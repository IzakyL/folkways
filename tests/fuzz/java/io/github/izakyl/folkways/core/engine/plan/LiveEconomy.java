package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.FuzzLabor;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import io.github.izakyl.folkways.front.api.CoreSettings;
import io.github.izakyl.folkways.fuzz.Cases;
import io.github.izakyl.folkways.fuzz.Crew;
import io.github.izakyl.folkways.fuzz.LiveTarget;
import io.github.izakyl.folkways.fuzz.Plot;
import io.github.izakyl.folkways.fuzz.Rng;
import io.github.izakyl.folkways.fuzz.Violation;
import io.github.izakyl.folkways.plugins.person.FuzzColony;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * A colony's economy as it really runs, making and hauling together. A case lays chests of raw stock the
 * residents can or cannot reach and a random set of stations (crafting tables, furnaces, blast furnaces, smokers,
 * stonecutters, a smithing table), scattered over a platform {@link #HALF} cells each way, with coal or none, and
 * submits goals at their ticks: bring so many of something to a chest, or work at a chest with goods in hand,
 * which uses them up. Most goals call for goods several recipes away (a hopper minecart is iron smelted, logs
 * made into planks and a chest, then a hopper and a cart; a blast furnace takes stone cooked twice), drawn from
 * what the case's stations can make and laid with the raw stock and fuel they take, alongside others nothing
 * provides for, all competing for the same stock, stations and residents. Cases come small, middling and big: up
 * to {@link #MOST_STASHES} stores, {@link #MOST_STATIONS} stations, {@link #MOST_PEOPLE} residents and
 * {@link #MOST_GOALS} goals. Meanwhile a player may withdraw a goal, take or add stock, steal fuel, wall a chest
 * in or open one, break or take away a station, kill a resident or put junk in a furnace. Residents' kits are
 * switched off, so no tool goes to them, and the colony burns coal only. Judged when every goal has ended and the
 * residents have put away what they carried, when nothing at all has moved for {@link #STILL} ticks, or when the
 * time runs out. Residents tire and sleep whatever the work; time any of them spends asleep counts towards neither
 * standing still, nor a goal's time bound, nor the time given to put things away:
 * <ul>
 *   <li>when nothing disturbs the case and the stock it can reach covers every goal at once, however the colony
 *       chooses to make each ({@link EconomyBook#most}), every goal its stations can make ends, and ends done,
 *       once the case stands still or its time bound has passed (every item cooked one after another, and a
 *       margin for the walking); a goal whose making needs more cookers at once than the case has is kept
 *       apart ({@code .cookers-short}): one growth books every cooker it uses for its whole length, and a
 *       cooker takes bookings for one product at a time (stone cooked into smooth stone needs two);</li>
 *   <li>a goal done in such a case is one the stock could have made;</li>
 *   <li>a goal still open at the end, whose work has stopped moving, is one the colony's last plan names a reason
 *       for, about anything grown for it;</li>
 *   <li>no goods are made from nothing or lost: what the chests, stations, packs and ground hold, against what was
 *       laid, added, taken and used up, is explained by recipe runs alone, and the coal gone by what was cooked
 *       ({@link EconomyBook#balance});</li>
 *   <li>once all is over, nobody still carries goods, nothing lies loose and no cooker holds finished goods;</li>
 *   <li>no resident died, or fell off, of anything but the case killing it.</li>
 * </ul>
 * Every judgement is constructive: what the case laid, by construction, is what each goal is held to, so the
 * oracles stay sound however many stores, stations, residents and goals a case has.
 */
public final class LiveEconomy implements LiveTarget {

    static final int BUDGET = 40_000;
    /** How far the platform runs each way: room for stores and stations scattered far apart. */
    static final int HALF = 44;
    /** Ticks residents are given, once every goal has ended, to put what they carry away. */
    static final int DRAIN = 1_200;
    /** Ticks a goal's work may stand still before an open goal must have a reason. */
    static final int STUCK = 1_500;
    /** Ticks with nothing at all moving (no goal's work, no goods, no fire) after which the case is over. */
    static final int STILL = 4_000;
    /** Ticks a cooker takes per item, at the slowest (a furnace); what a time bound reckons with. */
    static final int COOK_TICKS = 200;
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_fuzz", "economy");
    private static final ResourceLocation WARES = ResourceLocation.fromNamespaceAndPath("folkways", "wares");

    private static final List<String> WOODS = List.of("oak", "birch");
    /** What goals ask to have made, and at most how many; {@code planks} is the case's wood. */
    private static final Map<String, Integer> MADE = ordered(
        "planks", 24, "#minecraft:planks", 24, "stick", 16, "ladder", 6, "chest", 3, "crafting_table", 2,
        "wooden_pickaxe", 2, "wooden_shovel", 2, "stone_pickaxe", 2, "stone_axe", 1, "stone_sword", 2,
        "furnace", 2, "iron_ingot", 10, "iron_pickaxe", 1, "iron_shovel", 1, "bucket", 2, "shears", 1, "rail", 16,
        "iron_bars", 16, "minecart", 1, "hopper", 1, "hopper_minecart", 1, "torch", 16, "stone", 12,
        "smooth_stone", 6, "stone_bricks", 12, "stone_slab", 12, "cobblestone_slab", 12, "stonecutter", 1,
        "blast_furnace", 1, "glass", 12, "glass_pane", 16, "netherite_pickaxe", 1);
    /** What goals ask to have carried, as laid; {@code log} is the case's wood. */
    private static final Map<String, Integer> HAULED = ordered("log", 32, "cobblestone", 40, "raw_iron", 16,
        "sand", 24, "coal", 8);
    private static final List<String> RAW = List.of("log", "cobblestone", "raw_iron", "sand", "coal",
        "netherite_upgrade_smithing_template", "diamond_pickaxe", "netherite_ingot");
    private static final List<String> OTHERS = List.of("stick", "stone", "glass", "iron_ingot", "dirt", "planks");
    private static final Map<String, Block> STATION_KINDS = Map.of(
        "crafting_table", Blocks.CRAFTING_TABLE, "furnace", Blocks.FURNACE, "blast_furnace", Blocks.BLAST_FURNACE,
        "smoker", Blocks.SMOKER, "stonecutter", Blocks.STONECUTTER, "smithing_table", Blocks.SMITHING_TABLE);
    /** Odds a case has a station of each kind at all, and at most how many it then has. */
    private static final Map<String, Double> STATION_ODDS = ordered("crafting_table", 0.85, "furnace", 0.8,
        "blast_furnace", 0.3, "smoker", 0.25, "stonecutter", 0.4, "smithing_table", 0.3);
    private static final Map<String, Integer> STATION_MOST = ordered("crafting_table", 3, "furnace", 4,
        "blast_furnace", 2, "smoker", 2, "stonecutter", 2, "smithing_table", 1);
    private static final Set<Block> COOKERS = Set.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER);
    private static final List<String> OPS = List.of("withdraw", "take", "add", "steal-fuel", "wall", "open",
        "break", "remove", "kill", "junk");
    /** Most residents, stores, stations and goals a case lays. */
    static final int MOST_PEOPLE = 6;
    static final int MOST_STASHES = 12;
    static final int MOST_STATIONS = 14;
    static final int MOST_GOALS = 14;
    /** Where stores and stations may stand: west of it are the beds, the food and where residents arrive. */
    static final int WEST = -HALF + 9;
    /** Cells (in x or z) every store and station keeps clear round it, so a walled chest walls in nothing else. */
    static final int APART = 4;

    public String name() {
        return "economy";
    }

    public int budget() {
        return BUDGET;
    }

    public int half() {
        return HALF;
    }

    public Map<String, Object> draw(Rng rng) {
        String wood = rng.pick(WOODS);
        // Small, middling and big cases: the big ones are what a colony's economy looks like once it has grown.
        int scale = rng.pick(1, 2, 3);
        List<int[]> spots = new ArrayList<>();
        int stashCount = rng.between(2, 3 + 3 * scale);
        List<Object> stashes = new ArrayList<>();
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < stashCount; i++) {
            int[] at = spot(rng, spots, scale);
            int walled = rng.chance(0.1) ? 1 : 0;
            if (walled == 0) {
                open.add(i);
            }
            stashes.add(Cases.map("x", at[0], "z", at[1], "walled", walled));
        }
        if (open.isEmpty()) {
            stashes.set(0, Cases.map("x", spots.getFirst()[0], "z", spots.getFirst()[1], "walled", 0));
            open.add(0);
        }
        List<Object> stations = new ArrayList<>();
        Set<Block> present = new LinkedHashSet<>();
        STATION_ODDS.forEach((kind, odds) -> {
            if (rng.chance(odds)) {
                for (int n = rng.between(1, Math.min(STATION_MOST.get(kind), scale + 1)); n > 0; n--) {
                    int[] at = spot(rng, spots, scale);
                    stations.add(Cases.map("kind", kind, "x", at[0], "z", at[1]));
                    present.add(STATION_KINDS.get(kind));
                }
            }
        });
        int crew = rng.between(1, 2 * scale);
        EconomyBook book = EconomyBook.of(ServerLifecycleHooks.getCurrentServer().overworld(), universe(wood));
        List<String> makeable = new ArrayList<>();
        for (String name : MADE.keySet()) {
            spec(name, wood).flatMap(spec -> one(book, spec)).filter(item -> book.makeable(item, present))
                .ifPresent(item -> makeable.add(name));
        }
        List<Object> holdings = new ArrayList<>();
        List<Object> goals = new ArrayList<>();
        long coal = 0;
        boolean cooks = false;
        for (int i = rng.between(1, 2 + 4 * scale); i > 0; i--) {
            int at = rng.chance(0.7) ? 0 : 20 * rng.between(1, 150);
            int stash = rng.below(stashCount);
            List<Map<String, Object>> needs = new ArrayList<>();
            for (int n = rng.chance(0.6) ? 1 : rng.between(2, 3); n > 0; n--) {
                String name;
                int most;
                if (rng.chance(0.2)) {
                    name = rng.pick(List.copyOf(HAULED.keySet()));
                    most = HAULED.get(name);
                } else {
                    name = !makeable.isEmpty() && rng.chance(0.85) ? rng.pick(makeable)
                        : rng.pick(List.copyOf(MADE.keySet()));
                    most = MADE.get(name);
                }
                String item = concrete(name, wood);
                if (needs.stream().noneMatch(need -> item.equals(need.get("item")))) {
                    needs.add(Cases.map("item", item, "count", rng.between(1, most)));
                }
            }
            boolean deliver = needs.size() == 1 && rng.chance(0.5);
            goals.add(deliver
                ? Cases.map("kind", "deliver", "stash", stash, "item", needs.getFirst().get("item"),
                    "count", needs.getFirst().get("count"), "at", at)
                : Cases.map("kind", "consume", "stash", stash, "needs", new ArrayList<Object>(needs), "at", at));
            if (rng.chance(0.15)) {
                continue;
            }
            for (Map<String, Object> need : needs) {
                Optional<Item> item = spec(Cases.text(need, "item", ""), wood).flatMap(spec -> one(book, spec));
                if (item.isEmpty()) {
                    continue;
                }
                double slack = 1 + rng.below(4) * 0.1;
                Map<String, Long> raw = new LinkedHashMap<>();
                book.most(item.get(), Cases.num(need, "count", 1), crew).forEach((good, n) ->
                    raw.merge(EconomyBook.name(good), (long) Math.ceil(n * slack), Long::sum));
                cooks |= raw.containsKey("coal") && !book.raw(item.get());
                Long fuel = raw.remove("coal");
                if (fuel != null && !book.raw(item.get())) {
                    coal += fuel;
                } else if (fuel != null) {
                    raw.put("coal", fuel);
                }
                for (Map.Entry<String, Long> good : raw.entrySet()) {
                    List<Integer> into = new ArrayList<>(open);
                    if (deliver && into.size() > 1) {
                        into.remove(Integer.valueOf(stash));
                    }
                    // Stock for one goal may lie in several stores, as it does once a colony has filled a few.
                    long left = good.getValue();
                    for (int parts = rng.chance(0.3) ? 2 : 1; parts > 0 && left > 0; parts--) {
                        long part = parts == 1 ? left : Math.max(1, left / 2);
                        holdings.add(Cases.map("stash", rng.pick(into), "item", good.getKey(),
                            "count", (int) Math.min(part, 256)));
                        left -= part;
                    }
                }
            }
        }
        if (cooks && rng.chance(0.85)) {
            for (long left = coal + rng.below(3); left > 0; left -= 64) {
                holdings.add(Cases.map("stash", rng.pick(open), "item", "coal", "count", (int) Math.min(left, 64)));
            }
        }
        for (int i = rng.between(0, 2 * scale); i > 0; i--) {
            holdings.add(Cases.map("stash", rng.below(stashCount), "item", concrete(rng.pick(RAW), wood),
                "count", rng.between(1, 32)));
        }
        List<Object> ops = new ArrayList<>();
        for (int i = rng.chance(0.5) ? 0 : rng.between(1, 2 + 2 * scale); i > 0; i--) {
            ops.add(Cases.map("at", 20 * rng.between(1, 600), "op", rng.pick(OPS), "goal", rng.below(goals.size()),
                "stash", rng.below(stashCount), "station", rng.below(Math.max(1, stations.size())),
                "item", concrete(rng.chance(0.5) ? rng.pick(RAW) : rng.pick(OTHERS), wood),
                "count", rng.between(1, 32), "who", rng.below(MOST_PEOPLE)));
        }
        return Cases.map("wood", wood, "stashes", stashes, "stations", stations, "holdings", holdings,
            "crew", Cases.map("count", crew), "goals", goals, "ops", ops);
    }

    /**
     * A free cell for a store or a station, {@link #APART} clear of every other: near the beds in a small case,
     * across the whole platform in a big one.
     */
    static int[] spot(Rng rng, List<int[]> taken, int scale) {
        int reach = Math.min(HALF - 4, 12 + 12 * scale);
        int[] at = {WEST, -reach};
        for (int tries = 0; tries < 64; tries++) {
            at = new int[] {rng.between(WEST, WEST + 2 * reach - 8), rng.between(-reach, reach)};
            if (clear(at, taken)) {
                break;
            }
        }
        taken.add(at);
        return at;
    }

    static boolean clear(int[] at, List<int[]> taken) {
        for (int[] other : taken) {
            if (Math.abs(other[0] - at[0]) < APART && Math.abs(other[1] - at[1]) < APART) {
                return false;
            }
        }
        return true;
    }

    public Run open(Plot plot, Map<String, Object> kase) {
        return new Economy(plot, kase).lay();
    }

    /** Every good a case of this wood deals in. */
    static Set<Item> universe(String wood) {
        Set<Item> out = new LinkedHashSet<>();
        for (String name : MADE.keySet()) {
            if (!name.startsWith("#")) {
                out.add(item(concrete(name, wood)));
            }
        }
        for (String name : RAW) {
            out.add(item(concrete(name, wood)));
        }
        for (String name : OTHERS) {
            out.add(item(concrete(name, wood)));
        }
        out.remove(Items.AIR);
        return out;
    }

    static String concrete(String name, String wood) {
        return switch (name) {
            case "planks" -> wood + "_planks";
            case "log" -> wood + "_log";
            default -> name;
        };
    }

    private static final class Goal {
        final UUID root;
        final Map<String, Object> drawn;
        final List<Need> needs;
        final int stash;
        final boolean delivers;
        final Grown grown;
        final Set<Item> wants = new LinkedHashSet<>();
        boolean submitted;
        boolean withdrawn;
        boolean structural;
        boolean possible;
        boolean crowded;
        Ending ending;
        long endedAt = -1;
        String shape = "";
        long movedAt;

        Goal(UUID root, Map<String, Object> drawn, List<Need> needs, int stash, boolean delivers, Grown grown) {
            this.root = root;
            this.drawn = drawn;
            this.needs = needs;
            this.stash = stash;
            this.delivers = delivers;
            this.grown = grown;
        }
    }

    /** Working at a chest with goods in hand, which the core takes from the pack as the node commits. */
    private record Use(NodeSpec spec) implements Node {
        public Outcome commit(ServerLevel level, Worker who) {
            return Outcome.done();
        }
    }

    private static final class Economy implements Run {
        final Plot plot;
        final ServerLevel level;
        final Map<String, Object> kase;
        final EconomyBook book;
        final List<BlockPos> stashes = new ArrayList<>();
        final List<Stances> stashStances = new ArrayList<>();
        final List<Integer> walled = new ArrayList<>();
        final List<BlockPos> stations = new ArrayList<>();
        final List<Block> stationBlocks = new ArrayList<>();
        final Set<Block> stationsEver = new LinkedHashSet<>();
        final List<Goal> goals = new ArrayList<>();
        final Set<Integer> landed = new LinkedHashSet<>();
        final List<String> happened = new ArrayList<>();
        final Map<Item, Long> laid = new LinkedHashMap<>();
        final Map<Item, Long> added = new LinkedHashMap<>();
        final Map<Item, Long> used = new LinkedHashMap<>();
        final List<String> doubts = new ArrayList<>();
        BlockPos foodChest;
        Colony colony;
        Crew crew;
        boolean disturbed;
        boolean spilled;
        int jolts;
        boolean feasible;
        String infeasible = "";
        long allEndedAt = -1;
        long now;
        /** Ticks within which a covered case has time to end every goal, however its cooking queues. */
        long bound;
        /** The last tick anything moved: a goal's work, any goods, a cooker's fire. */
        long movedAt;
        int lastLook;
        /** Ticks some resident spent asleep, and the last tick one was: residents tire and sleep whatever the work. */
        long slept;
        long asleepAt = -1;
        long lastStep;

        Economy(Plot plot, Map<String, Object> kase) {
            this.plot = plot;
            this.level = plot.level();
            this.kase = kase;
            this.book = EconomyBook.of(level, universe(wood()));
        }

        String wood() {
            String wood = Cases.text(kase, "wood", "oak");
            return WOODS.contains(wood) ? wood : "oak";
        }

        Economy lay() {
            List<int[]> taken = new ArrayList<>();
            for (Map<String, Object> one : Cases.maps(kase, "stashes")) {
                int[] cell = {Math.clamp(Cases.num(one, "x", 0), WEST, HALF - 3),
                    Math.clamp(Cases.num(one, "z", 0), -HALF + 3, HALF - 3)};
                if (stashes.size() >= MOST_STASHES || !clear(cell, taken)) {
                    continue;
                }
                taken.add(cell);
                BlockPos at = plot.at(cell[0], 1, cell[1]);
                plot.put(at, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
                stashes.add(at);
                stashStances.add(Stances.of(level, Reach.workableCells(level, at)).orElse(null));
                walled.add(Math.clamp(Cases.num(one, "walled", 0), 0, 1));
            }
            for (Map<String, Object> one : Cases.maps(kase, "holdings")) {
                int index = Cases.num(one, "stash", -1);
                Item item = item(Cases.text(one, "item", "stick"));
                if (index >= 0 && index < stashes.size() && book.universe().contains(item)) {
                    int put = put(stashes.get(index), item, Math.max(1, Cases.num(one, "count", 1)));
                    laid.merge(item, (long) put, Long::sum);
                }
            }
            List<Map<String, Object>> drawnStations = Cases.maps(kase, "stations");
            for (int i = 0; i < drawnStations.size() && stations.size() < MOST_STATIONS; i++) {
                Block block = STATION_KINDS.get(Cases.text(drawnStations.get(i), "kind", ""));
                // Cases from before stations had a place of their own stood them in a row.
                int[] cell = {Math.clamp(Cases.num(drawnStations.get(i), "x", -14 + 5 * i), WEST, HALF - 3),
                    Math.clamp(Cases.num(drawnStations.get(i), "z", 12), -HALF + 3, HALF - 3)};
                if (block == null || !clear(cell, taken)) {
                    continue;
                }
                taken.add(cell);
                BlockPos at = plot.at(cell[0], 1, cell[1]);
                plot.put(at, block.defaultBlockState().hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    ? block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                    : block.defaultBlockState());
                stations.add(at);
                stationBlocks.add(block);
                stationsEver.add(block);
            }
            List<BlockPos> members = new ArrayList<>(stashes);
            members.addAll(stations);
            foodChest = plot.at(-HALF + 3, 1, 0);
            plot.put(foodChest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST));
            put(foodChest, Items.COOKED_BEEF, 64 * MOST_PEOPLE);
            members.add(foodChest);
            int people = Math.clamp(Cases.num(asMap(kase.get("crew")), "count", 1), 1, MOST_PEOPLE);
            FuzzColony.Founded founded = FuzzColony.found(level, plot.at(-HALF + 3, 1, -HALF + 3), members, people,
                plot.at(-HALF + 3, 1, 10));
            colony = founded.colony();
            crew = new Crew(founded.residents());
            FuzzColony.setItems(colony, CoreSettings.CORE, CoreSettings.TOOL_KEY, List.of());
            FuzzColony.setItems(colony, WARES, "fuel", List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("coal"))));
            for (int i = 0; i < stashes.size(); i++) {
                if (walled.get(i) > 0) {
                    wall(stashes.get(i), true);
                }
            }
            List<Map<String, Object>> drawn = Cases.maps(kase, "goals");
            for (int i = 0; i < drawn.size() && goals.size() < MOST_GOALS; i++) {
                goal(i, drawn.get(i)).ifPresent(goals::add);
            }
            reckon(people);
            return this;
        }

        Optional<Goal> goal(int index, Map<String, Object> one) {
            if (stashes.isEmpty()) {
                return Optional.empty();
            }
            int stash = Math.floorMod(Cases.num(one, "stash", 0), stashes.size());
            BlockPos at = stashes.get(stash);
            Stances stances = stashStances.get(stash);
            if (stances == null) {
                return Optional.empty();
            }
            WorkSite site = new WorkSite.AtBlock(WorldPos.of(level, at));
            UUID root = UUID.nameUUIDFromBytes(("folkways-fuzz-economy/" + plot.index() + "/" + index + "/"
                + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
            Goal goal;
            if ("deliver".equals(Cases.text(one, "kind", ""))) {
                Optional<ItemSpec> spec = spec(Cases.text(one, "item", "stick"), wood());
                if (spec.isEmpty()) {
                    return Optional.empty();
                }
                int count = Math.max(1, Cases.num(one, "count", 1));
                Delivery delivery = Delivery.to(root, Haul.DOMAIN, site, stances, spec.get(), count, arrived -> { });
                goal = new Goal(root, one, List.of(new Need(spec.get(), count)), stash, true, Grown.of(delivery));
            } else {
                List<Need> needs = new ArrayList<>();
                for (Map<String, Object> need : Cases.maps(one, "needs")) {
                    spec(Cases.text(need, "item", "stick"), wood()).ifPresent(spec ->
                        needs.add(new Need(spec, Math.max(1, Cases.num(need, "count", 1)))));
                }
                if (needs.isEmpty()) {
                    return Optional.empty();
                }
                Use use = new Use(NodeSpec.of(root, OWNER, site, stances, Workload.Once.of(5)).needs(needs).done());
                goal = new Goal(root, one, needs, stash, false, Grown.of(use));
            }
            for (Need need : goal.needs) {
                one(book, need.spec()).ifPresent(item -> goal.wants.addAll(book.wants(item)));
            }
            return Optional.of(goal);
        }

        /**
         * Whether the stock as laid covers every goal at once: each draws at most {@link EconomyBook#most} of each
         * good from the chests residents can reach, and a delivery cannot take its own goods from the chest they
         * are for. Stock already made into something (a plank, an ingot) would let the colony pick between
         * fetching and making, so a case laid with any is not claimed. Each goal is also asked whether its
         * stations could make it at all, and whether the stock alone could ever cover it.
         */
        void reckon(int hands) {
            Map<Item, Long> usable = new LinkedHashMap<>();
            for (int i = 0; i < stashes.size(); i++) {
                if (walled.get(i) == 0) {
                    contents(stashes.get(i)).forEach((item, n) -> usable.merge(item, n, Long::sum));
                }
            }
            Set<Block> present = new LinkedHashSet<>(stationBlocks);
            Map<Item, Long> pool = new LinkedHashMap<>(usable);
            Map<Item, Long> demand = new LinkedHashMap<>();
            Map<Integer, Long> stacksInto = new LinkedHashMap<>();
            Set<String> excluded = new LinkedHashSet<>();
            for (Goal goal : goals) {
                goal.structural = walled.get(goal.stash) == 0;
                goal.possible = true;
                Map<Item, Long> alone = new LinkedHashMap<>(usable);
                for (Need need : goal.needs) {
                    Optional<Item> item = one(book, need.spec());
                    if (item.isEmpty()) {
                        goal.structural = false;
                        doubts.add("goal " + goals.indexOf(goal) + " asks for " + need.spec().describe()
                            + ", which is not one good of the case's");
                        continue;
                    }
                    goal.structural &= book.makeable(item.get(), present);
                    book.most(item.get(), need.count(), hands).forEach((good, n) -> demand.merge(good, n, Long::sum));
                    if (goal.delivers) {
                        stacksInto.merge(goal.stash, EconomyBook.up(need.count(), item.get().getDefaultMaxStackSize()),
                            Long::sum);
                        if (book.raw(item.get()) && walled.get(goal.stash) == 0) {
                            long there = contents(stashes.get(goal.stash)).getOrDefault(item.get(), 0L);
                            alone.merge(item.get(), -there, Long::sum);
                            if (excluded.add(goal.stash + "/" + EconomyBook.name(item.get()))) {
                                pool.merge(item.get(), -there, Long::sum);
                            }
                        }
                    }
                    goal.possible &= book.possible(item.get(), need.count(), present, alone);
                }
            }
            List<Block> cookers = stationBlocks.stream().filter(COOKERS::contains).toList();
            for (Goal goal : goals) {
                List<Item> cooked = new ArrayList<>();
                for (Need need : goal.needs) {
                    one(book, need.spec()).ifPresent(item -> cooked.addAll(book.loads(item)));
                }
                // Loads of one product may share a cooker; each other product needs one of its own at once.
                List<Item> products = List.copyOf(new LinkedHashSet<>(cooked));
                goal.crowded = !matched(products, 0, cookers, new boolean[cookers.size()]);
            }
            List<String> why = new ArrayList<>();
            usable.forEach((item, n) -> {
                if (!book.raw(item)) {
                    why.add(n + " " + EconomyBook.name(item) + " laid ready-made");
                }
            });
            demand.forEach((item, n) -> {
                if (n > pool.getOrDefault(item, 0L)) {
                    why.add("goals may draw " + n + " " + EconomyBook.name(item) + " of " + pool.getOrDefault(item, 0L));
                }
            });
            stacksInto.forEach((stash, stacks) -> {
                if (free(stashes.get(stash)) < stacks + 2) {
                    why.add("stash " + stash + " has room for " + free(stashes.get(stash)) + " stacks, not " + stacks);
                }
            });
            feasible = why.isEmpty();
            infeasible = String.join("; ", why);
            // Every item cooked one after another at the slowest cooker, each goal's fetching and making done by
            // one resident at a time, and a margin: past this a covered goal still open is no longer slow.
            long cooked = 0;
            for (Goal goal : goals) {
                for (Need need : goal.needs) {
                    Optional<Item> item = one(book, need.spec());
                    if (item.isPresent()) {
                        cooked += book.cooked(item.get(), need.count(), hands);
                    }
                }
            }
            long last = 0;
            for (Goal goal : goals) {
                last = Math.max(last, Cases.num(goal.drawn, "at", 0));
            }
            bound = last + 8_000 + 3 * cooked * COOK_TICKS / 2 + 3_000L * goals.size();
        }

        /** Whether each of these products can have a cooker of its own at once, as one growth of a goal needs. */
        boolean matched(List<Item> cooked, int from, List<Block> cookers, boolean[] taken) {
            if (from == cooked.size()) {
                return true;
            }
            for (int i = 0; i < cookers.size(); i++) {
                Block cooker = cookers.get(i);
                if (!taken[i] && book.ways(cooked.get(from)).stream().anyMatch(way -> way.station() == cooker)) {
                    taken[i] = true;
                    boolean rest = matched(cooked, from + 1, cookers, taken);
                    taken[i] = false;
                    if (rest) {
                        return true;
                    }
                }
            }
            return false;
        }

        public void step(long elapsed, List<Violation> into) {
            now = elapsed;
            for (Goal goal : goals) {
                if (!goal.submitted && Cases.num(goal.drawn, "at", 0) <= elapsed) {
                    goal.submitted = true;
                    goal.movedAt = elapsed;
                    movedAt = elapsed;
                    colony.submit(OWNER, level.dimension(), goal.grown, (node, how) -> ended(goal, how, now));
                }
            }
            List<Map<String, Object>> ops = Cases.maps(kase, "ops");
            for (int i = 0; i < ops.size(); i++) {
                if (Cases.num(ops.get(i), "at", 0) <= elapsed && landed.add(i)) {
                    if (land(ops.get(i), elapsed)) {
                        disturbed = true;
                        movedAt = elapsed;
                    }
                }
            }
            for (Goal goal : goals) {
                if (goal.submitted && goal.ending == null && !goal.withdrawn) {
                    String shape = String.join(",", FuzzLabor.phases(level, colony.id(),
                        FuzzLabor.grown(level, colony.id(), goal.root)));
                    if (!shape.equals(goal.shape)) {
                        goal.shape = shape;
                        goal.movedAt = elapsed;
                        movedAt = elapsed;
                    }
                }
            }
            // Goods left on the ground would despawn and be counted lost; they stay, to be counted where they lie.
            for (Entity entity : plot.entities()) {
                if (entity instanceof ItemEntity stray) {
                    stray.setUnlimitedLifetime();
                }
            }
            // Nobody's work moves while a resident sleeps, nor is anything put away: that is rest, not a stall, so
            // asleep time counts towards neither standing still, nor the time bound, nor the drain.
            if (crew.standing().stream().anyMatch(LivingEntity::isSleeping)) {
                slept += elapsed - lastStep;
                asleepAt = elapsed;
                movedAt = elapsed;
            }
            lastStep = elapsed;
            int look = counted().hashCode() * 31 + stashesNow().hashCode() + (anyLit() ? 1 : 0);
            if (look != lastLook || anyLit()) {
                lastLook = look;
                movedAt = elapsed;
            }
            crew.watch(elapsed, this::rel, into);
            if (allEndedAt < 0 && everyGoalOver()) {
                allEndedAt = elapsed;
            }
        }

        void ended(Goal goal, Ending how, long elapsed) {
            movedAt = elapsed;
            goal.ending = how;
            goal.endedAt = elapsed;
            if (!goal.delivers && how instanceof Ending.Done) {
                for (Need need : goal.needs) {
                    one(book, need.spec()).ifPresent(item -> used.merge(item, need.count(), Long::sum));
                }
            }
            happened.add(elapsed + ": goal " + goals.indexOf(goal) + " ended " + describe(how));
        }

        boolean everyGoalOver() {
            for (Goal goal : goals) {
                if (!goal.submitted || (goal.ending == null && !goal.withdrawn)) {
                    return false;
                }
            }
            return landed.size() == Cases.maps(kase, "ops").size();
        }

        /** Lands one disturbance; answers whether it changed anything. */
        boolean land(Map<String, Object> op, long elapsed) {
            int g = Cases.num(op, "goal", -1);
            Goal goal = g >= 0 && g < goals.size() ? goals.get(g) : null;
            int s = Cases.num(op, "stash", -1);
            BlockPos stash = s >= 0 && s < stashes.size() ? stashes.get(s) : null;
            int t = stations.isEmpty() ? -1 : Math.floorMod(Cases.num(op, "station", 0), stations.size());
            BlockPos station = t < 0 ? null : stations.get(t);
            Item item = item(Cases.text(op, "item", "stick"));
            int count = Math.max(1, Cases.num(op, "count", 1));
            boolean known = book.universe().contains(item);
            switch (Cases.text(op, "op", "")) {
                case "withdraw" -> {
                    if (goal != null && goal.submitted && goal.ending == null && !goal.withdrawn) {
                        goal.withdrawn = true;
                        colony.withdraw(OWNER, goal.root);
                        return said(elapsed + ": goal " + g + " withdrawn");
                    }
                }
                case "take" -> {
                    if (stash != null && known) {
                        int took = take(stash, item, count);
                        added.merge(item, (long) -took, Long::sum);
                        return took > 0 && said(elapsed + ": a player takes " + took + " " + EconomyBook.name(item)
                            + " from stash " + s);
                    }
                }
                case "add" -> {
                    if (stash != null && known) {
                        int put = put(stash, item, count);
                        added.merge(item, (long) put, Long::sum);
                        return put > 0 && said(elapsed + ": a player adds " + put + " " + EconomyBook.name(item)
                            + " to stash " + s);
                    }
                }
                case "steal-fuel" -> {
                    int took = 0;
                    String from = "";
                    if (station != null && level.getBlockEntity(station) instanceof Container cooker
                            && cooker.getContainerSize() == 3) {
                        took = cooker.removeItem(1, count).getCount();
                        cooker.setChanged();
                        from = "the " + EconomyBook.name(stationBlocks.get(t).asItem()) + " " + t;
                    }
                    if (took == 0 && stash != null) {
                        took = take(stash, EconomyBook.FUEL, count);
                        from = "stash " + s;
                    }
                    added.merge(EconomyBook.FUEL, (long) -took, Long::sum);
                    if (took > 0) {
                        jolts++;
                        return said(elapsed + ": a player steals " + took + " coal from " + from);
                    }
                }
                case "junk" -> {
                    if (station != null && level.getBlockEntity(station) instanceof Container cooker
                            && cooker.getContainerSize() == 3 && cooker.getItem(0).isEmpty()) {
                        Item junk = item == Items.COBBLESTONE || item == Items.DIRT ? item : Items.DIRT;
                        int n = Math.min(count, 16);
                        cooker.setItem(0, new ItemStack(junk, n));
                        cooker.setChanged();
                        added.merge(junk, (long) n, Long::sum);
                        jolts++;
                        return said(elapsed + ": a player puts " + n + " " + EconomyBook.name(junk) + " in the "
                            + EconomyBook.name(stationBlocks.get(t).asItem()) + " " + t);
                    }
                }
                case "break", "remove" -> {
                    if (station != null && !level.getBlockState(station).isAir()) {
                        boolean breaks = Cases.text(op, "op", "").equals("break");
                        if (breaks) {
                            spilled = true;
                        } else {
                            contents(station).forEach((good, n) -> added.merge(good, -n, Long::sum));
                            if (level.getBlockEntity(station) instanceof Container box) {
                                box.clearContent();
                            }
                        }
                        level.destroyBlock(station, false);
                        jolts++;
                        return said(elapsed + ": a player " + (breaks ? "breaks" : "takes away") + " the "
                            + EconomyBook.name(stationBlocks.get(t).asItem()) + " " + t);
                    }
                }
                case "kill" -> {
                    if (crew.kill(Cases.num(op, "who", 0)) != null) {
                        return said(elapsed + ": a resident is killed");
                    }
                }
                case "open", "wall" -> {
                    if (stash != null) {
                        boolean closing = Cases.text(op, "op", "").equals("wall");
                        if ((walled.get(s) > 0) != closing) {
                            wall(stash, closing);
                            walled.set(s, closing ? 1 : 0);
                            return said(elapsed + ": stash " + s + (closing ? " walled in" : " opened"));
                        }
                    }
                }
                default -> { }
            }
            return false;
        }

        boolean said(String what) {
            happened.add(what);
            return true;
        }

        /**
         * Stone in the ring round a chest, corners too (a resident reaches a chest from any cell touching it), two
         * high, and over it, so no one can stand where they could open it; or none.
         */
        void wall(BlockPos chest, boolean up) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int y = 0; y <= 1 && (dx != 0 || dz != 0); y++) {
                        BlockPos at = chest.offset(dx, y, dz);
                        if (up ? level.getBlockState(at).isAir() : level.getBlockState(at).is(Blocks.STONE)) {
                            plot.put(at, (up ? Blocks.STONE : Blocks.AIR).defaultBlockState());
                        }
                    }
                }
            }
            plot.put(chest.above(), (up ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        }

        public boolean settled(long elapsed) {
            if (goals.isEmpty()) {
                return true;
            }
            if (allEndedAt < 0) {
                // Nothing has moved for so long that nothing will: judged as it stands.
                return stood(elapsed);
            }
            return elapsed - Math.max(allEndedAt, asleepAt) >= DRAIN || (elapsed - allEndedAt >= 100 && goodsCarried().isEmpty()
                && looseItems().isEmpty() && cookedLeft().isEmpty());
        }

        boolean stood(long elapsed) {
            return elapsed - movedAt >= STILL && landed.size() == Cases.maps(kase, "ops").size()
                && goals.stream().allMatch(goal -> goal.submitted);
        }

        boolean anyLit() {
            for (BlockPos station : stations) {
                if (level.getBlockState(station).getOptionalValue(BlockStateProperties.LIT).orElse(false)) {
                    return true;
                }
            }
            return false;
        }

        public List<Violation> judge(boolean settled, long elapsed) {
            List<Violation> broke = new ArrayList<>();
            if (!doubts.isEmpty()) {
                broke.add(new Violation("economy.oracle", String.join("; ", doubts)));
            }
            boolean claimed = feasible && !disturbed;
            for (Goal goal : goals) {
                if (!goal.submitted || goal.withdrawn) {
                    continue;
                }
                boolean covered = claimed && goal.structural;
                // One growth must speak for a cooker per load at once, and a cooker takes one job: a goal whose
                // making calls for more loads than there are cookers never grows. Kept apart from the rest.
                String crowd = goal.crowded ? ".cookers-short" : "";
                String name = "goal " + goals.indexOf(goal) + " (" + (goal.delivers ? "deliver " : "use ")
                    + goal.needs.stream().map(need -> need.count() + " " + need.spec().describe()).toList()
                    + " at stash " + goal.stash + ")";
                if (goal.ending == null) {
                    Set<UUID> grown = FuzzLabor.grown(level, colony.id(), goal.root);
                    String plan = "; its work: " + FuzzLabor.phases(level, colony.id(), grown) + "; the plan says "
                        + FuzzLabor.told(level, colony.id()) + "; the cookers hold " + cookersNow();
                    boolean due = stood(elapsed) || elapsed - slept >= bound;
                    if (covered && due) {
                        // Kept apart by the excuse the plan gives, so one way of falling short hides no other.
                        String excuse = FuzzLabor.why(level, colony.id(), grown, goal.wants).stream()
                            .map(LiveEconomy::excuse).findFirst().orElse("none");
                        // One kind for the known shortage of cookers, whatever the plan says: it hides nothing else.
                        String kind = crowd.isEmpty() ? "economy.unfinished:" + excuse : "economy.unfinished" + crowd;
                        broke.add(new Violation(kind, name + " is still open after " + elapsed + " ticks ("
                            + (stood(elapsed) ? "nothing has moved since " + movedAt : "past its time bound of "
                            + bound + " awake ticks") + ") though the stock covered every goal and nothing disturbed the case"
                            + plan));
                    } else if (!crew.standing().isEmpty() && elapsed - Math.max(goal.movedAt, asleepAt) >= STUCK
                            && FuzzLabor.why(level, colony.id(), grown, goal.wants).isEmpty()) {
                        // With nobody left standing there is no labor to plan, and no plan to give a reason.
                        broke.add(new Violation("economy.silent", name + " is still open after " + elapsed
                            + " ticks, its work has not moved since " + goal.movedAt + ", and the colony's plan"
                            + " names no reason for it (" + (infeasible.isEmpty() ? "covered" : infeasible) + ")"
                            + plan));
                    }
                } else if (covered && !(goal.ending instanceof Ending.Done)) {
                    broke.add(new Violation("economy.failed" + crowd, name + " ended " + describe(goal.ending) + " at "
                        + goal.endedAt + " though the stock covered every goal and nothing disturbed the case"));
                } else if (!disturbed && goal.ending instanceof Ending.Done && !goal.possible) {
                    broke.add(new Violation("economy.beyond-stock", name + " ended done at " + goal.endedAt
                        + ", though its stations could not make it from all the stock it could reach; the stashes"
                        + " end with " + stashesNow()));
                }
            }
            Map<Item, Long> diff = counted();
            Set<Item> items = new LinkedHashSet<>(laid.keySet());
            items.addAll(added.keySet());
            items.addAll(used.keySet());
            for (Item item : items) {
                diff.merge(item, -(laid.getOrDefault(item, 0L) + added.getOrDefault(item, 0L)
                    - used.getOrDefault(item, 0L)), Long::sum);
            }
            int cookers = (int) stationBlocks.stream().filter(COOKERS::contains).count();
            EconomyBook.Balance balance = book.balance(diff, stationsEver, cookers + jolts);
            String ledger = " (found - (laid + added - used), unwound by " + balance.cooked() + " cooking runs and "
                + balance.burned() + " coal burned; laid " + names(laid) + ", added " + names(added) + ", used "
                + names(used) + ")";
            if (!balance.made().isEmpty()) {
                broke.add(new Violation("economy.duplicated", "goods no recipe run explains: " + balance.made() + ledger));
            }
            if (!balance.lost().isEmpty() && !crew.anyKilled()) {
                broke.add(new Violation("economy.lost", "goods vanished: " + balance.lost() + ledger));
            }
            if (settled && allEndedAt >= 0 && (elapsed - Math.max(allEndedAt, asleepAt) >= DRAIN
                    || goodsCarried().isEmpty() && looseItems().isEmpty() && cookedLeft().isEmpty())) {
                Map<Item, Long> carried = goodsCarried();
                if (!carried.isEmpty()) {
                    broke.add(new Violation("economy.pack-stranded", "every goal ended " + (elapsed - allEndedAt)
                        + " ticks ago, yet residents still carry " + names(carried)));
                }
                List<String> loose = looseItems();
                if (!crew.anyKilled() && !spilled && !loose.isEmpty()) {
                    broke.add(new Violation("economy.loose-items", "items lie loose once all is over: " + loose));
                }
                List<String> left = cookedLeft();
                if (!crew.standing().isEmpty() && !left.isEmpty()) {
                    broke.add(new Violation("economy.cooked-stranded", "every goal ended " + (elapsed - allEndedAt)
                        + " ticks ago, yet finished goods sit in a cooker nobody is clearing: " + left));
                }
            }
            return broke;
        }

        /**
         * Goods in the chests, the stations, the packs and on the ground. Food is no good of the case's: residents
         * eat it and put what they leave wherever there is room, so it is not counted anywhere.
         */
        Map<Item, Long> counted() {
            Map<Item, Long> out = new LinkedHashMap<>();
            List<BlockPos> boxes = new ArrayList<>(stashes);
            boxes.add(foodChest);
            boxes.addAll(stations);
            for (BlockPos box : boxes) {
                contents(box).forEach((item, count) -> out.merge(item, count, Long::sum));
            }
            crew.carried().forEach((item, count) -> out.merge(item, count, Long::sum));
            for (Entity entity : plot.entities()) {
                if (entity instanceof ItemEntity stray) {
                    out.merge(stray.getItem().getItem(), (long) stray.getItem().getCount(), Long::sum);
                }
            }
            out.remove(Items.COOKED_BEEF);
            return out;
        }

        String stashesNow() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < stashes.size(); i++) {
                out.add(i + (walled.get(i) > 0 ? " (walled)" : "") + ": " + names(contents(stashes.get(i))));
            }
            return String.join(", ", out);
        }

        /** Each cooker's input, fuel and output, and whether it burns. */
        String cookersNow() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < stations.size(); i++) {
                if (level.getBlockEntity(stations.get(i)) instanceof Container cooker && cooker.getContainerSize() == 3) {
                    out.add(EconomyBook.name(stationBlocks.get(i).asItem()) + " " + i + " ["
                        + cooker.getItem(0).getCount() + " " + EconomyBook.name(cooker.getItem(0).getItem()) + ", "
                        + cooker.getItem(1).getCount() + " " + EconomyBook.name(cooker.getItem(1).getItem()) + ", "
                        + cooker.getItem(2).getCount() + " " + EconomyBook.name(cooker.getItem(2).getItem())
                        + (level.getBlockState(stations.get(i)).getOptionalValue(BlockStateProperties.LIT).orElse(false)
                        ? ", lit]" : "]"));
                }
            }
            return out.toString();
        }

        Map<Item, Long> goodsCarried() {
            Map<Item, Long> out = new LinkedHashMap<>(crew.carried());
            out.remove(Items.COOKED_BEEF);
            return out;
        }

        List<String> looseItems() {
            List<String> loose = new ArrayList<>();
            for (Entity entity : plot.entities()) {
                if (entity instanceof ItemEntity stray) {
                    loose.add(stray.getItem().getCount() + " " + EconomyBook.name(stray.getItem().getItem()) + " at "
                        + rel(stray.blockPosition()));
                }
            }
            return loose;
        }

        /** Finished goods in a cooker that has nothing more to cook: what the colony's clearing chore is for. */
        List<String> cookedLeft() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < stations.size(); i++) {
                if (level.getBlockEntity(stations.get(i)) instanceof Container cooker && cooker.getContainerSize() == 3
                        && !cooker.getItem(2).isEmpty() && cooker.getItem(0).isEmpty()
                        && !level.getBlockState(stations.get(i)).getOptionalValue(BlockStateProperties.LIT).orElse(false)) {
                    out.add(cooker.getItem(2).getCount() + " " + EconomyBook.name(cooker.getItem(2).getItem()) + " in the "
                        + EconomyBook.name(stationBlocks.get(i).asItem()) + " " + i);
                }
            }
            return out;
        }

        Map<Item, Long> contents(BlockPos at) {
            Map<Item, Long> out = new LinkedHashMap<>();
            if (at != null && level.getBlockEntity(at) instanceof Container box) {
                for (int slot = 0; slot < box.getContainerSize(); slot++) {
                    ItemStack stack = box.getItem(slot);
                    if (!stack.isEmpty()) {
                        out.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                    }
                }
            }
            return out;
        }

        int free(BlockPos at) {
            int free = 0;
            if (level.getBlockEntity(at) instanceof Container box) {
                for (int slot = 0; slot < box.getContainerSize(); slot++) {
                    free += box.getItem(slot).isEmpty() ? 1 : 0;
                }
            }
            return free;
        }

        int put(BlockPos at, Item item, int count) {
            if (!(level.getBlockEntity(at) instanceof Container box)) {
                return 0;
            }
            int left = count;
            for (int slot = 0; slot < box.getContainerSize() && left > 0; slot++) {
                ItemStack there = box.getItem(slot);
                if (there.isEmpty()) {
                    int now = Math.min(left, item.getDefaultMaxStackSize());
                    box.setItem(slot, new ItemStack(item, now));
                    left -= now;
                } else if (there.is(item) && there.getCount() < there.getMaxStackSize()) {
                    int now = Math.min(left, there.getMaxStackSize() - there.getCount());
                    there.grow(now);
                    left -= now;
                }
            }
            box.setChanged();
            return count - left;
        }

        int take(BlockPos at, Item item, int count) {
            if (!(level.getBlockEntity(at) instanceof Container box)) {
                return 0;
            }
            int left = count;
            for (int slot = 0; slot < box.getContainerSize() && left > 0; slot++) {
                if (box.getItem(slot).is(item)) {
                    left -= box.removeItem(slot, left).getCount();
                }
            }
            box.setChanged();
            return count - left;
        }

        public Map<String, Object> summary() {
            return Cases.map("goals", goals.size(), "ended", goals.stream().filter(goal -> goal.ending != null).count(),
                "done", goals.stream().filter(goal -> goal.ending instanceof Ending.Done).count(),
                "claimed", feasible && !disturbed, "unclaimed", infeasible, "bound", bound,
                "stashes", stashes.size(), "stations", stations.size(), "crew", crew.size(),
                "happened", List.copyOf(happened));
        }

        public void close() {
            if (colony != null) {
                for (Goal goal : goals) {
                    if (goal.submitted && goal.ending == null && !goal.withdrawn) {
                        colony.withdraw(OWNER, goal.root);
                    }
                }
                FuzzColony.raze(level, colony);
            }
        }

        String rel(BlockPos at) {
            return "(" + (at.getX() - plot.origin().getX()) + "," + (at.getY() - plot.origin().getY()) + ","
                + (at.getZ() - plot.origin().getZ()) + ")";
        }
    }

    static Optional<ItemSpec> spec(String written, String wood) {
        String name = concrete(written, wood);
        if (name.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(name.substring(1));
            return tag == null ? Optional.empty() : Optional.of(ItemSpec.of(TagKey.create(Registries.ITEM, tag)));
        }
        Item item = item(name);
        return item == Items.AIR ? Optional.empty() : Optional.of(ItemSpec.of(BuiltInRegistries.ITEM.getKey(item)));
    }

    /** The one good of the case's a spec takes in, if exactly one. */
    static Optional<Item> one(EconomyBook book, ItemSpec spec) {
        List<Item> members = Goods.members(spec).stream().filter(book.universe()::contains).toList();
        return members.size() == 1 ? Optional.of(members.getFirst()) : Optional.empty();
    }

    static Item item(String written) {
        ResourceLocation id = ResourceLocation.tryParse(written.contains(":") ? written : "minecraft:" + written);
        return id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
    }

    static String names(Map<Item, Long> goods) {
        List<String> out = new ArrayList<>();
        goods.forEach((item, n) -> {
            if (n != 0) {
                out.add(n + " " + EconomyBook.name(item));
            }
        });
        return out.toString();
    }

    /** The kind of reason a plan gave, as {@link FuzzLabor#why} words it. */
    static String excuse(String why) {
        int colon = why.lastIndexOf(": ");
        String tail = colon < 0 ? why : why.substring(colon + 2);
        return tail.replaceAll("\\[.*", "").replaceAll("[^A-Za-z_]+", "-").replaceAll("^-|-$", "");
    }

    static String describe(Ending how) {
        return switch (how) {
            case Ending.Failed failed -> "failed: " + failed.why().translationKey();
            default -> how.getClass().getSimpleName().toLowerCase();
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> ordered(Object... pairs) {
        Map<String, V> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put((String) pairs[i], (V) pairs[i + 1]);
        }
        return out;
    }
}
