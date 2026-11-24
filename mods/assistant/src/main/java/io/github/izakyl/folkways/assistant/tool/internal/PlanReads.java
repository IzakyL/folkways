package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.plan.internal.Cooldowns;
import io.github.izakyl.folkways.core.plan.internal.PlanInputs;
import io.github.izakyl.folkways.core.plan.internal.PlanTask;
import io.github.izakyl.folkways.core.runtime.LaborPlan;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;

final class PlanReads {

    private static final Gson JSON = new Gson();
    private static final int BUDGET = 40 * 1024;

    private record View(ServerLevel level, LaborPlan plan) {
    }

    private PlanReads() {
    }

    static void register(ToolRegistry registry) {
        JsonObject filters = stringField("vocation");
        filters.add("site", ReadTools.positionSchema());
        filters.add("resident_id", stringField("resident_id").get("resident_id"));
        JsonObject nodes = filters.deepCopy();
        nodes.add("doing", stringField("doing").get("doing"));
        paging(nodes);
        registry.register(ToolRegistry.spec("plan_nodes", "Read the last plan's nodes and direct dependencies. "
                + "No solving. Cursor is bound to this snapshot and filters; restart on stale_cursor.", nodes),
                (context, args) -> read(context, args, "nodes"));
        registry.register(ToolRegistry.spec("plan_node", "Read one node's dependencies, diagnostics, cooldown "
                + "and blockedBy/root from the last plan.", stringField("id"), "id"),
                (context, args) -> read(context, args, "node"));
        JsonObject inputs = stringField("kind");
        paging(inputs);
        registry.register(ToolRegistry.spec("plan_inputs", "Read last planning inputs: stock, workshops, stands "
                + "or ways. Workshop processes are observed offers, not an exhaustive recipe catalogue.", inputs),
                (context, args) -> read(context, args, "inputs"));
        JsonObject events = stringField("node");
        events.add("resident_id", stringField("resident_id").get("resident_id"));
        events.add("since_tick", integer());
        events.add("limit", integer());
        registry.register(ToolRegistry.spec("plan_events", "Read the colony's last 512 digested planner messages; "
                + "tick is the planning input tick. since_tick is inclusive.", events),
                (context, args) -> read(context, args, "events"));
        registry.register(ToolRegistry.spec("trace", "Explain matching last-plan tasks as assigned, waiting, "
                + "refused, cooling or stalled, with structural flags, codes and localized reasons.", filters),
                (context, args) -> read(context, args, "trace"));
    }

    private static JsonObject event(LaborPlan.Event event) {
        JsonObject row = new JsonObject();
        row.addProperty("type", switch (event.type()) {
            case SUBMITTED -> "Submitted";
            case WITHDRAWN -> "Withdrawn";
            case OFFERED -> "Offered";
            case STARTED -> "Started";
            case FINISHED -> "Finished";
            case FAILED -> "Failed";
            case RELEASED -> "Released";
            case LEFT -> "Left";
            case IDLE -> "Idle";
            case PACK_BUSY -> "PackBusy";
            case ANSWERED -> "Answered";
        });
        if (event.node() != null) {
            row.addProperty("node", event.node().toString());
        }
        if (event.resident() != null) {
            row.addProperty("resident", event.resident().toString());
        }
        if (event.why() != null) {
            row.addProperty("why", event.why().translationKey());
        }
        row.addProperty("tick", event.tick());
        row.addProperty("dimension", event.dimension().location().toString());
        return row;
    }

    private static JsonObject stringField(String key) {
        JsonObject fields = new JsonObject();
        JsonObject field = new JsonObject();
        field.addProperty("type", "string");
        fields.add(key, field);
        return fields;
    }

    private static JsonObject integer() {
        JsonObject field = new JsonObject();
        field.addProperty("type", "integer");
        return field;
    }

    private static void paging(JsonObject fields) {
        fields.add("limit", integer());
        fields.add("cursor", stringField("cursor").get("cursor"));
    }

