package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyGround;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyLooks;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySites;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyStocks;
import io.github.izakyl.folkways.core.front.engine.colony.HeldFacilities;
import io.github.izakyl.folkways.core.front.engine.colony.Residents;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.runtime.LaborSnapshot;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class ReadTools {

    private static final Gson JSON = new Gson();

    private ReadTools() {
    }

    public static ToolRegistry create() {
        ToolRegistry registry = new ToolRegistry();
        register(registry);
        return registry;
    }

    public static void register(ToolRegistry registry) {
        registry.register(ToolRegistry.spec("colony_overview",
                "Read the current colony's name, keepers, holdings, counts, labor metrics and blockers.",
                new JsonObject()), ReadTools::overview);
        registry.register(ToolRegistry.spec("list_residents",
                "List all residents by stable entity UUID, with loaded activity, health, needs and balks.",
                new JsonObject()), ReadTools::residents);
        registry.register(ToolRegistry.spec("resident_detail",
                "Read one colony resident's needs, activity, recent balk, trades, licences and perks.",
                properties("resident_id", "string"), "resident_id"), ReadTools::resident);
        registry.register(ToolRegistry.spec("colony_problems",
                "Read stalls and translated explanations for waiting work and missing supplies. "
                        + "Includes generic site descriptions and housing advice.",
                new JsonObject()), ReadTools::problems);
        registry.register(ToolRegistry.spec("stock",
                "Count known colony stock and resident packs; unopened loot and unloaded stores stay unknown. "
                        + "Optionally filter by exact item registry id.", properties("item", "string")),
                ReadTools::stock);
        JsonObject target = properties("entity_id", "integer");
        target.add("at", positionSchema());
        registry.register(ToolRegistry.spec("look_target",
                "Describe a resident or a held block. "
                        + "Supply entity_id or at.", target),
                ReadTools::target);
        registry.register(ToolRegistry.spec("player_context",
                "Read your position, dimension, time, weather and inventory counts.", new JsonObject()),
                ReadTools::player);
        SurveyReads.register(registry);
        RailReads.register(registry);
        DiagnosticReads.register(registry);
        PlanReads.register(registry);
        registry.register(ToolRegistry.spec("patterns", "List survival patterns, accepted marks, knob definitions "
                + "and localized pattern constraints.", new JsonObject()), DraftTools::patterns);
    }

    public static void registerDirect(ToolRegistry.DirectReads registry) {
        registry.register(DraftTools.spec(), DraftTools::draft);
        registry.register(MechanicsReads.spec(), (player, args) ->
                CompletableFuture.completedFuture(MechanicsReads.read(player, args)));
        MechanismReads.register(registry);
        for (var function : WorldReads.functions()) {
            registry.register(function.spec(), (player, args) -> CompletableFuture.completedFuture(
                    WorldReads.read(player, function.target(), args).capped(player.registryAccess())));
        }
    }

    private static JsonObject properties(String key, String type) {
        JsonObject properties = new JsonObject();
        JsonObject field = new JsonObject();
        field.addProperty("type", type);
        properties.add(key, field);
        return properties;
    }

    static JsonObject positionSchema() {
        JsonObject fields = properties("dimension", "string");
        for (String axis : List.of("x", "y", "z")) {
            fields.add(axis, properties(axis, "integer").get(axis));
        }
        return ToolRegistry.spec("at", "World block position", fields, "dimension", "x", "y", "z").schema();
    }

    static ToolReply overview(ToolContext context, JsonObject args) {
        ToolText text = new ToolText();
        Colony colony = context.colony();
        var server = context.player().getServer();
        var charter = Charters.of(server, colony);
        JsonObject data = new JsonObject();
        data.add("name", text.text(Charters.title(server, charter)));
        JsonArray members = new JsonArray();
        charter.members().forEach(id -> members.add(id.toString()));
        data.add("members", members);
        data.addProperty("resident_count", colony.residents().size());
        data.addProperty("loaded_resident_count", Residents.of(colony, server).size());
        data.addProperty("zones", ColonyFront.of(colony).zones().size());
        data.addProperty("paths", ColonyFront.of(colony).paths().size());
        JsonArray holdings = new JsonArray();
        for (var holding : colony.claims()) {
            JsonObject row = new JsonObject();
            row.addProperty("holding_id", holding.id().toString());
            row.addProperty("owner", holding.owner().toString());
            row.addProperty("shape", holding.what().getClass().getSimpleName());
            row.addProperty("cells", holding.what().cells().size());
            holding.what().cells().stream().findFirst()
                    .ifPresent(at -> row.add("at", ToolText.position(at, context.player().getServer())));
            holdings.add(row);
        }
        data.add("holdings", holdings);
        data.add("problems", problemData(context, text));
        return text.reply(data);
    }

    private static ToolReply residents(ToolContext context, JsonObject args) {
        ToolText text = new ToolText();
        JsonObject data = new JsonObject();
        JsonArray residents = new JsonArray();
        for (Resident resident : context.colony().residents()) {
            residents.add(residentData(context, resident, text, false));
        }
        data.add("residents", residents);
        return text.reply(data);
    }

    static ToolReply resident(ToolContext context, JsonObject args) {
        UUID id = UUID.fromString(args.get("resident_id").getAsString());
        ToolText text = new ToolText();
        return context.colony().residents().stream().filter(resident -> resident.id().equals(id)).findFirst()
                .map(resident -> text.reply(residentData(context, resident, text, true)))
                .orElseGet(() -> ToolReply.error("not_colony_resident"));
    }

    private static JsonObject residentData(ToolContext context, Resident resident, ToolText text, boolean detail) {
        JsonObject data = new JsonObject();
        data.addProperty("resident_id", resident.id().toString());
        data.addProperty("kind", resident.kind().toString());
        Optional<Body> loaded = Residents.of(context.colony(), context.player().getServer(), resident.id());
        data.addProperty("status", loaded.isPresent() ? "loaded" : "unloaded");
        if (loaded.isEmpty()) {
            return data;
        }
        Body body = loaded.get();
        data.add("name", text.text(body.mob().getDisplayName()));
        data.add("at", ToolText.position(WorldSpaces.at(body.mob()), context.player().getServer()));
        data.addProperty("dimension", body.mob().level().dimension().location().toString());
        data.addProperty("health", body.mob().getHealth());
        data.addProperty("max_health", body.mob().getMaxHealth());
        data.add("lines", text.lines(ColonyLooks.of(context.colony(), body,
                context.colony().views(context.player().getServer()))));
        body.balk().ifPresent(balk -> data.addProperty("balk_code", balk.why().translationKey()));
        if (detail) {
            JsonArray trades = new JsonArray();
            for (var vocation : Vocations.all()) {
                body.licences().trade(vocation).ifPresent(trade -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("vocation", vocation.id().toString());
                    row.addProperty("allowed", body.licences().allows(trade));
                    row.addProperty("keenness", body.licences().keennessOf(trade).number());
                    row.addProperty("level", body.perks().level(vocation.id()));
                    row.addProperty("xp", body.perks().xp(vocation.id()));
                    trades.add(row);
                });
            }
            data.add("trades", trades);
            data.add("perks", JSON.toJsonTree(body.perks().ranks()));
        }
        return data;
    }

    static ToolReply problems(ToolContext context, JsonObject args) {
        ToolText text = new ToolText();
        return text.reply(problemData(context, text));
    }

    private static JsonObject problemData(ToolContext context, ToolText text) {
        JsonObject data = new JsonObject();
        JsonArray stalls = new JsonArray();
        JsonArray labor = new JsonArray();
        for (ServerLevel level : context.player().getServer().getAllLevels()) {
            for (var stall : context.colony().stallsIn(level.dimension())) {
                JsonObject row = new JsonObject();
                row.add("at", ToolText.position(stall.where(), context.player().getServer()));
                row.addProperty("dimension", level.dimension().location().toString());
                row.addProperty("code", stall.why().translationKey());
                row.addProperty("doing", stall.doing().toString());
                Notice.Arg about = stall.goods().flatMap(goods -> goods.item())
                        .map(id -> Notice.named(BuiltInRegistries.ITEM.get(id).getDescriptionId()))
                        .orElseGet(() -> stall.about().map(Notice::named).orElseGet(() -> Notice.text("")));
                row.add("text", text.text(Notice.of(stall.why(), about).component()));
                stalls.add(row);
            }
            LaborSnapshot.of(level, context.colony().id()).ifPresent(snapshot -> {
                JsonObject row = LaborReasons.snapshot(snapshot, context.player().getServer());
                LaborReasons.describe(row, text);
                row.addProperty("dimension", level.dimension().location().toString());
                row.addProperty("freshness", "last_plan_and_schedule");
                labor.add(row);
            });
        }
        data.add("stalls", stalls);
        data.add("labor", labor);
        JsonArray sites = new JsonArray();
        for (var holding : context.colony().claims()) {
            holding.what().singleCell().ifPresent(at -> {
                JsonObject row = new JsonObject();
                row.addProperty("holding_id", holding.id().toString());
                row.add("at", ToolText.position(at, context.player().getServer()));
                var resolved = WorldSpaces.resolve(context.player().getServer(), at)
                        .filter(block -> block.level().hasChunkAt(block.cell()));
                row.addProperty("status", resolved.isPresent() ? "loaded" : "unloaded");
                resolved.ifPresent(block -> {
                    row.add("lines", text.lines(HeldFacilities.find(context.colony(), holding.id())
                            .map(held -> held.lines(block.level(), Map.of())).orElse(List.of())));
                    row.addProperty("dimension", block.level().dimension().location().toString());
                });
                sites.add(row);
            });
        }
        data.add("sites", sites);
        data.addProperty("unknown", "Absent labor means no captured plan; empty diagnostics do not prove no problems.");
        return data;
    }

    private static Optional<WorldSpaces.Block> block(ToolContext context, JsonObject at) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(at.get("dimension").getAsString()));
        ServerLevel level = context.player().getServer().getLevel(dimension);
        BlockPos pos = new BlockPos(at.get("x").getAsInt(), at.get("y").getAsInt(), at.get("z").getAsInt());
        return level == null || !level.hasChunkAt(pos) ? Optional.empty()
                : Optional.of(new WorldSpaces.Block(level, pos));
    }

    private static ToolReply stock(ToolContext context, JsonObject args) {
        ToolText text = new ToolText();
        JsonObject data = new JsonObject();
        JsonArray goods = new JsonArray();
        ColonyStocks.tally(context.colony(), context.player().getServer()).entrySet().stream()
                .sorted(Comparator.<Map.Entry<ItemSpec, Integer>, Integer>comparing(Map.Entry::getValue)
                        .reversed().thenComparing(entry -> entry.getKey().toString()))
                .forEach(entry -> {
            ItemSpec item = entry.getKey();
            int count = entry.getValue();
            String id = item.item().orElseThrow().toString();
            if (!args.has("item") || args.get("item").getAsString().equals(id)) {
                JsonObject row = text.ware(id, count);
                row.addProperty("query", ToolText.query(item, context.player().registryAccess()));
                row.add("name", text.text(Goods.display(item).getHoverName()));
                goods.add(row);
            }
        });
        int unknown = 0;
        Set<ResourceKey<Level>> counted = context.colony().views(context.player().getServer()).stream()
                .map(view -> view.dimension()).collect(Collectors.toSet());
        for (ServerLevel level : context.player().getServer().getAllLevels()) {
            for (WorldPos store : context.colony().storesIn(level.dimension())) {
                var container = WorldSpaces.storage(level, store).filter(level::hasChunkAt)
                        .flatMap(cell -> Containers.at(level, cell));
                if (!counted.contains(level.dimension()) || container.isEmpty() || !Containers.known(container.get())) {
                    unknown++;
                }
            }
        }
        int unloaded = context.colony().residents().size()
                - Residents.of(context.colony(), context.player().getServer()).size();
        data.addProperty("scope", "physical_known_stock_before_reservations");
        data.add("goods", goods);
        data.addProperty("unknown_stores", unknown);
        data.addProperty("unloaded_resident_packs", unloaded);
        data.addProperty("complete", unknown == 0 && unloaded == 0);
        return text.reply(data);
    }

    private static ToolReply target(ToolContext context, JsonObject args) {
        if (args.has("entity_id")) {
            Entity entity = context.player().serverLevel().getEntity(args.get("entity_id").getAsInt());
            if (entity == null) {
                return ToolReply.error("unloaded");
            }
            JsonObject resident = new JsonObject();
            resident.addProperty("resident_id", entity.getUUID().toString());
            return resident(context, resident);
        }
        if (!args.has("at")) {
            return ToolReply.error("no_target");
        }
        var target = block(context, args.getAsJsonObject("at"));
        if (target.isEmpty()) {
            return ToolReply.error("unloaded");
        }
        var level = target.get().level();
        var pos = ColonySites.anchor(level, target.get().cell());
        if (!level.hasChunkAt(pos)) {
            return ToolReply.error("unloaded");
        }
        if (!ColonyGround.holds(level, context.colony(), pos)) {
            return ToolReply.error("not_colony_target");
        }
        ToolText text = new ToolText();
        JsonObject data = new JsonObject();
        data.add("at", ToolText.position(WorldPos.of(level, pos), context.player().getServer()));
        data.addProperty("dimension", level.dimension().location().toString());
        var held = HeldFacilities.at(level, context.colony(), pos);
        held.ifPresent(facility -> data.addProperty("facility_id", facility.id().toString()));
        data.add("lines", text.lines(held.map(facility -> facility.lines(level, Map.of())).orElse(List.of())));
        return text.reply(data);
    }

    private static ToolReply player(ToolContext context, JsonObject args) {
        ToolText text = new ToolText();
        JsonObject data = new JsonObject();
        var player = context.player();
        var level = player.serverLevel();
        data.add("at", ToolText.position(WorldSpaces.at(player), player.getServer()));
        data.addProperty("dimension", level.dimension().location().toString());
        data.addProperty("day_time", level.getDayTime());
        data.addProperty("raining", level.isRaining());
        data.addProperty("thundering", level.isThundering());
        JsonArray inventory = new JsonArray();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                JsonObject row = text.ware(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
                row.addProperty("slot", slot);
                inventory.add(row);
            }
        }
        data.add("inventory", inventory);
        return text.reply(data);
    }
}
