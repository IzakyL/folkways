package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.CaptureRequest;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.api.ToolSpec;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptApi;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost.RunReply;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.github.izakyl.jpi.agent.AgentTool;
import io.github.izakyl.jpi.agent.AgentToolResult;
import io.github.izakyl.jpi.ai.CancellationToken;
import io.github.izakyl.jpi.ai.ContentBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.world.phys.AABB;

// The same two-phase flow serves packet proxies and direct-registry integration tests.
public final class ConfirmedAction implements AgentTool {

    public interface Transport {

        CompletableFuture<RunReply> run(String script, CancellationToken cancellation);

        CompletableFuture<ToolReply> decide(String revision, boolean accept, CancellationToken cancellation);
    }

    public interface Capture {

        CompletableFuture<AgentToolResult> take(CaptureRequest camera, CancellationToken cancellation);

        /**
         * Shows what a run staged in the world, as its review would, while that run's shots are taken. The returned
         * action puts the world back as it was.
         */
        default Runnable stage(ToolReply staged) {
            return () -> { };
        }
    }

    public interface Confirmation {

        CompletableFuture<Boolean> ask(ToolReply preview, CancellationToken cancellation);
    }

    private final ToolSpec spec;

    private final Transport transport;

    private final Confirmation confirmation;

    private final Capture capture;

    public ConfirmedAction(boolean privileged, Transport transport, Confirmation confirmation, Capture capture) {
        this.spec = ScriptApi.run(privileged);
        this.transport = transport;
        this.confirmation = confirmation;
        this.capture = capture;
    }

    @Override
    public String name() {
        return spec.id();
    }

    @Override
    public String label() {
        return name().replace('_', ' ');
    }

    @Override
    public String description() {
        return spec.description();
    }

    @Override
    public JsonObject parameters() {
        return spec.schema();
    }

    @Override
    public AgentToolResult execute(String callId, JsonObject arguments, CancellationToken cancellation,
            Consumer<AgentToolResult> onUpdate) throws Exception {
        cancellation.throwIfCancelled();
        RunReply run = transport.run(arguments.get("script").getAsString(), cancellation).get();
        ToolReply reply = run.reply();
        JsonArray captures = new JsonArray();
        List<ContentBlock> content = new ArrayList<>();
        boolean held = false;
        if (!reply.data().has("error") && !reply.data().has("truncated") && !run.captures().isEmpty()) {
            held = reply.data().has("units");
            shots(run.captures(), reply, held, captures, content, cancellation);
        }
        JsonObject result;
        if (held) {
            result = hold(reply, cancellation);
        } else {
            result = reply.data().has("units") ? decideUnits(reply, cancellation) : reply.localized();
        }
        if (!captures.isEmpty()) {
            result.add("captures", captures);
        }
        if (run.historyReads() > 0) {
            result.addProperty("history_reads", run.historyReads());
        }
        content.addFirst(new ContentBlock.Text(result.toString()));
        return new AgentToolResult(List.copyOf(content), result, result.has("error") || result.has("truncated"));
    }

    // A run that both stages changes and captures them is a check by the model: the shots show the staged changes
    // as previews, and the changes are dropped instead of being put to the player.
    private void shots(List<JsonObject> cameras, ToolReply reply, boolean staged, JsonArray captures,
            List<ContentBlock> content, CancellationToken cancellation) throws Exception {
        AABB changes = CaptureRequest.changes(reply.ghosts().stream().map(Ghost::pos).toList(),
                reply.data().has("intents") ? reply.data().getAsJsonArray("intents") : null);
        List<List<CaptureRequest>> planned = new ArrayList<>();
        int images = 0;
        for (JsonObject camera : cameras) {
            try {
                List<CaptureRequest> shots = CaptureRequest.expand(camera, changes);
                shots = shots.subList(0, Math.min(shots.size(), CaptureRequest.MAX_PER_RUN - images));
                images += shots.size();
                planned.add(shots);
            } catch (IllegalStateException | IllegalArgumentException | ClassCastException unframed) {
                planned.add(null);
            }
        }
        int size = images > CaptureRequest.FULL_SIZE_IMAGES ? CaptureRequest.SET_SIZE : CaptureRequest.FULL_SIZE;
        Runnable restore = staged ? capture.stage(reply) : () -> { };
        try {
            for (List<CaptureRequest> shots : planned) {
                if (shots == null) {
                    JsonObject failure = new JsonObject();
                    failure.addProperty("ok", false);
                    failure.addProperty("error", "nothing_to_frame");
                    captures.add(failure);
                    continue;
                }
                for (CaptureRequest shot : shots) {
                    if (cancellation.isCancelled()) {
                        return;
                    }
                    AgentToolResult taken = capture.take(shot.sized(size), cancellation).get();
                    captures.add(taken.details());
                    taken.content().stream().filter(ContentBlock.Image.class::isInstance).forEach(content::add);
                }
            }
        } finally {
            restore.run();
        }
    }