    static ToolReply read(ToolContext context, JsonObject args, String kind) {
        return read(context, args, kind, true);
    }

    static ToolReply read(ToolContext context, JsonObject args, String kind, boolean demand) {
        long requested;
        try {
            requested = args.has("limit") ? args.get("limit").getAsBigDecimal().longValueExact() : 64;
        } catch (ArithmeticException invalid) {
            return ToolReply.error("invalid_limit");
        }
        if (requested < 1 || requested > 64) {
            return ToolReply.error("invalid_limit");
        }
        int limit = (int) requested;
        if (args.has("since_tick") && args.get("since_tick").getAsLong() < 0) {
            return ToolReply.error("invalid_tick");
        }
        if (kind.equals("inputs") && args.has("kind")
                && !List.of("stock", "workshops", "stands", "ways").contains(args.get("kind").getAsString())) {
            return ToolReply.error("invalid_arguments");
        }
        for (String key : List.of("resident_id", "node", "id")) {
            if (args.has(key)) {
                UUID.fromString(args.get(key).getAsString());
            }
        }
        List<View> views = new ArrayList<>();
        for (ServerLevel level : context.player().getServer().getAllLevels()) {
            LaborPlan.of(level, context.colony().id()).ifPresent(plan -> {
                if (demand) {
                    plan.demand(level.getGameTime());
                }
                views.add(new View(level, plan));
            });
        }
        views.sort(Comparator.comparing(view -> view.level().dimension().location().toString()));
        String revision = views.stream().map(view -> view.level().dimension().location() + ":"
                + view.plan().rounds() + ":" + view.plan().scheduleTick()).reduce("", (a, b) -> a + "/" + b);
        JsonObject query = args.deepCopy();
        query.remove("cursor");
        query.remove("limit");
        String binding = UUID.nameUUIDFromBytes((context.colony().id() + ":" + kind + ":" + query + ":" + revision)
                .getBytes(StandardCharsets.UTF_8)).toString();
        int offset = 0;
        if (args.has("cursor")) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(args.get("cursor").getAsString()),
                        StandardCharsets.UTF_8);
                int split = decoded.lastIndexOf('\n');
                if (split < 0) {
                    return ToolReply.error("invalid_cursor");
                }
                offset = Integer.parseInt(decoded.substring(split + 1));
                if (!decoded.substring(0, split).equals(binding)) {
                    return ToolReply.error("stale_cursor");
                }
                if (offset < 0) {
                    return ToolReply.error("invalid_cursor");
                }
            } catch (IllegalArgumentException invalid) {
                return ToolReply.error("invalid_cursor");
            }
        }
        boolean warming = views.isEmpty() || views.stream().anyMatch(view -> view.plan().warming());
        List<Supplier<JsonObject>> rows = new ArrayList<>();
        long capture = -1;
        for (View view : views) {
            LaborPlan plan = view.plan();
            if (plan.tick() >= 0) {
                capture = capture < 0 ? plan.tick() : Math.min(capture, plan.tick());
            }
            if (kind.equals("inputs")) {
                inputs(context, args, view, rows);
            } else if (kind.equals("events")) {
                // Each dimension's reader shares the same colony ring.
                if (view != views.getFirst()) {
                    continue;
                }
                for (LaborPlan.Event event : plan.events().reversed()) {
                    if (args.has("since_tick") && event.tick() < args.get("since_tick").getAsLong()
                            || !matches(args, "node", event.node())
                            || !matches(args, "resident_id", event.resident())) {
                        continue;
                    }
                    rows.add(() -> event(event));
                }
            } else {
                for (PlanTask task : plan.tasks()) {
                    if (!matches(args, "id", task.id()) || !matches(args, "doing", task.doing())
                            || !matches(args, "vocation", task.vocation())
                            || !matches(args, "resident_id", plan.resident(task))) {
                        continue;
                    }
                    if (args.has("site") && !args.get("site").equals(
                            ToolText.position(task.site(), context.player().getServer()))) {
                        continue;
                    }
                    rows.add(() -> {
                        JsonObject row = task(context, view, task, kind.equals("node"));
                        return kind.equals("trace") ? trace(view, task, row) : row;
                    });
                }
            }
        }
        if (kind.equals("node") && rows.isEmpty()) {
            ToolReply reply = ToolReply.error("unknown_node");
            reply.data().addProperty("warming", warming);
            return reply;
        }
        JsonObject result = new JsonObject();
        result.addProperty("colony_id", context.colony().id().toString());
        result.addProperty("capture_tick", capture);
        result.add("age_ticks", capture < 0 ? JsonNull.INSTANCE
                : JSON.toJsonTree(Math.max(0, context.player().serverLevel().getGameTime() - capture)));
        result.addProperty("available", capture >= 0);
        result.addProperty("warming", warming);
        if (offset > rows.size()) {
            return ToolReply.error("invalid_cursor");
        }
        JsonArray page = new JsonArray();
        int index = offset;
        int bytes = 512;
        int maximum = kind.equals("node") ? 1 : kind.equals("trace") ? Integer.MAX_VALUE : limit;
        while (index < rows.size() && page.size() < maximum) {
            JsonObject row = rows.get(index).get();
            trim(row);
            int size = row.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > BUDGET) {
                break;
            }
            bytes += size;
            page.add(row);
            index++;
        }
        boolean truncated = index < rows.size();
        result.add(kind.equals("node") ? "nodes" : kind, page);
        result.addProperty("truncated", truncated || page.asList().stream()
                .anyMatch(row -> row.getAsJsonObject().has("truncated")));
        if (kind.equals("nodes") || kind.equals("inputs")) {
            result.add("next_cursor", truncated ? JSON.toJsonTree(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((binding + "\n" + index).getBytes(StandardCharsets.UTF_8))) : JsonNull.INSTANCE);
        }
        if (kind.equals("node")) {
            result.add("node", page.get(0));
            result.remove("nodes");
        }
        while (true) {
            ToolText text = new ToolText();
            JsonObject output = result.deepCopy();
            if (kind.equals("node")) {
                describe(output.getAsJsonObject("node"), text);
            } else {
                output.getAsJsonArray(kind).forEach(row -> describe(row.getAsJsonObject(), text));
            }
            ToolReply reply = text.reply(output);
            if (!reply.capped(context.player().registryAccess()).data().has("error")) {
                return reply;
            }
            if (kind.equals("node")) {
                if (!shorten(result.getAsJsonObject("node"))) {
                    return ToolReply.error("result_size_cap");
                }
                result.addProperty("truncated", true);
                continue;
            }
            if (page.size() <= 1) {
                if (page.isEmpty() || !shorten(page.get(0).getAsJsonObject())) {
                    return ToolReply.error("result_size_cap");
                }
                result.addProperty("truncated", true);
                continue;
            }
            page.remove(page.size() - 1);
            index--;
            result.addProperty("truncated", true);
            if (result.has("next_cursor")) {
                result.addProperty("next_cursor", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString((binding + "\n" + index).getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    private static void describe(JsonObject row, ToolText text) {
        if (row.has("reason_code")) {
            String code = row.remove("reason_code").getAsString();
            boolean unassigned = row.remove("reason_unassigned").getAsBoolean();
            row.add("text", text.text(unassigned ? LaborReasons.unassigned(code) : LaborReasons.shortage(code)));
        }
        if (row.has("reasons")) {
            for (JsonElement element : row.getAsJsonArray("reasons")) {
                JsonObject reason = element.getAsJsonObject();
                String code = reason.get("code").getAsString();
                reason.add("text", text.text(reason.get("kind").getAsString().equals("unassigned")
                        ? LaborReasons.unassigned(code) : LaborReasons.shortage(code)));
            }
        }
    }

    private static boolean matches(JsonObject args, String field, Object value) {
        return !args.has(field) || value != null && args.get(field).getAsString().equals(value.toString());
    }

    private static JsonObject task(ToolContext context, View view, PlanTask task, boolean detail) {
        LaborPlan plan = view.plan();
        JsonObject row = new JsonObject();
        row.addProperty("id", task.id().toString());
        row.addProperty("doing", task.doing());
        if (task.vocation() != null) {
            row.addProperty("vocation", task.vocation());
        }
        row.add("site", ToolText.position(task.site(), context.player().getServer()));
        row.addProperty("owner", task.owner());
        row.add("needs", JSON.toJsonTree(task.needs()));
        row.add("gives", JSON.toJsonTree(task.gives()));
        row.add("before", JSON.toJsonTree(plan.before(task.id())));
        row.add("after", JSON.toJsonTree(plan.after(task.id())));
        row.add("group", JSON.toJsonTree(plan.group(task.id())));
        row.addProperty("held", plan.held(task.id()));
        row.addProperty("on_graph", plan.onGraph(task.id()));
        row.addProperty("resident", plan.resident(task) == null ? null : plan.resident(task).toString());
        row.addProperty("capture_tick", plan.tick());
        row.addProperty("schedule_tick", plan.scheduleTick());
        row.addProperty("age_ticks", Math.max(0, view.level().getGameTime() - plan.tick()));
        if (detail) {
            row.add("reasons", JSON.toJsonTree(plan.reasons(task)));
            Cooldowns.Bar bar = cooldown(plan, task);
            JsonObject cooling = new JsonObject();
            if (bar != null) {
                cooling.addProperty("failures", bar.failures());
                cooling.addProperty("retry_at", bar.retryAt());
            }
            row.add("cooldown", bar == null ? JsonNull.INSTANCE : cooling);
            JsonArray upstream = new JsonArray();
            JsonArray downstream = new JsonArray();
            for (PlanTask other : plan.tasks()) {
                if (plan.before(task.id()).contains(other.id())) {
                    upstream.add(task(context, view, other, false));
                }
                if (plan.after(task.id()).contains(other.id())) {
                    downstream.add(task(context, view, other, false));
                }
            }
            row.add("upstream", upstream);
            row.add("downstream", downstream);
        }
        return row;
    }

    private static Cooldowns.Bar cooldown(LaborPlan plan, PlanTask task) {
        Cooldowns.Bar bar = plan.cooldowns().get(task.id());
        if (bar == null) {
            for (LaborPlan.Reason reason : plan.reasons(task)) {
                for (UUID hole : reason.holes()) {
                    Cooldowns.Bar found = plan.cooldowns().get(hole);
                    if (found != null && (bar == null || found.retryAt() > bar.retryAt())) {
                        bar = found;
                    }
                }
            }
        }
        return bar;
    }

    private static JsonObject trace(View view, PlanTask task, JsonObject source) {
        LaborPlan plan = view.plan();
        JsonObject row = new JsonObject();
        for (String key : List.of("doing", "vocation", "site", "resident", "capture_tick", "age_ticks")) {
            if (source.has(key)) {
                row.add(key, source.get(key));
            }
        }
        row.addProperty("node", task.id().toString());
        String outcome = plan.assigned(task.id()) ? "assigned" : "waiting";
        List<LaborPlan.Reason> reasons = plan.reasons(task);
        LaborPlan.Reason primary = reasons.stream().filter(reason -> reason.kind().equals("shortfall"))
                .findFirst().orElse(reasons.isEmpty() ? null : reasons.getFirst());
        Cooldowns.Bar bar = cooldown(plan, task);
        if (primary != null) {
            String code = primary.code();
            if (primary.kind().equals("unassigned")) {
                outcome = primary.blockedBy() == null ? "waiting" : "stalled";
                row.addProperty("reason_unassigned", true);
            } else {
                outcome = code.equals("COOLING") ? "cooling" : "refused";
                row.addProperty("reason_unassigned", false);
            }
            row.addProperty("reason_code", code);
            String normalized = code;
            if (primary.kind().equals("unassigned")
                    || List.of("NO_SOURCE", "NO_ROOM", "NO_WAY", "NO_HAND", "COOLING").contains(code)) {
                normalized = code.toLowerCase(Locale.ROOT);
            } else if (code.startsWith("TURNED:")) {
                normalized = "turned";
            }
            row.addProperty("code", normalized);
            row.addProperty("structural", primary.structural());
            row.add("items", JSON.toJsonTree(reasons.stream().filter(reason -> reason.item() != null)
                    .map(reason -> new PlanInputs.Ware(reason.item(), reason.missing())).toList()));
        } else {
            row.addProperty("structural", false);
        }
        if (bar != null && bar.retryAt() > plan.tick()) {
            outcome = "cooling";
            row.addProperty("retry_at", bar.retryAt());
            row.addProperty("failures", bar.failures());
            row.addProperty("code", "cooling");
            row.addProperty("reason_code", "COOLING");
            row.addProperty("reason_unassigned", false);
        }
        row.addProperty("outcome", outcome);
        return row;
    }

    private static void inputs(ToolContext context, JsonObject args, View view, List<Supplier<JsonObject>> rows) {
        String kind = args.has("kind") ? args.get("kind").getAsString() : null;
        int first = rows.size();
        PlanInputs inputs = view.plan().inputs();
        if (kind == null || kind.equals("stock")) {
            inputs.stock().forEach(stock -> rows.add(() -> {
                JsonObject row = JSON.toJsonTree(stock).getAsJsonObject();
                row.addProperty("kind", "stock");
                return row;
            }));
        }
        if (kind == null || kind.equals("workshops")) {
            inputs.workshops().forEach(shop -> rows.add(() -> {
                JsonObject row = new JsonObject();
                row.addProperty("kind", "workshops");
                row.addProperty("id", shop.id().toString());
                row.addProperty("owner", shop.owner());
                row.add("site", ToolText.position(shop.site(), context.player().getServer()));
                row.add("processes", JSON.toJsonTree(inputs.processes().stream()
                        .filter(process -> process.workshop().equals(shop.id())).toList()));
                row.addProperty("processes_complete", false);
                return row;
            }));
        }
        for (String summary : List.of("stands", "ways")) {
            if (kind == null || kind.equals(summary)) {
                rows.add(() -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("kind", summary);
                    row.addProperty("stashes", inputs.stashes());
                    row.addProperty("stands", inputs.stands());
                    row.addProperty("hands", inputs.hands());
                    if (summary.equals("ways")) {
                        row.addProperty("provider", inputs.ways());
                    }
                    return row;
                });
            }
        }
        for (int index = first; index < rows.size(); index++) {
            Supplier<JsonObject> source = rows.get(index);
            rows.set(index, () -> {
                JsonObject row = source.get();
                row.addProperty("dimension", view.level().dimension().location().toString());
                row.addProperty("capture_tick", view.plan().tick());
                row.addProperty("age_ticks", Math.max(0, view.level().getGameTime() - view.plan().tick()));
                return row;
            });
        }
    }

    private static void trim(JsonObject row) {
        while (row.toString().getBytes(StandardCharsets.UTF_8).length > BUDGET / 2) {
            if (!shorten(row)) {
                break;
            }
        }
    }

    private static boolean shorten(JsonObject row) {
        JsonArray largest = null;
        for (var entry : row.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonArray() && !value.getAsJsonArray().isEmpty()
                    && (largest == null || value.toString().length() > largest.toString().length())) {
                largest = value.getAsJsonArray();
            }
        }
        if (largest == null) {
            return false;
        }
        int left = largest.size() / 2;
        while (largest.size() > left) {
            largest.remove(largest.size() - 1);
        }
        row.addProperty("truncated", true);
        return true;
    }
}
