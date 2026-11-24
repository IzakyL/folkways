package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;

public final class Surveys {

    public record Corridor(UUID id, UUID colony, List<WorldPos> points) {

        public Corridor {
            points = List.copyOf(points);
        }
    }

    private static final Map<UUID, List<Corridor>> SESSIONS = new HashMap<>();

    private Surveys() {
    }

    public static List<Corridor> of(ToolContext context) {
        return SESSIONS.getOrDefault(context.player().getUUID(), List.of()).stream()
                .filter(corridor -> corridor.colony().equals(context.colony().id())).toList();
    }

    static ActionRegistry.Plan prepare(ToolContext context, JsonObject args) {
        var level = SurveyReads.level(context, args.get("dimension").getAsString());
        var values = args.getAsJsonArray("points");
        if (values.size() < 2 || values.size() > 1025) {
            throw new ActionRegistry.Refused("invalid_survey_size");
        }
        List<WorldPos> points = new ArrayList<>();
        double length = 0;
        for (var value : values) {
            var pos = SurveyReads.pos(value.getAsJsonObject());
            if (!level.isInWorldBounds(pos)) {
                throw new ActionRegistry.Refused("outside_world");
            }
            WorldPos address = WorldPos.of(level, pos);
            if (!points.isEmpty()) {
                WorldPos previous = points.getLast();
                if (!previous.sameRealm(address)) {
                    throw new ActionRegistry.Refused("mixed_realms");
                }
                length += Math.sqrt(previous.cell().distSqr(address.cell()));
            }
            points.add(address);
        }
        if (length > 1024) {
            throw new ActionRegistry.Refused("survey_too_long");
        }
        Corridor corridor = new Corridor(UUID.randomUUID(), context.colony().id(), points);
        return new ActionRegistry.Plan(ActionSummaries.of(context, "request_survey", args), args.deepCopy(),
                context.colony().kept(Charters.OWNER).toString() + points, () -> {
                    List<Corridor> held = SESSIONS.computeIfAbsent(context.player().getUUID(),
                            ignored -> new ArrayList<>());
                    held.add(corridor);
                    if (held.size() > 4) {
                        held.removeFirst();
                    }
                    JsonObject result = args.deepCopy();
                    result.addProperty("changed", true);
                    return CompletableFuture.completedFuture(new ToolReply(result, List.of()));
                });
    }

    public static void forget(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    static void clear() {
        SESSIONS.clear();
    }
}
