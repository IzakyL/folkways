package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.StateRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.github.izakyl.folkways.core.front.api.Ghost;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public final class ActionBatch {

    public record Intent(String action, JsonObject arguments, String path, JsonElement before, JsonElement after) {

        public Intent(String action, JsonObject arguments, String path) {
            this(action, arguments, path, null, null);
        }

        public Intent(String action, JsonObject arguments) {
            this(action, arguments, null);
        }

        void identify(JsonObject data) {
            if (path != null) {
                data.addProperty("path", path);
            } else {
                data.addProperty("action", action);
            }
        }
    }

    private record Signed(Intent intent, String revision) {
    }

    private record Pending(UUID colony, ResourceKey<Level> dimension, long tick, ToolReply output,
            List<Signed> intents) {
    }

    private final Map<ServerPlayer, Map<String, Pending>> pending = new WeakHashMap<>();

    private final Map<ServerPlayer, CompletableFuture<ToolReply>> applying = new WeakHashMap<>();

    private final ActionRegistry actions;

    public ActionBatch(ActionRegistry actions) {
        this.actions = actions;
    }

    CompletableFuture<ToolReply> previewState(ServerPlayer player, StateRegistry states,
            StateRegistry.Snapshot snapshot, ResourceKey<Level> dimension, List<StateRegistry.Change> changes,
            ToolReply output, BooleanSupplier cancelled) {
        if (changes.isEmpty()) {
            return CompletableFuture.completedFuture(output.capped(player.registryAccess()));
        }
        var current = states.snapshot(player, false);
        if (current.colony() == null) {
            return CompletableFuture.completedFuture(ToolReply.error("not_member"));
        }
        if (!current.colony().equals(snapshot.colony()) || !player.serverLevel().dimension().equals(dimension)) {
            return CompletableFuture.completedFuture(ToolReply.error("stale"));
        }
        List<Intent> intents = new ArrayList<>();
        List<Intent> runs = new ArrayList<>();
        Map<String, JsonElement> staged = new HashMap<>();
        changes.forEach(change -> staged.put(change.path(), change.after()));
        try {
            for (var change : changes) {
                if (!current.get(change.path()).equals(change.before())) {
                    return CompletableFuture.completedFuture(ToolReply.error("stale"));
                }
                var operation = states.operation(change.path(), change.after());
                var args = operation.arguments();
                JsonElement after = change.after();
                if (operation.name().equals("commission_build") && args.has("source")) {
                    JsonObject redacted = after.getAsJsonObject().deepCopy();
                    redacted.remove("source");
                    redacted.addProperty("pattern", "inline");
                    after = redacted;
                }
                Intent intent = new Intent(operation.name(), args, change.path(), change.before(), after);
                if (operation.name().equals("run")) {
                    String definition = change.path().substring(0, change.path().lastIndexOf("/runs/"))
                            + "/definition";
                    if (!snapshot.get(definition).equals(current.get(definition))) {
                        return CompletableFuture.completedFuture(ToolReply.error("stale"));
                    }
                    args.add("definition", staged.getOrDefault(definition, snapshot.get(definition)));
                    runs.add(intent);
                } else {
                    intents.add(intent);
                }
            }
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(ActionRegistry.error(invalid));
        }
        intents.addAll(runs);
        return previewAsync(player, intents, output, cancelled);
    }

    public ToolReply preview(ServerPlayer player, List<Intent> intents, ToolReply output) {
        if (intents.isEmpty()) {
            return output.capped(player.registryAccess());
        }
        return preview(player, intents, output, intents.stream()
                .map(intent -> actions.preview(player, intent.action(), intent.arguments())).toList());
    }

    public CompletableFuture<ToolReply> previewAsync(ServerPlayer player, List<Intent> intents, ToolReply output) {
        return previewAsync(player, intents, output, () -> false);
    }

    public CompletableFuture<ToolReply> previewAsync(ServerPlayer player, List<Intent> intents, ToolReply output,
            BooleanSupplier cancelled) {
        var colony = CurrentColony.of(player).map(Colony::id);
        var dimension = player.serverLevel().dimension();
        CompletableFuture<List<ToolReply>> previews = CompletableFuture.completedFuture(new ArrayList<>());
        for (Intent intent : intents) {
            previews = previews.thenCompose(held -> {
                if (cancelled.getAsBoolean() || held.stream().anyMatch(reply -> reply.data().has("error"))) {
                    return CompletableFuture.completedFuture(held);
                }
                return actions.previewAsync(player, intent.action(), intent.arguments()).thenApply(reply -> {
                    held.add(reply);
                    return held;
                });
            });
        }
        return previews.thenApply(replies -> {
            if (cancelled.getAsBoolean()) {
                return ToolReply.error("cancelled");
            }
            if (!CurrentColony.of(player).map(Colony::id).equals(colony)
                    || !player.serverLevel().dimension().equals(dimension)) {
                return ToolReply.error("stale");
            }
            return preview(player, intents, output, replies);
        });
    }

    private ToolReply preview(ServerPlayer player, List<Intent> intents, ToolReply output, List<ToolReply> previews) {
        if (intents.isEmpty()) {
            return output.capped(player.registryAccess());
        }
        List<Signed> signed = new ArrayList<>();
        List<Component> texts = new ArrayList<>(output.texts());
        JsonArray changes = new JsonArray();
        var context = CurrentColony.of(player).map(colony -> new ToolContext(player, colony));
        for (Intent intent : intents) {
            ToolReply preview = previews.get(signed.size());
            if (preview.data().has("error") || preview.data().has("truncated")) {
                return preview;
            }
            signed.add(new Signed(intent, preview.data().get("revision").getAsString()));
            JsonObject change = new JsonObject();
            intent.identify(change);
            change.addProperty("action", intent.action());
            if (intent.before() != null) {
                change.add("before", intent.before());
                change.add("after", intent.after());
            }
            change.addProperty("category", preview.data().get("category").getAsString());
            change.addProperty("destructive", preview.data().get("destructive").getAsBoolean());
            change.addProperty("explicit_approval", preview.data().get("explicit_approval").getAsBoolean());
            JsonObject reference = new JsonObject();
            reference.addProperty("$text", texts.size());
            texts.add(preview.component("summary"));
            change.add("summary", reference);
            if (preview.data().has("details")) {
                change.add("details", preview.data().get("details").deepCopy());
            }
            context.flatMap(held -> Places.of(held, intent, preview)).ifPresent(place -> change.add("place", place));
            changes.add(change);
        }
        try {
            FacilityTools.checkOverlaps(intents, previews);
        } catch (ActionRegistry.Refused refusal) {
            return ActionRegistry.error(refusal);
        }
        JsonObject data = output.data().deepCopy();
        data.add("intents", changes);
        JsonArray units = new JsonArray();
        long now = player.serverLevel().getGameTime();
        var dimension = player.serverLevel().dimension();
        data.addProperty("preview_dimension", dimension.location().toString());
        List<Ghost> ghosts = new ArrayList<>();
        Map<String, Pending> offered = new HashMap<>();
        var groups = ReviewSubjects.group(context, intents, previews);
        long drawings = previews.stream().filter(preview -> !preview.ghosts().isEmpty()).count();
        for (var group : groups) {
            JsonObject unit = new JsonObject();
            String revision = UUID.randomUUID().toString();
            unit.addProperty("revision", revision);
            long issued = group.stream().mapToLong(index -> previews.get(index).data().get("capture_tick").getAsLong())
                    .min().orElseThrow();
            unit.addProperty("capture_tick", issued);
            int primary = group.stream().filter(index -> intents.get(index).action().equals("commission_build"))
                    .findFirst().orElseGet(() -> group.stream()
                            .filter(index -> intents.get(index).action().equals("set_facility"))
                            .findFirst().orElse(group.getFirst()));
            var title = context.map(held -> ActionSummaries.subject(held, intents.get(primary), previews.get(primary)))
                    .orElseGet(() -> previews.get(primary).component("summary"));
            unit.add("subject", reference(texts, title));
            var summary = Component.empty();
            JsonArray indices = new JsonArray();
            int ghostStart = ghosts.size();
            for (int index : group) {
                indices.add(index);
                ToolReply preview = previews.get(index);
                if (!summary.getSiblings().isEmpty()) {
                    summary.append("\n");
                }
                summary.append(preview.component("summary"));
                if (!preview.ghosts().isEmpty()) {
                    ghosts.addAll(Ghost.limited(preview.ghosts(), (int) (Ghost.MAX_PREVIEW_GHOSTS / drawings),
                            player.blockPosition()));
                }
            }
            unit.add("summary", reference(texts, summary));
            unit.add("changes", indices);
            unit.addProperty("ghost_start", ghostStart);
            unit.addProperty("ghost_end", ghosts.size());
            units.add(unit);
            ToolReply result = groups.size() == 1 ? output : new ToolReply(new JsonObject(), List.of());
            offered.put(revision, new Pending(CurrentColony.of(player).orElseThrow().id(), dimension, issued,
                    result, group.stream().map(signed::get).toList()));
        }
        data.add("units", units);
        if (units.size() == 1) {
            JsonObject unit = units.get(0).getAsJsonObject();
            for (String field : List.of("revision", "capture_tick", "summary")) {
                data.add(field, unit.get(field));
            }
        }
        ToolReply reply = new ToolReply(data, texts, ghosts).capped(player.registryAccess());
        if (!reply.data().has("truncated")) {
            Map<String, Pending> held = pending.computeIfAbsent(player, ignored -> new HashMap<>());
            held.entrySet().removeIf(entry -> now - entry.getValue().tick() > ActionRegistry.CONFIRMATION_TICKS);
            // A script can offer up to 32 writes. Keep room for its complete set of units.
            if (held.size() + offered.size() > 64) {
                held.clear();
            }
            held.putAll(offered);
        }
        return reply;
    }

    private static JsonObject reference(List<Component> texts, Component text) {
        JsonObject reference = new JsonObject();
        reference.addProperty("$text", texts.size());
        texts.add(text);
        return reference;
    }

    public CompletableFuture<ToolReply> decide(ServerPlayer player, String revision, boolean accept) {
        return decide(player, revision, accept, false);
    }

    /** raise sets builds down at once for a player in creative; otherwise they go to the colony as orders. */
    public CompletableFuture<ToolReply> decide(ServerPlayer player, String revision, boolean accept, boolean raise) {
        if (!player.getServer().isSameThread()) {
            throw new IllegalStateException("Batch decisions must run on the server thread");
        }
        Map<String, Pending> held = pending.get(player);
        Pending batch = held == null ? null : held.remove(revision);
        if (batch == null) {
            return CompletableFuture.completedFuture(ToolReply.error("stale"));
        }
        if (!accept) {
            JsonObject rejected = batch.output().data().deepCopy();
            rejected.addProperty("declined", true);
            rejected.addProperty("message", "player rejected");
            return CompletableFuture.completedFuture(new ToolReply(rejected, batch.output().texts())
                    .capped(player.registryAccess()));
        }
        var previous = applying.get(player);
        var result = previous == null || previous.isDone() ? validateAndApply(player, batch, raise)
                : previous.handleAsync((reply, failure) -> null, player.getServer())
                        .thenCompose(ignored -> validateAndApply(player, batch, raise));
        applying.put(player, result);
        result.whenComplete((reply, failure) -> player.getServer().execute(() -> applying.remove(player, result)));
        return result;
    }

    private CompletableFuture<ToolReply> validateAndApply(ServerPlayer player, Pending batch, boolean raise) {
        var colony = CurrentColony.of(player);
        if (colony.isEmpty()) {
            return CompletableFuture.completedFuture(ToolReply.error("not_member"));
        }
        if (player.serverLevel().getGameTime() - batch.tick() > ActionRegistry.CONFIRMATION_TICKS
                || !colony.get().id().equals(batch.colony())
                || !player.serverLevel().dimension().equals(batch.dimension())) {
            return CompletableFuture.completedFuture(ToolReply.error("stale"));
        }
        CompletableFuture<Void> validated = CompletableFuture.completedFuture(null);
        for (Signed signed : batch.intents()) {
            validated = validated.thenCompose(ignored -> actions.validateAsync(player, signed.intent().action(),
                    signed.intent().arguments(), signed.revision()).thenAccept(plan -> { }));
        }
        return validated.handle((ignored, failure) -> failure).thenCompose(failure -> {
            if (failure != null) {
                while (failure.getCause() != null) {
                    failure = failure.getCause();
                }
                return CompletableFuture.completedFuture(ToolReply.error(failure instanceof ActionRegistry.Refused
                        ? failure.getMessage() : "stale"));
            }
            return apply(player, batch, raise && player.isCreative());
        });
    }

    private CompletableFuture<ToolReply> apply(ServerPlayer player, Pending batch, boolean raise) {
        JsonArray applied = new JsonArray();
        CompletableFuture<ToolReply> result = new CompletableFuture<>();
        applyNext(player, batch, raise, 0, applied, new ArrayList<>(batch.output().texts()), result);
        return result;
    }

    private void applyNext(ServerPlayer player, Pending batch, boolean raise, int index, JsonArray applied,
            List<Component> texts, CompletableFuture<ToolReply> result) {
        if (index == batch.intents().size()) {
            JsonObject data = batch.output().data().deepCopy();
            data.add("applied", applied);
            result.complete(new ToolReply(data, texts).capped(player.registryAccess()));
            return;
        }
        Signed signed = batch.intents().get(index);
        Intent intent = signed.intent();
        apply(player, batch, signed, raise).whenComplete((reply, failure) -> {
            Runnable next = () -> {
                if (failure != null || reply == null || reply.data().has("error")) {
                    JsonObject data = batch.output().data().deepCopy();
                    if (reply != null) {
                        data.add("failure", references(reply.data(), texts.size()));
                        texts.addAll(reply.texts());
                    }
                    data.addProperty("error", failure == null && reply != null ? reply.data().get("error").getAsString()
                            : "action_failed");
                    data.addProperty("failed_intent", index);
                    data.add("applied", applied);
                    result.complete(new ToolReply(data, texts).capped(player.registryAccess()));
                    return;
                }
                JsonObject changed = references(reply.data(), texts.size()).getAsJsonObject();
                texts.addAll(reply.texts());
                intent.identify(changed);
                applied.add(changed);
                applyNext(player, batch, raise, index + 1, applied, texts, result);
            };
            if (player.getServer().isSameThread()) {
                next.run();
            } else {
                player.getServer().execute(next);
            }
        });
    }

    private CompletableFuture<ToolReply> apply(ServerPlayer player, Pending batch, Signed signed, boolean raise) {
        try {
            Colony colony = CurrentColony.of(player).orElse(null);
            if (colony == null) {
                return CompletableFuture.completedFuture(ToolReply.error("not_member"));
            }
            if (!colony.id().equals(batch.colony()) || !player.serverLevel().dimension().equals(batch.dimension())) {
                return CompletableFuture.completedFuture(ToolReply.error("stale"));
            }
            Intent intent = signed.intent();
            return actions.applyApproved(player, intent.action(), intent.arguments(), signed.revision(), raise);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static JsonElement references(JsonElement value, int offset) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.size() == 1 && object.has("$text")) {
                JsonObject reference = new JsonObject();
                reference.addProperty("$text", object.get("$text").getAsInt() + offset);
                return reference;
            }
            JsonObject result = new JsonObject();
            object.entrySet().forEach(entry -> result.add(entry.getKey(), references(entry.getValue(), offset)));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(entry -> result.add(references(entry, offset)));
            return result;
        }
        return value.deepCopy();
    }

    public static boolean autoApprove(ToolReply preview, Set<String> categories) {
        if (!preview.data().has("intents")) {
            return false;
        }
        for (JsonElement entry : preview.data().getAsJsonArray("intents")) {
            JsonObject intent = entry.getAsJsonObject();
            if (intent.get("explicit_approval").getAsBoolean() || intent.get("destructive").getAsBoolean()
                    || !categories.contains(intent.get("category").getAsString())) {
                return false;
            }
        }
        return true;
    }

    void forget(ServerPlayer player) {
        pending.remove(player);
    }
}
