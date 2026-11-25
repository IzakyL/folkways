package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.api.ToolSpec;
import io.github.izakyl.folkways.assistant.tool.internal.ReadTools;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.jpi.agent.Agent;
import io.github.izakyl.jpi.ai.AssistantMessage;
import io.github.izakyl.jpi.ai.CancellationToken;
import io.github.izakyl.jpi.ai.Model;
import io.github.izakyl.jpi.ai.StreamOptions;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class AgentIntegrationGameTests {

    private AgentIntegrationGameTests() {
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void agentExposesOnlyRunAndReadsColonyThroughLocalSse(GameTestHelper helper) throws IOException {
        conversation(helper, false, false);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void agentConfirmsScriptBatchThroughLocalSse(GameTestHelper helper) throws IOException {
        conversation(helper, true, true);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void agentRejectsScriptBatchThroughLocalSse(GameTestHelper helper) throws IOException {
        conversation(helper, true, false);
    }

    private static void conversation(GameTestHelper helper, boolean rename, boolean approve) throws IOException {
        ReadFixture fixture = new ReadFixture(helper);
        String script = "result = get(\"colony\")" + (rename
                ? "\nset(\"residents/\" + ls(\"residents\")[0] + \"/name\", \"Ada\")" : "");
        ActionSseServer server = new ActionSseServer(script);
        ToolRegistry observed = new ToolRegistry();
        ToolRegistry reads = ReadTools.create();
        for (ToolSpec spec : reads.specs()) {
            observed.register(spec, (context, args) -> {
                ToolReply[] reply = {null};
                Purity.assertPure(helper, "agent script read " + spec.id(), () -> {
                    fixture.warm();
                    reply[0] = reads.execute(context.player(), spec.id(), args);
                });
                return reply[0];
            });
        }
        ScriptHost host = new ScriptHost(1_000_000, 10_000, 2000, observed);
        AtomicInteger decisions = new AtomicInteger();
        ConfirmedAction.Transport transport = new ConfirmedAction.Transport() {
            @Override
            public CompletableFuture<ScriptHost.RunReply> run(String script, CancellationToken cancellation) {
                return fixture.player.getServer().submit(() -> host.run(fixture.player, script, new JsonObject(), "{}"))
                        .thenCompose(result -> result);
            }

            @Override
            public CompletableFuture<ToolReply> decide(String revision, boolean accept, CancellationToken cancellation) {
                return fixture.player.getServer().submit(() -> {
                    AtomicReference<CompletableFuture<ToolReply>> result = new AtomicReference<>();
                    Runnable action = () -> result.set(host.decide(fixture.player, revision, accept));
                    if (accept) {
                        action.run();
                    } else {
                        Purity.assertPure(helper, "agent rejected batch", action);
                    }
                    return result.get();
                }).thenCompose(result -> result);
            }
        };
        ConfirmedAction run = new ConfirmedAction(false, transport, (preview, cancellation) -> {
            decisions.incrementAndGet();
            return fixture.player.getServer().submit(() -> {
                helper.assertTrue(fixture.person.getCustomName() == null, "preview has not mutated the resident");
                helper.assertTrue(preview.data().getAsJsonArray("intents").size() == 1, "one batch card");
                return approve;
            });
        }, (camera, cancellation) -> CompletableFuture.failedFuture(new AssertionError("unexpected capture")));
        Agent agent = new Agent(Model.builder("fake-script", server.url()).build(),
                "Use run to read the colony and perform the requested rename, then answer.",
                List.of(run), StreamOptions.builder().apiKey("fake-key").build());
        var answer = agent.prompt(rename ? "Read my colony and rename one resident to Ada." : "How is my colony?");
        AsyncWait.succeedWhen(helper, answer, () -> {
            helper.assertTrue(answer.getNow(List.of()).stream().filter(AssistantMessage.class::isInstance)
                    .map(AssistantMessage.class::cast).anyMatch(message -> message.text().equals("Done.")),
                    "agent produced final answer");
            helper.assertTrue(server.requests.size() == 2, "exactly two model requests");
            var tools = server.requests.getFirst().getAsJsonArray("tools");
            helper.assertTrue(tools.size() == 1, "model sees exactly one tool");
            helper.assertTrue(tools.get(0).getAsJsonObject().getAsJsonObject("function").get("name")
                    .getAsString().equals("run"), "model calls run");
            String messages = server.requests.get(1).getAsJsonArray("messages").toString();
            helper.assertTrue(messages.contains(fixture.colony.id().toString()), "script read reached model");
            helper.assertTrue(decisions.get() == (rename ? 1 : 0), "exactly one decision per batch");
            if (rename) {
                helper.assertTrue(messages.contains(approve ? "applied" : "player rejected"),
                        "batch outcome reached model");
            }
            helper.assertTrue(approve ? Component.literal("Ada").equals(fixture.person.getCustomName())
                    : fixture.person.getCustomName() == null, "only confirmed batch changes the world");
        }, () -> {
            agent.abort();
            server.close();
            fixture.close();
        });
    }
}
