package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.core.plan.internal.Resource;
import io.github.izakyl.folkways.core.plan.internal.Unassigned;
import io.github.izakyl.folkways.core.runtime.LaborSnapshot;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class LaborReasons {

    private LaborReasons() {
    }

    public static JsonObject snapshot(LaborSnapshot snapshot, MinecraftServer server) {
        Gson json = new Gson();
        JsonObject row = new JsonObject();
        row.add("census", json.toJsonTree(snapshot.census()));
        row.addProperty("solvingMillis", snapshot.solvingMillis());
        row.addProperty("scheduleMillis", snapshot.scheduleMillis());
        row.addProperty("limitedSchedules", snapshot.limitedSchedules());
        row.add("shortages", json.toJsonTree(snapshot.shortages()));
        row.add("examples", json.toJsonTree(snapshot.examples()));
        JsonArray suspended = new JsonArray();
        for (var held : snapshot.suspended()) {
            JsonObject entry = new JsonObject();
            var work = held.work();
            JsonObject state = new JsonObject();
            state.addProperty("node", work.node().toString());
            state.addProperty("lastResident", work.lastResident().toString());
            state.addProperty("sinceTick", work.sinceTick());
            state.addProperty("owner", work.owner().toString());
            state.addProperty("reason", work.reason().name());
            entry.add("work", state);
            JsonArray inputs = new JsonArray();
            for (var input : held.inputs()) {
                JsonObject wanted = new JsonObject();
                wanted.addProperty("node", input.node().toString());
                wanted.addProperty("goods", input.goods().describe());
                wanted.addProperty("count", input.count());
                wanted.addProperty("pending", input.pending());
                input.store().ifPresent(at -> wanted.add("store", ToolText.position(at, server)));
                input.resident().ifPresent(id -> wanted.addProperty("resident", id.toString()));
                input.producer().ifPresent(id -> wanted.addProperty("producer", id.toString()));
                inputs.add(wanted);
            }
            entry.add("inputs", inputs);
            JsonArray claims = new JsonArray();
            for (var claim : held.claims()) {
                JsonObject claimed = new JsonObject();
                claimed.addProperty("owner", claim.owner().toString());
                claimed.addProperty("amount", claim.amount());
                claimed.add("on", resource(claim.on(), server));
                claims.add(claimed);
            }
            entry.add("claims", claims);
            suspended.add(entry);
        }
        row.add("suspended", suspended);
        return row;
    }

    private static JsonObject resource(Resource resource, MinecraftServer server) {
        JsonObject row = new JsonObject();
        switch (resource) {
            case Resource.Transport transport -> {
                row.addProperty("type", "transport");
                row.addProperty("resource", transport.resource().toString());
                row.addProperty("unit", transport.unit().toString());
            }
            case Resource.Lot lot -> {
                row.addProperty("type", "lot");
                row.add("where", ToolText.position(lot.where().pos(), server));
                row.addProperty("goods", lot.spec().describe());
            }
            case Resource.Transit transit -> {
                row.addProperty("type", "transit");
                row.add("where", ToolText.position(transit.where().pos(), server));
                row.addProperty("goods", transit.spec().describe());
            }
            case Resource.Space space -> {
                row.addProperty("type", "space");
                row.add("where", ToolText.position(space.where().pos(), server));
                row.addProperty("goods", space.spec().describe());
            }
            case Resource.Cargo cargo -> {
                row.addProperty("type", "cargo");
                row.addProperty("resident", cargo.resident().toString());
                row.addProperty("goods", cargo.spec().describe());
            }
            case Resource.Handed handed -> {
                row.addProperty("type", "handed");
                row.addProperty("producer", handed.by().toString());
                row.addProperty("goods", handed.spec().describe());
            }
        }
        return row;
    }

    public static void describe(JsonObject snapshot, ToolText text) {
        snapshot.add("shortage_reasons", reasons(snapshot.getAsJsonObject("shortages"), text, false));
        JsonObject census = snapshot.getAsJsonObject("census");
        census.add("unassigned_reasons", reasons(census.getAsJsonObject("unassigned"), text, true));
    }

    private static JsonArray reasons(JsonObject counts, ToolText text, boolean unassigned) {
        JsonArray reasons = new JsonArray();
        counts.entrySet().forEach(entry -> {
            JsonObject reason = new JsonObject();
            reason.addProperty("code", entry.getKey());
            reason.add("count", entry.getValue().deepCopy());
            reason.add("text", text.text(unassigned ? unassigned(entry.getKey()) : shortage(entry.getKey())));
            reasons.add(reason);
        });
        return reasons;
    }

    static Component shortage(String code) {
        String key = switch (code) {
            case "NO_SOURCE" -> "no_source";
            case "NO_ROOM" -> "no_room";
            case "NO_WAY" -> "no_way";
            case "NO_HAND" -> "no_hand";
            case "COOLING" -> "cooling";
            default -> code.startsWith("TURNED:") ? "turned" : "unknown";
        };
        return Component.translatable("folkways.assistant.shortage." + key);
    }

    static Component unassigned(String code) {
        try {
            var reason = Unassigned.Reason.valueOf(code);
            return Component.translatable("folkways.assistant.unassigned." + reason.name().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Component.translatable("folkways.assistant.shortage.unknown");
        }
    }
}