    private JsonObject hold(ToolReply preview, CancellationToken cancellation) throws Exception {
        List<CompletableFuture<ToolReply>> dropped = new ArrayList<>();
        for (ToolReply unit : reviewUnits(preview)) {
            dropped.add(transport.decide(unit.data().get("revision").getAsString(), false, cancellation));
        }
        for (var drop : dropped) {
            drop.handle((reply, failure) -> reply).get();
        }
        JsonObject result = preview.localized();
        for (String field : List.of("units", "revision", "preview_dimension")) {
            result.remove(field);
        }
        result.addProperty("held", true);
        result.addProperty("message", "The staged changes appear in the captures only and were not sent for "
                + "review. Check them, then run the final script without capture() to ask the player.");
        return result;
    }

    private JsonObject decideUnits(ToolReply preview, CancellationToken cancellation) throws Exception {
        var decisions = new ArrayList<CompletableFuture<JsonObject>>();
        for (ToolReply unit : reviewUnits(preview)) {
            var answer = cancellation.isCancelled() ? CompletableFuture.completedFuture(false)
                    : confirmation.ask(unit, cancellation);
            var subscription = cancellation.onCancel(() -> answer.cancel(false));
            decisions.add(answer.handle((accepted, failure) -> {
                subscription.close();
                boolean cancelled = answer.isCancelled() || cancellation.isCancelled();
                return transport.decide(unit.data().get("revision").getAsString(),
                        failure == null && !cancelled && accepted, new CancellationToken())
                        .handle((reply, transportFailure) -> {
                            JsonObject outcome = transportFailure == null ? reply.localized() : new JsonObject();
                            if (cancelled) {
                                outcome.remove("error");
                                outcome.addProperty("declined", true);
                                outcome.addProperty("message", "session cancelled");
                            } else if (failure != null || transportFailure != null) {
                                outcome.addProperty("error", failure != null ? "stale" : "action_failed");
                            }
                            outcome.addProperty("revision", unit.data().get("revision").getAsString());
                            outcome.addProperty("subject", unit.component("subject").getString());
                            outcome.addProperty("status", outcome.has("error") ? "failed"
                                    : outcome.has("declined") ? "rejected" : "applied");
                            return outcome;
                        });
            }).thenCompose(result -> result));
        }
        CompletableFuture.allOf(decisions.toArray(CompletableFuture[]::new)).get();
        JsonObject result = preview.localized();
        for (String field : List.of("units", "intents", "summary", "revision", "preview_dimension")) {
            result.remove(field);
        }
        JsonArray outcomes = new JsonArray();
        JsonArray applied = new JsonArray();
        int rejected = 0;
        int cancelled = 0;
        for (var decision : decisions) {
            JsonObject outcome = decision.join();
            for (String field : List.of("result", "prints", "capture_tick", "colony_id")) {
                outcome.remove(field);
            }
            if (outcome.has("applied")) {
                applied.addAll(outcome.getAsJsonArray("applied"));
            }
            if (outcome.get("status").getAsString().equals("rejected")) {
                rejected++;
                if (outcome.get("message").getAsString().equals("session cancelled")) {
                    cancelled++;
                }
            }
            outcomes.add(outcome);
        }
        result.add("outcomes", outcomes);
        result.add("applied", applied);
        if (rejected == outcomes.size()) {
            result.addProperty("declined", true);
            result.addProperty("message", cancelled > 0 ? "session cancelled" : "player rejected");
        }
        if (outcomes.size() == 1 && outcomes.get(0).getAsJsonObject().has("error")) {
            JsonObject failure = outcomes.get(0).getAsJsonObject();
            for (String field : List.of("error", "code", "refusal", "failure", "failed_intent")) {
                if (failure.has(field)) {
                    result.add(field, failure.get(field));
                }
            }
        }
        return result;
    }

    public static List<ToolReply> reviewUnits(ToolReply preview) {
        if (!preview.data().has("units")) {
            return List.of();
        }
        JsonArray intents = preview.data().getAsJsonArray("intents");
        List<ToolReply> units = new ArrayList<>();
        for (JsonElement entry : preview.data().getAsJsonArray("units")) {
            JsonObject unit = entry.getAsJsonObject().deepCopy();
            JsonArray changes = new JsonArray();
            for (JsonElement index : unit.getAsJsonArray("changes")) {
                changes.add(intents.get(index.getAsInt()));
            }
            unit.add("intents", changes);
            unit.add("preview_dimension", preview.data().get("preview_dimension"));
            units.add(new ToolReply(unit, preview.texts(), preview.ghosts().subList(
                    unit.get("ghost_start").getAsInt(), unit.get("ghost_end").getAsInt())));
        }
        return List.copyOf(units);
    }
}
