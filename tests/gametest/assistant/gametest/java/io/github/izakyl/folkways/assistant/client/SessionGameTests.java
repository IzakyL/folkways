package io.github.izakyl.folkways.assistant.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.assistant.tool.api.ConfirmationQueue;
import io.github.izakyl.jpi.agent.AgentEvent;
import io.github.izakyl.jpi.agent.AgentTool;
import io.github.izakyl.jpi.agent.AgentToolResult;
import io.github.izakyl.jpi.ai.AssistantMessage;
import io.github.izakyl.jpi.ai.ContentBlock;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class SessionGameTests {

    private SessionGameTests() {
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void detachingAViewRetainsTheStreamAndPendingDecision(GameTestHelper helper) {
        var transcript = new AssistantTranscript(() -> {}, text -> {});
        AtomicInteger draws = new AtomicInteger();
        var view = transcript.subscribe(draws::incrementAndGet);
        transcript.note(Component.literal("Question"));
        transcript.receive(new AgentEvent.MessageStart(message("")));
        view.close();
        int detached = draws.get();
        transcript.receive(new AgentEvent.MessageUpdate(message("Still streaming"), null));
        helper.assertTrue(draws.get() == detached, "a detached panel gets no updates");
        helper.assertTrue(transcript.entries().getLast().equals(Component.literal("Still streaming")),
                "stream updates remain in the transcript without a panel");
        var again = transcript.subscribe(draws::incrementAndGet);
        transcript.receive(new AgentEvent.MessageUpdate(message("Reattached"), null));
        helper.assertTrue(draws.get() > detached, "the reopened view sees stream updates");
        again.close();
        ConfirmationQueue queue = new ConfirmationQueue();
        var pending = queue.add(Component.literal("Change?"), List.of(), List.of(), () -> true);
        helper.assertTrue(queue.pending().getFirst() == pending && !pending.decision().isDone(),
                "pending decisions belong to the session queue, independent of views");
        queue.cancel();
        helper.assertTrue(pending.decision().isCancelled(), "stop cancels pending approval");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void devPropertiesSelectTheFactoryWithoutAnApiKeyProperty(GameTestHelper helper) {
        var keys = List.of("folkways.assistant.provider", "folkways.assistant.baseUrl", "folkways.assistant.model");
        var previous = new HashMap<String, String>();
        keys.forEach(key -> previous.put(key, System.getProperty(key)));
        try {
            System.setProperty(keys.get(0), "openai-compatible");
            System.setProperty(keys.get(1), "http://127.0.0.1:9876/v1");
            System.setProperty(keys.get(2), "local-test-model");
            var settings = new AssistantSettings("openai-compatible", "https://example.invalid/v1",
                    "saved", Set.of()).effective();
            var runtime = AssistantModels.create(settings);
            helper.assertTrue(runtime.model().baseUrl().equals("http://127.0.0.1:9876/v1")
                    && runtime.model().id().equals("local-test-model"), "dev properties reach the model factory");
            helper.assertTrue(runtime.options().apiKey() == null && runtime.options().apiKeyResolver() != null,
                    "credentials are deferred to JpiMini's store resolver");
            helper.assertTrue(settings.autoApprove().isEmpty(), "auto-approval defaults off");
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void providerPresetsKeepReasoningReplay(GameTestHelper helper) {
        var deepseek = AssistantSettings.preset("deepseek", Set.of());
        var deepseekRuntime = AssistantModels.create(deepseek);
        helper.assertTrue(deepseek.model().equals("deepseek-v4-flash")
                && deepseekRuntime.model().provider().equals("deepseek") && deepseekRuntime.model().reasoning(),
                "DeepSeek defaults and reasoning preset selected");
        helper.assertTrue(deepseekRuntime.options().maxTokens() >= 4096,
                "reasoning has enough budget to reach tools and the answer");
        for (String provider : List.of("anthropic", "google", "deepseek", "openai-compatible")) {
            var preset = AssistantSettings.preset(provider, Set.of());
            var runtime = AssistantModels.create(preset);
            helper.assertTrue(runtime.options().maxRetries() == 2 && runtime.options().requestTimeoutMs() == 60000
                    && runtime.options().idleTimeoutMs() == 60000, "runtime has retry and timeout settings");
            if (provider.equals("anthropic") || provider.equals("google")) {
                String api = provider.equals("anthropic") ? "anthropic-messages" : "google-generative-ai";
                helper.assertTrue(runtime.model().input().contains("image") && runtime.model().api().equals(api)
                        && runtime.model().provider().equals(provider) && runtime.options().apiKeyResolver() != null,
                        "provider preset uses its API and stored key");
                var custom = new AssistantSettings(provider, preset.baseUrl(), "custom-model", Set.of());
                helper.assertTrue(AssistantModels.create(custom).model().api().equals(api),
                        "unknown models use the provider API");
            }
        }
        String prior = System.getProperty("folkways.assistant.provider");
        try {
            System.setProperty("folkways.assistant.provider", "deepseek");
            var overridden = new AssistantSettings("openai-compatible", "https://example.invalid/v1",
                    "old-model", Set.of()).effective();
            helper.assertTrue(overridden.model().equals("deepseek-v4-flash")
                    && overridden.baseUrl().equals("https://api.deepseek.com"),
                    "provider override selects its defaults");
        } finally {
            if (prior == null) {
                System.clearProperty("folkways.assistant.provider");
            } else {
                System.setProperty("folkways.assistant.provider", prior);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void settingsRoundTripAndKeepEntryCredentialsSeparate(GameTestHelper helper) throws Exception {
        var firstEntry = AssistantSettings.Entry.from(
                new AssistantSettings("deepseek", "https://api.deepseek.com", "saved", Set.of(), true));
        var second = AssistantSettings.Entry.from(AssistantSettings.preset("deepseek", Set.of()));
        var config = new AssistantSettings.Config(List.of(firstEntry, second), second.id(), Set.of("work"));
        var roundTrip = AssistantSettings.parse(new com.google.gson.Gson().toJson(config));
        var first = roundTrip.entries().getFirst().settings(roundTrip.autoApprove());
        helper.assertTrue(roundTrip.entries().size() == 2 && roundTrip.active().id().equals(second.id()),
                "multiple entries and selection survive serialization");
        var store = io.github.izakyl.jpi.mc.JpiMini.credentials();
        String entryKey = AssistantModels.credentialKey(first.id());
        var previous = store.get("deepseek");
        try {
            store.put("deepseek", new io.github.izakyl.jpi.auth.Credential.ApiKey("shared"));
            var runtime = AssistantModels.create(first);
            helper.assertTrue(runtime.options().apiKeyResolver().resolve(runtime.model()) == null,
                    "the provider-wide key is ignored");
            store.put(entryKey, new io.github.izakyl.jpi.auth.Credential.ApiKey("entry"));
            helper.assertTrue(runtime.options().apiKeyResolver().resolve(runtime.model()).equals("entry"),
                    "entry key takes precedence");
            var other = AssistantModels.create(second.settings(Set.of()));
            helper.assertTrue(other.options().apiKeyResolver().resolve(other.model()) == null,
                    "other entries do not use this entry key");
            helper.assertTrue(AssistantSettings.sameOrigin(first.baseUrl(), "https://API.DeepSeek.com:443/v1")
                    && !AssistantSettings.sameOrigin(first.baseUrl(), "https://other.example/v1")
                    && !AssistantSettings.sameOrigin(first.baseUrl(), "http://api.deepseek.com")
                    && !AssistantSettings.sameOrigin(first.baseUrl(), "https://api.deepseek.com:8443"),
                    "credential origins compare scheme, host and effective port");
            String priorProvider = System.getProperty("folkways.assistant.provider");
            try {
                System.setProperty("folkways.assistant.provider", "deepseek");
                helper.assertTrue(first.effective().id().equals(first.id()), "same-provider override retains entry");
                System.setProperty("folkways.assistant.provider", "anthropic");
                helper.assertTrue(first.effective().id() == null, "different-provider override discards entry key");
            } finally {
                if (priorProvider == null) {
                    System.clearProperty("folkways.assistant.provider");
                } else {
                    System.setProperty("folkways.assistant.provider", priorProvider);
                }
            }
            AssistantModels.removeCredentials(Set.of(first.id()));
            helper.assertTrue(store.get(entryKey).isEmpty() && store.get("deepseek").isPresent(),
                    "deleting an entry removes its key but preserves the shared provider key");
        } finally {
            store.remove(entryKey);
            if (previous.isPresent()) {
                store.put("deepseek", previous.get());
            } else {
                store.remove("deepseek");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void toolLinesUseTranslatedScriptLabels(GameTestHelper helper) {
        var run = (TranslatableContents) AssistantTranscript.toolLine().getContents();
        helper.assertTrue(run.getKey().equals("folkways.assistant.tool.run"), "run has a translated card");
        List<String> tools = AssistantModels.tools(false, null, null, null).stream().map(AgentTool::name).toList();
        helper.assertTrue(tools.equals(List.of("run")), "the client session exposes exactly one tool");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void scriptCardsRetainSourceAndResultAcrossDetachedViews(GameTestHelper helper) {
        var transcript = new AssistantTranscript(() -> {}, text -> {});
        JsonObject args = new JsonObject();
        args.addProperty("script", "result = stock()");
        ContentBlock.ToolCall call = new ContentBlock.ToolCall("script1", "run", args);
        var view = transcript.subscribe(() -> {});
        transcript.receive(new AgentEvent.ToolExecutionStart(call));
        view.close();
        int index = transcript.entries().size() - 1;
        helper.assertTrue(transcript.script(index).equals("result = stock()"), "script source belongs to the session");
        transcript.receive(new AgentEvent.ToolExecutionEnd(call,
                runResult("{\"prints\":[],\"result\":{\"goods\":[]}}", false)));
        helper.assertTrue(((TranslatableContents) transcript.result(index).getContents()).getKey()
                .equals("folkways.assistant.script_result"), "result summary remains localizable");
        var reopened = transcript.subscribe(() -> {});
        helper.assertTrue(transcript.script(index).equals("result = stock()"), "reattaching retains the source");
        reopened.close();
        transcript.clear();
        helper.assertTrue(transcript.entries().isEmpty(), "new conversation clears script cards");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void failedScriptCardsShowKindLineAndShortMessage(GameTestHelper helper) {
        var transcript = new AssistantTranscript(() -> {}, text -> {});
        JsonObject args = new JsonObject();
        args.addProperty("script", "result = (");
        ContentBlock.ToolCall call = new ContentBlock.ToolCall("syntax", "run", args);
        transcript.receive(new AgentEvent.ToolExecutionStart(call));
        transcript.receive(new AgentEvent.ToolExecutionEnd(call, runResult(
                "{\"error\":\"syntax_error\",\"message\":\"expected expression\",\"line\":3,\"column\":11}", true)));
        Component result = transcript.result(0);
        Component expected = Component.translatable("folkways.assistant.script_error.message",
                Component.translatable("folkways.assistant.script_error.line",
                        Component.translatable("folkways.assistant.script_error.syntax_error"), 3),
                "expected expression");
        helper.assertTrue(result.equals(expected), "card shows localized syntax error, line and diagnostic");
        ContentBlock.ToolCall limited = new ContentBlock.ToolCall("limited", "run", args);
        transcript.receive(new AgentEvent.ToolExecutionStart(limited));
        transcript.receive(new AgentEvent.ToolExecutionEnd(limited, runResult(
                "{\"error\":\"call_limit\",\"message\":\"" + "x".repeat(200) + "\"}", true)));
        Component shortened = Component.translatable("folkways.assistant.script_error.message",
                Component.translatable("folkways.assistant.script_error.call_limit"), "x".repeat(157) + "...");
        helper.assertTrue(transcript.result(1).equals(shortened), "call limit is translated and message capped at 160");
        transcript.clear();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void savedSessionsReloadPruneAndKeepPlayersSeparate(GameTestHelper helper) throws Exception {
        var world = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT);
        var root = Files.createTempDirectory(world, "assistant-history-test-");
        var player = UUID.randomUUID();
        var otherPlayer = UUID.randomUUID();
        var id = UUID.randomUUID();
        try {
            var original = new SessionHistory(root, "server:example", player);
            JsonObject snapshot = sessionSnapshot();
            original.save(id, snapshot);
            var reloaded = new SessionHistory(root, "server:example", player);
            var restored = reloaded.read(id);
            helper.assertTrue(restored.equals(snapshot) && restored.get("version").getAsInt() == 1,
                "the versioned transcript, tool summary and scratch survive reloading");
            var privateHistory = new SessionHistory(root, "server:example", otherPlayer);
            helper.assertTrue(privateHistory.list().isEmpty() && privateHistory.read(id) == null
                    && new SessionHistory(root, "server:other", player).read(id) == null,
                "another account or server cannot read the same session ID");
            UUID current = UUID.randomUUID();
            var registries = helper.getLevel().registryAccess();
            String historyPath = "client/history/" + id;
            var allowed = reloaded.scriptRead(current, historyPath, registries).getAsJsonObject();
            var entriesForScript = allowed.getAsJsonArray("entries");
            helper.assertTrue(allowed.keySet().equals(Set.of("title", "updated", "turns", "entries"))
                    && entriesForScript.get(0).getAsJsonObject().keySet().equals(Set.of("role", "text"))
                    && entriesForScript.get(0).getAsJsonObject().get("text").getAsString().equals("Ran script"),
                    "the mount exposes text and excludes scripts, results and scratch");
            helper.assertTrue(privateHistory.scriptRead(current, "client/history", registries).getAsJsonArray()
                    .isEmpty(), "other players cannot list the saved session");
            helper.assertTrue(reloaded.scriptRead(current, "client/history", registries).getAsJsonArray()
                    .get(0).getAsString().equals(id.toString())
                    && reloaded.scriptRead(id, "client/history", registries).getAsJsonArray().isEmpty(),
                    "the history mount excludes the current session");
            try (var files = Files.walk(root)) {
                var path = files.filter(file -> file.getFileName().toString().equals(id + ".json"))
                    .findFirst().orElseThrow();
                Files.setLastModifiedTime(path, FileTime.fromMillis(
                    System.currentTimeMillis() - SessionHistory.MAX_AGE - 1_000));
                reloaded.prune();
                helper.assertTrue(reloaded.list().isEmpty() && !Files.exists(path), "expired sessions are pruned");
            }
            for (int index = 0; index <= SessionHistory.MAX_SESSIONS; index++) {
                original.save(UUID.randomUUID(), sessionSnapshot());
            }
            helper.assertTrue(reloaded.list().size() == SessionHistory.MAX_SESSIONS, "session count is capped");
            JsonObject large = sessionSnapshot();
            JsonArray entries = large.getAsJsonArray("entries");
            entries.add("a".repeat(SessionHistory.MAX_BYTES));
            entries.add("most recent");
            original.save(id, large);
            var trimmed = reloaded.read(id);
            helper.assertTrue(trimmed != null && trimmed.getAsJsonArray("entries").size() == 1
                    && trimmed.getAsJsonArray("entries").get(0).getAsString().equals("most recent")
                    && trimmed.getAsJsonObject("scratch").equals(snapshot.getAsJsonObject("scratch")),
                "the byte cap retains recent entries and the scratch dictionary");
        } finally {
            try (var files = Files.walk(root)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        helper.succeed();
    }

    private static JsonObject sessionSnapshot() {
        var snapshot = new JsonObject();
        snapshot.addProperty("title", "Previous chat");
        snapshot.addProperty("turns", 1);
        var entries = new JsonArray();
        var entry = new JsonObject();
        entry.addProperty("role", "tool");
        entry.addProperty("component", "{\"text\":\"Ran script\"}");
        entry.addProperty("script", "result = 3");
        entry.addProperty("result", "{\"text\":\"Finished\"}");
        entries.add(entry);
        snapshot.add("entries", entries);
        var scratch = new JsonObject();
        scratch.addProperty("data", "scratch-value");
        snapshot.add("scratch", scratch);
        return snapshot;
    }

    private static AgentToolResult runResult(String json, boolean error) {
        return new AgentToolResult(List.of(new ContentBlock.Text(json)),
                JsonParser.parseString(json), error);
    }

    private static AssistantMessage message(String text) {
        return AssistantMessage.builder("openai-completions", "fake", "fake")
                .content(List.of(new ContentBlock.Text(text))).timestamp(0).build();
    }
}
