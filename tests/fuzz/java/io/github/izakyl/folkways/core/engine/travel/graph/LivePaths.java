package io.github.izakyl.folkways.core.engine.travel.graph;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.FuzzLabor;
import io.github.izakyl.folkways.core.engine.travel.TravelBudget;
import io.github.izakyl.folkways.core.engine.travel.graph.Lanes.Lane;
import io.github.izakyl.folkways.core.engine.travel.graph.Lanes.Pocket;
import io.github.izakyl.folkways.core.engine.travel.graph.Lanes.Slot;
import io.github.izakyl.folkways.core.engine.travel.graph.Lanes.Step;
import io.github.izakyl.folkways.core.engine.travel.graph.Lanes.Yard;
import io.github.izakyl.folkways.fuzz.Cases;
import io.github.izakyl.folkways.fuzz.Crew;
import io.github.izakyl.folkways.fuzz.LiveTarget;
import io.github.izakyl.folkways.fuzz.Plot;
import io.github.izakyl.folkways.fuzz.Rng;
import io.github.izakyl.folkways.fuzz.Violation;
import io.github.izakyl.folkways.plugins.person.FuzzColony;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.rail.FuzzRail;
import io.github.izakyl.folkways.plugins.rail.domain.RailContent;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.slf4j.Logger;

/**
 * Getting about at scale, judged by construction. A case lays a colony's home yard (the hub, with its beds and its
 * chest) on a plot up to 160 across, then grows lanes out of it, each a chain of pieces a resident's body is known
 * to get through: flat runs on any floor and under low ceilings, stair flights and slab steps, ladders and vines up
 * and down, wooden doors and gates shut or open, railed bridges over pits, walkways high in the air, tunnels deep in
 * the ground (see {@link Lanes}). Lanes cross over and under each other and meet where they want the same blocks, so
 * a big case is a many-floored warren. Some cases lay a Create line from a platform by the hub to a walled yard by a
 * far platform, with a conductor, and grow lanes out of that yard too, reached only by riding. Some lay pockets
 * nobody can get into: a sealed cell, a pillar too high to climb, an island past a moat. Scenery fills the rest
 * (hills, walls, rooms, pits, pools of water and lava) but only where no lane or pocket has reserved a cell.
 * Residents, up to a dozen, are asked at their ticks to go and stand somewhere on a lane, or in a pocket, many at
 * once; meanwhile disturbances dig, fill, wall, flood, take ladders down, swap doors for iron ones, shut and open
 * doors, mend what an earlier one did, and break the line.
 *
 * <p>No flood or search decides what is owed: a lane is whole while each of its own hard cells (and the hub's, and
 * for a yard lane the line's) still holds the block it was laid with, which is read straight off the world, so a
 * disturbance cuts exactly the lanes whose cells it touched and a mend that puts them back restores them. The case
 * holds:
 * <ul>
 *   <li>an ask on a lane that is whole, while every resident stands on whole lanes or the hub (or rides), and for a
 *       yard lane while the line serves the colony and is driven, is done within a bound got from the lane's own
 *       length, climbs, doors and ride, and the asks queued ahead of it; it does not fail. The bound's clock stops
 *       while the plan says the way is still being worked out (WAY_PENDING): that work is budgeted in wall time, not
 *       ticks, so how many ticks it takes says how fast the machine is, not whether the colony copes;</li>
 *   <li>an ask on a lane that was cut may fail or wait, but if it is still waiting at the end, long after the cut,
 *       the colony's plan names a reason;</li>
 *   <li>an ask into a pocket that is still sealed is never done, and within a bound it fails or the plan names a
 *       reason it waits; it does not hang silently;</li>
 *   <li>no resident dies, or falls off the plot, getting about (lava, a long drop, drowning) or of anything the case
 *       did not do near it.</li>
 * </ul>
 * An ask on a yard lane that breaks one of these is told apart ({@code -riding}), since only the train is sure to
 * reach that yard.
 *
 * <p>A case is lanes by seed and count (and, to pin a lane down, a list of piece names), so the shrinker can only
 * drop whole lanes, pockets, scenery, asks and disturbances, or shorten a lane, and every case it makes still lays
 * whole lanes.
 */
public final class LivePaths implements LiveTarget {

    static final int BUDGET = 24_000;
    static final int WIDEST = 160;
    /** Ticks an owed ask has on top of what its walk costs: planning, setting out, waiting its turn. */
    static final int SLACK = 900;
    /** Ticks a pocket ask has to be refused, or to be named a reason. */
    static final int REFUSE = 2_400;
    /** Ticks a cut lane must have stayed cut before an ask still waiting on it at the end must have a reason. */
    static final int STILL = 1_200;
    /** Extra ticks an ask has when its lane became whole only after it was asked: the colony must notice. */
    static final int RELEARN = 2_400;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_fuzz", "paths");

    private static final List<String> SCENERY = List.of("hill", "hill", "block", "block", "pit", "pit", "pool",
        "lava", "leaves", "fence", "wall", "wall", "stairs", "slab", "trapdoor", "door", "carpet", "tower", "room");
    private static final List<String> GROUND_EDITS = List.of("dig", "dig", "dig", "fill", "fill", "wall", "unladder",
        "iron", "shut", "shut", "unshut", "flood");
    private static final List<String> RAIL_EDITS = List.of("track", "scrap", "disassemble", "reschedule",
        "reschedule", "conductor", "platform", "release");
    private static final List<String> POCKETS = List.of("sealed", "pillar", "island", "cage");

    public String name() {
        return "paths";
    }

    public int budget() {
        return BUDGET;
    }

    public int half() {
        return WIDEST / 2 + 4;
    }

    public int half(Map<String, Object> kase) {
        return Math.max(width(kase, "w"), width(kase, "d")) / 2 + 4;
    }

    static int width(Map<String, Object> kase, String key) {
        return Math.clamp(Cases.num(kase, key, 24), 16, WIDEST);
    }

    public Map<String, Object> draw(Rng rng) {
        int size = rng.below(10);
        boolean small = size < 4;
        boolean large = size >= 8;
        int lo = small ? 20 : large ? 100 : 48;
        int hi = small ? 48 : large ? WIDEST : 100;
        int w = rng.between(lo, hi);
        int d = rng.between(lo, hi);
        List<Object> rail = new ArrayList<>();
        Line line = null;
        if (!small && rng.chance(0.35)) {
            d = Math.max(d, rng.between(64, Math.max(64, hi)));
            w = Math.max(w, 40);
            Map<String, Object> spec = Cases.map("x", rng.between(5, Math.max(5, Math.min(w - 30, 40))),
                "z", rng.between(2, 8), "length", rng.between(16, 56), "seats", rng.between(1, 3),
                "dwellA", rng.between(5, 20), "dwellB", rng.between(5, 15));
            line = Line.fit(spec, w, d);
            if (line != null) {
                rail.add(spec);
            }
        }
        int people = small ? rng.between(1, 3) : large ? rng.between(4, line != null ? 6 : 12) : rng.between(2, 6);
        List<Object> routes = new ArrayList<>();
        int lanes = small ? rng.between(2, 6) : large ? rng.between(12, 36) : rng.between(5, 16);
        for (int i = 0; i < lanes; i++) {
            routes.add(Cases.map("id", i, "seed", rng.below(1 << 30),
                "from", line != null && rng.chance(0.3) ? "yard" : "hub",
                "count", small ? rng.between(3, 12) : large ? rng.between(10, 40) : rng.between(6, 24)));
        }
        List<Object> pockets = new ArrayList<>();
        for (int i = small ? rng.below(2) : large ? rng.between(1, 4) : rng.below(4); i > 0; i--) {
            pockets.add(Cases.map("id", i, "kind", rng.pick(POCKETS), "x", rng.between(4, w - 5),
                "z", rng.between(4, d - 5), "h", rng.between(3, 7)));
        }
        List<Object> scenery = new ArrayList<>();
        for (int i = rng.between(0, Math.min(160, w * d / 40)); i > 0; i--) {
            scenery.add(Cases.map("kind", rng.pick(SCENERY), "x", rng.below(w), "z", rng.below(d),
                "y", rng.between(1, 8), "w", rng.between(1, 6), "d", rng.between(1, 6), "h", rng.between(1, 5),
                "facing", rng.below(4), "open", rng.chance(0.4), "top", rng.chance(0.4)));
        }
        List<Object> asks = new ArrayList<>();
        int asked = small ? rng.between(1, 6) : large ? rng.between(8, 36) : rng.between(3, 14);
        for (int i = 0; i < asked; i++) {
            int at = rng.chance(0.4) ? 0 : 20 * rng.between(1, 150);
            if (!pockets.isEmpty() && rng.chance(0.15)) {
                Map<?, ?> pocket = (Map<?, ?>) rng.pick(pockets);
                asks.add(Cases.map("pocket", pocket.get("id"), "at", at));
            } else {
                asks.add(Cases.map("route", rng.below(lanes), "along", rng.chance(0.7) ? 1000 : rng.below(1001),
                    "at", at));
            }
        }
        List<Object> edits = new ArrayList<>();
        if (rng.chance(0.6)) {
            int count = rng.between(1, small ? 4 : 8);
            for (int i = 0; i < count; i++) {
                Map<String, Object> edit;
                if (i > 0 && rng.chance(0.2)) {
                    edit = Cases.map("kind", "mend", "of", rng.below(i));
                } else if (line != null && rng.chance(0.3)) {
                    edit = Cases.map("kind", rng.pick(RAIL_EDITS), "part", rng.below(100), "mode", rng.below(3),
                        "which", rng.below(2));
                } else {
                    edit = Cases.map("kind", rng.pick(GROUND_EDITS), "w", rng.between(1, 4), "d", rng.between(1, 4),
                        "h", rng.between(1, 3));
                    if (rng.chance(0.75)) {
                        edit.put("route", rng.below(lanes));
                        edit.put("along", rng.below(1001));
                    } else {
                        edit.put("x", rng.below(w));
                        edit.put("z", rng.below(d));
                        edit.put("y", rng.between(0, 6));
                    }
                }
                edit.put("id", i);
                edit.put("at", 20 * rng.between(5, 250));
                edits.add(edit);
            }
        }
        Map<String, Object> hub = Cases.map("x", rng.between(2, Math.max(2, w - 12)),
            "z", rng.between(2, Math.max(2, d - 30)));
        return Cases.map("w", w, "d", d, "hub", hub,
            "crew", Cases.map("count", people), "rail", rail, "routes", routes, "pockets", pockets,
            "scenery", scenery, "asks", asks, "edits", edits);
    }

    public Run open(Plot plot, Map<String, Object> kase) {
        return new Paths(plot, kase).lay();
    }

    private record Stand(NodeSpec spec) implements Node {
        public Outcome commit(ServerLevel level, Worker who) {
            return Outcome.done();
        }
    }

    /** A line's place on the field, in field cells: its track column, the car's origin and both stations' rows. */
    private record Line(int x, int zn, int length, int seats, int carEnd, int z0, int zA, int zB, int south) {

        static Line fit(Map<String, Object> spec, int w, int d) {
            int seats = Math.clamp(Cases.num(spec, "seats", 1), 1, 3);
            int carEnd = FuzzRail.carEnd(seats);
            int zn = Math.max(2, Cases.num(spec, "z", 2));
            int length = Math.min(Cases.num(spec, "length", 24), d - 14 - carEnd - zn);
            int x = Math.clamp(Cases.num(spec, "x", 6), 5, Math.max(5, w - 30));
            if (length < 10 || x + 26 > w - 1) {
                return null;
            }
            int z0 = zn + length + 8;
            return new Line(x, zn, length, seats, carEnd, z0, z0 + carEnd + 1, z0 - length, z0 + carEnd + 3);
        }
    }

    enum Owed { WAIT, OWED, LAPSED, DONE }

    private static final class Ask {
        final int index;
        final Lane lane;
        final int step;
        final Pocket pocket;
        final BlockPos to;
        final long at;
        final int cost;
        UUID root;
        Ending ending;
        long endedAt = -1;
        Owed owed = Owed.WAIT;
        long since = -1;
        long deadline = -1;
        long nominal = -1;
        long cutAt = -1;
        String cut = "";
        boolean cutOwn;
        boolean judged;
        long pending;
        long pendingRun;
        long longestPending;
        List<String> where = List.of();

        Ask(int index, Lane lane, int step, Pocket pocket, BlockPos to, long at, int cost) {
            this.index = index;
            this.lane = lane;
            this.step = step;
            this.pocket = pocket;
            this.to = to;
            this.at = at;
            this.cost = cost;
        }
    }

    /** A disturbance that landed: what it changed, as it was before, and the box of cells round it. */
    private record Landed(int id, String kind, Map<BlockPos, BlockState> before, BlockPos min, BlockPos max,
                          long at) {
    }

    private static final class Paths implements Run {
        final Plot plot;
        final ServerLevel level;
        final Map<String, Object> kase;
        final int w;
        final int d;
        final BlockPos field;
        final Lanes lanes;
        final List<Ask> asks = new ArrayList<>();
        final List<Map<String, Object>> edits;
        final Set<Integer> edited = new LinkedHashSet<>();
        final Map<Integer, Landed> landed = new LinkedHashMap<>();
        final List<String> happened = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final Set<UUID> rode = new LinkedHashSet<>();
        final List<BlockPos> lava = new ArrayList<>();
        long[] hardKeys = new long[0];
        BlockState[] hardStates = new BlockState[0];
        LongOpenHashSet broken = new LongOpenHashSet();
        long lastFullCheck = -1_000;
        long lastEdit = -1;
        boolean flooded;
        Colony colony;
        Crew crew;
        Line line;
        FuzzRail rail;
        ResidentEntity conductor;
        int walkers;
        int rideCost;
        int budget = BUDGET;
        long now;
        int owedCount;
        int reported;
        long lastStep;
        boolean wasDriven;
        boolean drivenOnce;
        final Map<String, Integer> lapses = new LinkedHashMap<>();
        int doneOwed;

        Paths(Plot plot, Map<String, Object> kase) {
            this.plot = plot;
            this.level = plot.level();
            this.kase = kase;
            this.w = width(kase, "w");
            this.d = width(kase, "d");
            this.field = plot.at(-plot.half() + 2, 0, -plot.half() + 2);
            this.lanes = new Lanes(w, d);
            this.edits = Cases.maps(kase, "edits");
        }

        // ---- laying -------------------------------------------------------------------------------------------

        Paths lay() {
            GameRules rules = level.getGameRules();
            rules.getRule(GameRules.RULE_DOFIRETICK).set(false, level.getServer());
            rules.getRule(GameRules.RULE_DO_VINES_SPREAD).set(false, level.getServer());
            int people = Math.clamp(Cases.num(kase.get("crew") instanceof Map<?, ?> crewed ? crewed.get("count") : null,
                2), 1, 12);
            List<Map<String, Object>> railed = Cases.maps(kase, "rail");
            if (!railed.isEmpty()) {
                line = Line.fit(railed.getFirst(), w, d);
            }
            walkers = line != null ? Math.min(people, 5) : people;
            int beds = walkers + (line != null ? 1 : 0);
            Yard hubAt;
            Yard yardAt = null;
            List<int[]> needed = new ArrayList<>();
            if (line != null) {
                Map<String, Object> spec = railed.getFirst();
                rail = new FuzzRail(level, field.offset(line.x, 1, line.z0), line.length, line.seats,
                    "fuzz" + plot.index() + "a", "fuzz" + plot.index() + "b",
                    Math.clamp(Cases.num(spec, "dwellA", 10), 1, 60), Math.clamp(Cases.num(spec, "dwellB", 10), 1, 60));
                int[] berthA = shift(rail.berth(0));
                int[] berthB = shift(rail.berth(1));
                int north = rail.north() - field.getZ();
                int south = rail.south() - field.getZ();
                for (int z = north; z <= south; z++) {
                    needed.add(new int[] {line.x, 1, z});
                }
                for (int which = 0; which < 2; which++) {
                    BlockPos station = rail.station(which).subtract(field);
                    needed.add(new int[] {station.getX(), station.getY(), station.getZ()});
                    int[] span = which == 0 ? berthA : berthB;
                    for (int z = span[0]; z <= span[1]; z++) {
                        needed.add(new int[] {line.x + 3, 1, z});
                        needed.add(new int[] {line.x + 3, 2, z});
                        needed.add(new int[] {line.x + 4, 1, z});
                    }
                }
                lanes.line = lanes.line(line.x, north, south, berthA, berthB,
                    new int[] {line.z0 + 3, line.z0 + line.carEnd}, needed);
                hubAt = new Yard(line.x + 4, berthA[0] - 1, line.x + 4 + 9, berthA[1] + 1, true);
                yardAt = new Yard(line.x + 4, berthB[0] - 1, line.x + 4 + 9, berthB[1] + 1, true);
                int travel = (rail.south() - rail.north()) * 4;
                rideCost = 2 * (rail.dwell()[0] + rail.dwell()[1]) * 20 + 2 * travel + 600;
            } else {
                Map<?, ?> hub = (Map<?, ?>) kase.getOrDefault("hub", Map.of());
                int depth = Math.max(7, 2 * beds + 3);
                int x0 = Math.clamp(Cases.num(hub.get("x"), 2), 1, Math.max(1, w - 10));
                int z0 = Math.clamp(Cases.num(hub.get("z"), 2), 1, Math.max(1, d - depth - 1));
                hubAt = new Yard(x0, z0, x0 + 7, Math.min(d - 2, z0 + depth - 1), false);
            }
            if (2 * beds + 1 > hubAt.z1() - hubAt.z0() - 1) {
                beds = Math.max(1, (hubAt.z1() - hubAt.z0() - 2) / 2);
                walkers = Math.max(1, beds - (line != null ? 1 : 0));
                notes.add("the hub holds " + beds + " beds");
            }
            List<long[]> holes = new ArrayList<>();
            int bedX = hubAt.x1() - 2;
            for (int i = 0; i < beds; i++) {
                int z = hubAt.z0() + 1 + 2 * i;
                holes.add(new long[] {bedX, z});
                holes.add(new long[] {bedX + 1, z});
            }
            int chestX = hubAt.x0() + 1;
            int chestZ = hubAt.z1() - 1;
            holes.add(new long[] {chestX, chestZ});
            lanes.hubYard = hubAt;
            lanes.hub = lanes.yard(-1, "hub", hubAt, holes);
            if (yardAt != null) {
                lanes.farYard = yardAt;
                lanes.yard = lanes.yard(-2, "yard", yardAt, List.of());
            }
            for (Map<String, Object> one : Cases.maps(kase, "pockets")) {
                int id = Cases.num(one, "id", 0);
                if (lanes.pockets.containsKey(id) || lanes.pocket(id, Cases.text(one, "kind", "sealed"),
                        Cases.num(one, "x", 0), Cases.num(one, "z", 0), Cases.num(one, "h", 4)) == null) {
                    notes.add("pocket " + id + " did not fit");
                }
            }
            for (Map<String, Object> one : Cases.maps(kase, "routes")) {
                int id = Cases.num(one, "id", 0);
                boolean fromYard = "yard".equals(Cases.text(one, "from", "hub")) && lanes.yard != null;
                List<String> pinned = Cases.list(one, "pieces").stream().map(String::valueOf).toList();
                if (lanes.lanes.containsKey(id) || lanes.grow(id, Lanes.seed(one), Cases.num(one, "count", 8),
                        fromYard ? lanes.yard : lanes.hub, fromYard ? lanes.farYard : lanes.hubYard, pinned) == null) {
                    notes.add("route " + id + " did not leave its yard");
                }
            }
            build(people);
            BlockPos chest = field.offset(chestX, 1, chestZ);
            plot.put(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST));
            if (level.getBlockEntity(chest) instanceof Container box) {
                box.setItem(0, new ItemStack(Items.COOKED_BEEF, 64));
            }
            FuzzColony.Founded founded = FuzzColony.found(level, field.offset(bedX, 1, hubAt.z0() + 1), List.of(chest),
                beds, field.offset(hubAt.x0() + 1, 1, hubAt.z0() + 1));
            colony = founded.colony();
            seal();
            crew = new Crew(founded.residents()).lowest(Plot.FLOOR + Lanes.BOTTOM - 4);
            List<Step> spots = lanes.hub.steps;
            for (int i = 0; i < founded.residents().size(); i++) {
                ResidentEntity person = founded.residents().get(i);
                BlockPos start;
                if (rail != null && i == founded.residents().size() - 1) {
                    conductor = person;
                    start = rail.station(0).offset(3, 1, -3);
                } else {
                    Step spot = spots.get((i * 7) % spots.size());
                    start = field.offset(spot.x(), spot.y(), spot.z());
                    if (rail != null) {
                        person.licences().trade(Vocations.required(RailContent.VOCATION))
                            .ifPresent(trade -> person.licences().allow(trade, false));
                    }
                }
                person.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0.0F, 0.0F);
            }
            List<Map<String, Object>> asked = Cases.maps(kase, "asks");
            for (int i = 0; i < asked.size(); i++) {
                Map<String, Object> one = asked.get(i);
                long at = Math.max(0, Cases.num(one, "at", 0));
                if (one.containsKey("pocket")) {
                    Pocket pocket = lanes.pockets.get(Cases.num(one, "pocket", -1));
                    if (pocket != null) {
                        asks.add(new Ask(i, null, -1, pocket, field.offset(pocket.target), at, 0));
                    }
                    continue;
                }
                Lane lane = lanes.lanes.get(Cases.num(one, "route", -1));
                int step = lane == null ? -1 : lane.standAt(Cases.num(one, "along", 1000));
                if (step < 1) {
                    continue;
                }
                Step there = lane.steps.get(step);
                int cost = lane.cost(step) + ("yard".equals(lane.from) ? rideCost + 200 : 0);
                asks.add(new Ask(i, lane, step, null, field.offset(there.x(), there.y(), there.z()), at, cost));
            }
            int queue = 0;
            int costliest = 0;
            long latest = 0;
            for (Ask ask : asks) {
                queue += 2 * ask.cost + 100;
                costliest = Math.max(costliest, ask.cost);
                latest = Math.max(latest, ask.at);
            }
            for (Map<String, Object> edit : edits) {
                latest = Math.max(latest, Cases.num(edit, "at", 0));
            }
            budget = (int) Math.clamp(latest + RELEARN + SLACK + 3L * costliest + 2L * queue / Math.max(1, walkers)
                + 600, 4_000, BUDGET);
            for (Lane lane : lanes.lanes.values()) {
                Step first = lane.steps.getFirst();
                happened.add("0: lane " + lane.id + " from the " + lane.from + " at " + rel(field.offset(first.x(),
                    first.y(), first.z())) + ": " + String.join(", ", lane.pieces));
            }
            happened.add("0: laid " + w + " by " + d + ", " + lanes.lanes.size() + " lanes ("
                + lanes.lanes.values().stream().mapToInt(lane -> lane.steps.size()).sum() + " steps), "
                + lanes.pockets.size() + " pockets, " + founded.residents().size() + " residents"
                + (rail != null ? ", a line" : "") + "; budget " + budget + (notes.isEmpty() ? "" : "; " + notes));
            return this;
        }

        int[] shift(int[] span) {
            return new int[] {span[0] - field.getZ(), span[1] - field.getZ()};
        }

        /** Puts the case's blocks in the world: ground, every reserved cell, the line, scenery, then fluids. */
        void build(int people) {
            int half = plot.half();
            BlockState stone = Lanes.STONE;
            for (int x = -half + 1; x <= half - 1; x++) {
                for (int z = -half + 1; z <= half - 1; z++) {
                    for (int y = Lanes.BOTTOM; y < 0; y++) {
                        BlockPos at = plot.at(x, y, z);
                        if (!level.getBlockState(at).is(Blocks.STONE)) {
                            level.setBlock(at, stone, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        }
                    }
                }
            }
            List<BlockPos> joins = new ArrayList<>();
            for (Long2ObjectMap.Entry<Slot> entry : lanes.slots.long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                BlockState state = entry.getValue().state();
                if (state == Lanes.LAID_LATER) {
                    continue;
                }
                BlockPos at = field.offset(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
                place(at, state);
                if (Lanes.connects(state.getBlock())) {
                    joins.add(at);
                }
            }
            if (rail != null) {
                rail.lay((cell, state) -> plot.put(cell, state), 0);
                happened.add("0: a line laid from " + rel(new BlockPos(rail.x(), rail.y(), rail.north())) + " to "
                    + rel(new BlockPos(rail.x(), rail.y(), rail.south())));
            }
            List<Map<String, Object>> fluids = new ArrayList<>();
            for (Map<String, Object> one : Cases.maps(kase, "scenery")) {
                String kind = Cases.text(one, "kind", "");
                if (kind.equals("pool") || kind.equals("lava")) {
                    fluids.add(one);
                } else {
                    scenery(one, joins);
                }
            }
            for (Map<String, Object> one : fluids) {
                pool(one);
            }
            for (BlockPos at : joins) {
                BlockState state = level.getBlockState(at);
                BlockState joined = Block.updateFromNeighbourShapes(state, level, at);
                if (joined != state) {
                    level.setBlock(at, joined, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }

        /**
         * Reads back what was reserved to be laid by others (the line, beds, the chest) and takes the hard cells
         * whose blocks are read again each check.
         */
        void seal() {
            LongArrayList hard = new LongArrayList();
            List<BlockState> states = new ArrayList<>();
            for (Long2ObjectMap.Entry<Slot> entry : new ArrayList<>(lanes.slots.long2ObjectEntrySet())) {
                long key = entry.getLongKey();
                if (entry.getValue().state() == Lanes.LAID_LATER) {
                    BlockPos at = field.offset(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
                    lanes.needs(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), level.getBlockState(at));
                }
                if (entry.getValue().hard()) {
                    hard.add(key);
                    states.add(lanes.slot(key).state());
                }
            }
            hardKeys = hard.toLongArray();
            hardStates = states.toArray(BlockState[]::new);
        }

        void place(BlockPos at, BlockState state) {
            level.setBlock(at, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        /** A block of scenery, laid only where nothing is reserved and inside the field. */
        void decor(int x, int y, int z, BlockState state, List<BlockPos> joins) {
            if (!lanes.inside(x, y, z) || y > Lanes.TOP - 2 || lanes.reserved(x, y, z)) {
                return;
            }
            BlockPos at = field.offset(x, y, z);
            place(at, state);
            if (Lanes.connects(state.getBlock())) {
                joins.add(at);
            }
        }

        void scenery(Map<String, Object> one, List<BlockPos> joins) {
            int x = Cases.num(one, "x", 0);
            int z = Cases.num(one, "z", 0);
            int y = Math.clamp(Cases.num(one, "y", 1), 1, Lanes.HIGHEST);
            int h = Math.clamp(Cases.num(one, "h", 1), 1, 6);
            int sw = Math.clamp(Cases.num(one, "w", 1), 1, 6);
            int sd = Math.clamp(Cases.num(one, "d", 1), 1, 6);
            Direction facing = Direction.from2DDataValue(Cases.num(one, "facing", 0));
            boolean open = Cases.flag(one, "open");
            boolean top = Cases.flag(one, "top");
            BlockState air = Lanes.AIR;
            switch (Cases.text(one, "kind", "")) {
                case "hill" -> {
                    for (int k = 1; k <= h; k++) {
                        int in = k - 1;
                        box(x + in, z + in, sw - 2 * in, sd - 2 * in, k, k, Blocks.DIRT.defaultBlockState(), joins);
                    }
                }
                case "block" -> box(x, z, sw, sd, 1, h, top ? Lanes.BRICKS : Lanes.STONE, joins);
                case "pit" -> box(x, z, sw, sd, Math.max(Lanes.BOTTOM + 1, 1 - h), 0, air, joins);
                case "leaves" -> box(x, z, sw, sd, y, y + h - 1, Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, true), joins);
                case "fence", "wall" -> {
                    BlockState post = one.get("kind").equals("fence") ? Lanes.FENCE
                        : Blocks.STONE_BRICK_WALL.defaultBlockState();
                    for (int i = 0; i < sw + sd; i++) {
                        int px = x + (facing.getAxis() == Direction.Axis.X ? i : 0);
                        int pz = z + (facing.getAxis() == Direction.Axis.Z ? i : 0);
                        for (int k = 1; k <= (post == Lanes.FENCE ? 1 : h); k++) {
                            decor(px, k, pz, post == Lanes.FENCE ? post : Lanes.BRICKS, joins);
                        }
                    }
                }
                case "stairs" -> decor(x, y, z, Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM),
                    joins);
                case "slab" -> box(x, z, sw, sd, y, y, Blocks.OAK_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM), joins);
                case "trapdoor" -> box(x, z, Math.min(sw, 3), Math.min(sd, 3), y, y, Blocks.OAK_TRAPDOOR
                    .defaultBlockState().setValue(TrapDoorBlock.FACING, facing).setValue(TrapDoorBlock.OPEN, open)
                    .setValue(TrapDoorBlock.HALF, top ? Half.TOP : Half.BOTTOM), joins);
                case "door" -> {
                    if (!lanes.reserved(x, 1, z) && !lanes.reserved(x, 2, z) && lanes.inside(x, 2, z)) {
                        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing)
                            .setValue(DoorBlock.OPEN, open);
                        decor(x, 1, z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), joins);
                        decor(x, 2, z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), joins);
                    }
                }
                case "carpet" -> box(x, z, sw, sd, 1, 1, Blocks.WHITE_CARPET.defaultBlockState(), joins);
                case "tower" -> {
                    box(x, z, Math.min(sw, 3), Math.min(sd, 3), 1, h + 1, Lanes.STONE, joins);
                    int px = x - 1;
                    for (int k = 1; k <= h + 1; k++) {
                        if (level.getBlockState(field.offset(x, k, z)).is(Blocks.STONE)) {
                            decor(px, k, z, Blocks.LADDER.defaultBlockState()
                                .setValue(LadderBlock.FACING, Direction.WEST), joins);
                        }
                    }
                }
                case "room" -> {
                    int rw = Math.max(3, sw);
                    int rd = Math.max(3, sd);
                    for (int i = 0; i < rw; i++) {
                        for (int j = 0; j < rd; j++) {
                            boolean edge = i == 0 || j == 0 || i == rw - 1 || j == rd - 1;
                            for (int k = 1; k <= 3; k++) {
                                decor(x + i, k, z + j, edge ? Lanes.PLANKS : air, joins);
                            }
                            if (top) {
                                decor(x + i, 4, z + j, Lanes.PLANKS, joins);
                            }
                        }
                    }
                }
                default -> { }
            }
        }

        void box(int x, int z, int bw, int bd, int y0, int y1, BlockState state, List<BlockPos> joins) {
            for (int i = 0; i < bw; i++) {
                for (int j = 0; j < bd; j++) {
                    for (int k = y0; k <= y1; k++) {
                        decor(x + i, k, z + j, state, joins);
                    }
                }
            }
        }

        /**
         * A pool of water or lava sunk into the ground, laid only where every cell is unreserved and solid blocks
         * hold it in on every side and below, so it can never run.
         */
        void pool(Map<String, Object> one) {
            boolean hot = "lava".equals(one.get("kind"));
            int x = Cases.num(one, "x", 0);
            int z = Cases.num(one, "z", 0);
            int pw = Math.clamp(Cases.num(one, "w", 1), 1, hot ? 3 : 6);
            int pd = Math.clamp(Cases.num(one, "d", 1), 1, hot ? 3 : 6);
            int deep = hot ? 1 : Math.clamp(Cases.num(one, "h", 1), 1, 3);
            LongOpenHashSet cells = new LongOpenHashSet();
            for (int i = 0; i < pw; i++) {
                for (int j = 0; j < pd; j++) {
                    for (int k = 0; k > -deep; k--) {
                        if (lanes.inside(x + i, k, z + j) && !lanes.reserved(x + i, k, z + j)
                                && !lanes.reserved(x + i, k + 1, z + j)) {
                            cells.add(BlockPos.asLong(x + i, k, z + j));
                        }
                    }
                }
            }
            boolean changed = true;
            while (changed && !cells.isEmpty()) {
                changed = false;
                for (long key : cells.toLongArray()) {
                    int cx = BlockPos.getX(key);
                    int cy = BlockPos.getY(key);
                    int cz = BlockPos.getZ(key);
                    for (Direction side : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST,
                            Direction.WEST, Direction.DOWN}) {
                        long next = BlockPos.asLong(cx + side.getStepX(), cy + side.getStepY(), cz + side.getStepZ());
                        if (cells.contains(next)) {
                            continue;
                        }
                        BlockPos there = field.offset(BlockPos.getX(next), BlockPos.getY(next), BlockPos.getZ(next));
                        BlockState state = level.getBlockState(there);
                        if (!state.isCollisionShapeFullBlock(level, there) || !state.getFluidState().isEmpty()) {
                            cells.remove(key);
                            changed = true;
                            break;
                        }
                    }
                }
            }
            BlockState fluid = (hot ? Blocks.LAVA : Blocks.WATER).defaultBlockState();
            for (long key : cells) {
                BlockPos at = field.offset(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
                place(at, fluid);
                if (hot) {
                    lava.add(at);
                }
            }
        }

        // ---- running ------------------------------------------------------------------------------------------

        public int budget() {
            return budget;
        }

        public void step(long elapsed, List<Violation> into) {
            now = elapsed;
            int before = into.size();
            into.addAll(late);
            late.clear();
            if (rail != null) {
                FuzzRail.Stage was = rail.stage();
                FuzzRail.Stage is = rail.advance(elapsed, colony);
                if (is != was) {
                    happened.add(elapsed + ": the line is " + is.name().toLowerCase()
                        + (rail.trouble().isEmpty() ? "" : ": " + rail.trouble()));
                }
                boolean driven = rail.conducted();
                if (driven != wasDriven) {
                    wasDriven = driven;
                    happened.add(elapsed + ": the train is " + (driven ? "driven" : "not driven"));
                    if (driven) {
                        drivenOnce = true;
                    }
                }
                for (ResidentEntity person : crew.standing()) {
                    if (person.getVehicle() instanceof CarriageContraptionEntity && person != conductor
                            && rode.add(person.getUUID())) {
                        happened.add(elapsed + ": a resident boards at " + rel(person.blockPosition()));
                    }
                }
            }
            for (int i = 0; i < edits.size(); i++) {
                if (Cases.num(edits.get(i), "at", 0) <= elapsed && edited.add(i)) {
                    happened.add(elapsed + ": " + disturb(edits.get(i)));
                    lastEdit = elapsed;
                }
            }
            check(elapsed);
            for (Ask ask : asks) {
                if (ask.root == null && ask.at <= elapsed) {
                    submit(ask);
                }
            }
            boolean onFoot = residentsOnLanes();
            boolean drives = rail != null && rail.serves(colony) && rail.conducted();
            for (Ask ask : asks) {
                if (ask.root == null || ask.ending != null) {
                    continue;
                }
                if (FuzzLabor.why(level, colony.id(), Set.of(ask.root)).stream()
                        .anyMatch(why -> why.contains("WAY_PENDING"))) {
                    ask.pending += elapsed - Math.max(lastStep, ask.at);
                    ask.pendingRun += elapsed - Math.max(lastStep, ask.at);
                    ask.longestPending = Math.max(ask.longestPending, ask.pendingRun);
                    if (ask.owed == Owed.OWED) {
                        ask.deadline += elapsed - Math.max(lastStep, ask.at);
                    }
                } else {
                    ask.pendingRun = 0;
                }
                String cut = ask.pocket != null ? broken(ask.pocket.cells) : cut(ask.lane, drives);
                boolean own = cut != null;
                if (cut == null && ask.lane != null && !onFoot) {
                    cut = "a resident is off the lanes: " + offLanes();
                }
                if (cut == null) {
                    if (ask.owed == Owed.WAIT) {
                        ask.owed = Owed.OWED;
                        ask.since = elapsed;
                        ask.deadline = elapsed + bound(ask) + (elapsed > ask.at + 40 ? RELEARN : 0);
                        owedCount++;
                    }
                    ask.cutAt = -1;
                } else {
                    if (ask.owed == Owed.OWED) {
                        ask.owed = Owed.LAPSED;
                        happened.add(elapsed + ": ask " + ask.index + " is no longer owed: " + cut);
                    lapses.merge(cut.substring(0, Math.min(cut.length(), cut.startsWith("a resident") ? 19 : 12)), 1,
                        Integer::sum);
                    }
                    if (ask.cutAt < 0 || own != ask.cutOwn) {
                        ask.cutAt = elapsed;
                        ask.cut = cut;
                        ask.cutOwn = own;
                    }
                }
                if (!ask.judged && ask.owed == Owed.OWED && elapsed >= ask.deadline) {
                    ask.judged = true;
                    if (ask.pocket != null) {
                        if (FuzzLabor.why(level, colony.id(), Set.of(ask.root)).isEmpty()) {
                            into.add(new Violation("paths.silent", what(ask) + " into a sealed " + ask.pocket.kind
                                + " is still open " + (elapsed - ask.at) + " ticks after it was asked, and the plan"
                                + " names no reason it waits; " + state(ask) + "; the plan says "
                                + FuzzLabor.told(level, colony.id())));
                        }
                    } else {
                        into.add(new Violation(riding(ask, "paths.unreached"), what(ask) + " has been owed since " + ask.since
                            + " and is still open at " + elapsed + " (bound " + (ask.deadline - ask.since) + "): "
                            + lane(ask) + "; the residents stand at " + walking() + "; " + state(ask)
                            + "; the plan says " + FuzzLabor.told(level, colony.id()) + rails()));
                    }
                }
            }
            crew.watch(elapsed, this::rel, into, this::excused);
            reported += into.size() - before;
            lastStep = elapsed;
        }

        void submit(Ask ask) {
            ask.root = UUID.nameUUIDFromBytes(("folkways-fuzz-paths/" + plot.index() + "/" + ask.index + "/"
                + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
            Stand stand = new Stand(NodeSpec.of(ask.root, OWNER, new WorkSite.AtBlock(WorldPos.of(level, ask.to)),
                Stances.at(level, ask.to), Workload.Once.of(10)).done());
            ask.nominal = now + bound(ask);
            colony.submit(OWNER, level.dimension(), Grown.of(stand), (node, how) -> {
                ask.ending = how;
                ask.endedAt = now;
                ask.where = residents();
                happened.add(now + ": ask " + ask.index + " ended " + describe(how));
                ended(ask);
            });
            happened.add(now + ": ask " + ask.index + " to " + rel(ask.to)
                + (ask.pocket != null ? " in a " + ask.pocket.kind : " on lane " + ask.lane.id));
        }

        final List<Violation> late = new ArrayList<>();

        void ended(Ask ask) {
            if (ask.owed != Owed.OWED) {
                return;
            }
            if (ask.pocket != null && ask.ending instanceof Ending.Done) {
                late.add(new Violation("paths.impossible-done", what(ask) + " in a sealed " + ask.pocket.kind
                    + " was done at " + ask.endedAt + "; nobody can stand there; the residents stood at " + ask.where));
            } else if (ask.lane != null && ask.ending instanceof Ending.Failed) {
                late.add(new Violation(riding(ask, "paths.failed"), what(ask) + ", owed since " + ask.since + ", ended "
                    + describe(ask.ending) + " at " + ask.endedAt + " though its lane was whole and every resident"
                    + " on the lanes: " + lane(ask) + "; when it ended the residents stood at " + ask.where
                    + "; the plan says " + FuzzLabor.told(level, colony.id()) + rails()));
            } else if (ask.lane != null && ask.ending instanceof Ending.Done) {
                doneOwed++;
            }
        }

        /** Ticks an ask on a whole lane has: slack, three times the walk, and its share of the asks ahead of it. */
        int bound(Ask ask) {
            if (ask.pocket != null) {
                return REFUSE;
            }
            long queue = 0;
            for (Ask other : asks) {
                if (other != ask && other.root != null && other.ending == null && other.lane != null) {
                    queue += 2L * other.cost + 100;
                }
            }
            return (int) Math.min(BUDGET, SLACK + 3L * ask.cost + 2 * queue / Math.max(1, walkers));
        }

        /** Why {@code lane} is not whole to walk, or null if it is. */
        String cut(Lane lane, boolean drives) {
            String home = broken(lanes.hub.cells);
            if (home != null) {
                return "the hub is broken: " + home;
            }
            String own = broken(lane.cells);
            if (own != null) {
                return "lane " + lane.id + " is broken: " + own;
            }
            if ("yard".equals(lane.from)) {
                String yard = broken(lanes.yard.cells);
                if (yard != null) {
                    return "the yard is broken: " + yard;
                }
                String line = broken(lanes.line.cells);
                if (line != null) {
                    return "the line is broken: " + line;
                }
                if (!drives) {
                    return "the train " + (rail.serves(colony) ? "has nobody driving it" : "does not serve the colony");
                }
            }
            return null;
        }

        /** The first of {@code cells} that no longer holds its block, told, or null. */
        String broken(LongArrayList cells) {
            if (broken.isEmpty()) {
                return null;
            }
            for (int i = 0; i < cells.size(); i++) {
                long key = cells.getLong(i);
                if (broken.contains(key)) {
                    BlockPos at = field.offset(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
                    return rel(at) + " is " + BlockStateName.of(level.getBlockState(at)) + ", not "
                        + BlockStateName.of(lanes.slot(key).state());
                }
            }
            return null;
        }

        /**
         * Reads every hard cell back off the world: after a disturbance and while what it let loose may still run,
         * and every few seconds besides.
         */
        void check(long elapsed) {
            boolean stirring = lastEdit >= 0 && elapsed - lastEdit <= 600;
            if (!stirring && elapsed - lastFullCheck < 200) {
                return;
            }
            lastFullCheck = elapsed;
            LongOpenHashSet found = new LongOpenHashSet();
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
            for (int i = 0; i < hardKeys.length; i++) {
                long key = hardKeys[i];
                probe.set(field.getX() + BlockPos.getX(key), field.getY() + BlockPos.getY(key),
                    field.getZ() + BlockPos.getZ(key));
                if (!Lanes.same(hardStates[i], level.getBlockState(probe))) {
                    found.add(key);
                }
            }
            if (found.size() != broken.size() || !found.equals(broken)) {
                List<String> told = new ArrayList<>();
                for (long key : found) {
                    if (!broken.contains(key) && told.size() < 4) {
                        BlockPos at = field.offset(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
                        told.add(rel(at) + " " + level.getBlockState(at) + " for " + lanes.slot(key).state());
                    }
                }
                happened.add(elapsed + ": " + found.size() + " reserved cells differ from what was laid " + told);
            }
            broken = found;
        }

        /** Whether every resident standing is on a whole lane, the hub or the yard (or one cell off), or rides. */
        boolean residentsOnLanes() {
            return offLanes().isEmpty();
        }

        List<String> offLanes() {
            List<String> off = new ArrayList<>();
            boolean drives = rail != null && rail.serves(colony) && rail.conducted();
            List<Lane> whole = new ArrayList<>();
            if (broken(lanes.hub.cells) == null) {
                whole.add(lanes.hub);
            }
            if (lanes.yard != null && broken(lanes.yard.cells) == null) {
                whole.add(lanes.yard);
            }
            if (lanes.line != null && broken(lanes.line.cells) == null) {
                whole.add(lanes.line);
            }
            for (Lane lane : lanes.lanes.values()) {
                if (broken(lane.cells) == null) {
                    whole.add(lane);
                }
            }
            for (ResidentEntity person : crew.standing()) {
                if (person.isPassenger()) {
                    continue;
                }
                BlockPos at = person.blockPosition().subtract(field);
                boolean on = false;
                for (int dy = -1; dy <= 1 && !on; dy++) {
                    for (int side = 0; side < 5 && !on; side++) {
                        int dx = side == 1 ? 1 : side == 2 ? -1 : 0;
                        int dz = side == 3 ? 1 : side == 4 ? -1 : 0;
                        long key = BlockPos.asLong(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                        for (Lane lane : whole) {
                            if (lane.walked.contains(key)) {
                                on = true;
                                break;
                            }
                        }
                    }
                }
                if (!on) {
                    BlockPos feet = person.blockPosition();
                    Slot under = lanes.slot(at.below().asLong());
                    off.add(rel(feet) + " in " + BlockStateName.of(level.getBlockState(feet)) + " on "
                        + BlockStateName.of(level.getBlockState(feet.below()))
                        + (under == null ? " (unreserved)" : under.hard() ? " (hard)" : " (soft)"));
                }
            }
            return off;
        }

        public boolean settled(long elapsed) {
            if (edited.size() < edits.size()) {
                return false;
            }
            for (Ask ask : asks) {
                if (ask.root == null) {
                    return false;
                }
                if (ask.ending != null) {
                    continue;
                }
                long due = ask.owed == Owed.OWED ? ask.deadline : Math.max(ask.nominal, ask.at + REFUSE);
                if (elapsed < due + (ask.owed == Owed.OWED ? 0 : STILL)) {
                    return false;
                }
            }
            return true;
        }

        public List<Violation> judge(boolean settled, long elapsed) {
            List<Violation> broke = new ArrayList<>(late);
            late.clear();
            for (Ask ask : asks) {
                if (ask.root == null || ask.ending != null || ask.owed == Owed.OWED) {
                    continue;
                }
                // An ask into a sealed pocket must have been refused or given a reason; one on a lane cut (its own
                // cells, not a resident wandering) for a long while, that nobody is working on, must have a reason.
                boolean sealed = ask.pocket != null && broken(ask.pocket.cells) == null && elapsed - ask.at >= REFUSE;
                boolean longCut = ask.lane != null && ask.cutOwn && ask.cutAt >= 0 && elapsed - ask.cutAt >= STILL
                    && FuzzLabor.state(level, colony.id(), ask.root).map(one -> one != NodeState.WORKING).orElse(false);
                if ((sealed || longCut) && FuzzLabor.why(level, colony.id(), Set.of(ask.root)).isEmpty()) {
                    broke.add(new Violation("paths.silent", what(ask) + " is still open after " + elapsed
                        + " ticks, " + (sealed ? "into a sealed " + ask.pocket.kind : "its lane cut since " + ask.cutAt
                        + " (" + ask.cut + ")") + ", and the colony's plan names no reason it waits; " + state(ask)
                        + "; the plan says " + FuzzLabor.told(level, colony.id()) + rails()));
                }
            }
            reported += broke.size();
            Map<String, Object> told = summary();
            Object events = told.remove("happened");
            LOGGER.info("[fuzz paths] plot {} after {} ticks{}: {} {}", plot.index(), elapsed,
                settled ? ", settled" : "", told, TravelBudget.drainHeartbeat());
            long longest = asks.stream().mapToLong(ask -> ask.longestPending).max().orElse(0);
            if (longest >= 2_000) {
                LOGGER.info("[fuzz paths] plot {} waited {} ticks on a way; the case: {}", plot.index(), longest,
                    new com.google.gson.Gson().toJson(kase));
            }
            if (reported > 0 || longest >= 2_000) {
                LOGGER.info("[fuzz paths] plot {} broke {} properties; it went: {}", plot.index(), reported, events);
            }
            return broke;
        }

        // ---- disturbances -------------------------------------------------------------------------------------

        String disturb(Map<String, Object> edit) {
            String kind = Cases.text(edit, "kind", "");
            int id = Cases.num(edit, "id", -1);
            if (kind.equals("mend")) {
                return mend(Cases.num(edit, "of", -1));
            }
            if (RAIL_EDITS.contains(kind)) {
                return derail(edit, kind);
            }
            int ax;
            int ay;
            int az;
            Lane lane = edit.containsKey("route") ? lanes.lanes.get(Cases.num(edit, "route", -1)) : null;
            if (lane != null) {
                Step at = lane.steps.get((int) ((long) Math.clamp(Cases.num(edit, "along", 0), 0, 1000)
                    * (lane.steps.size() - 1) / 1000));
                ax = at.x();
                ay = at.y();
                az = at.z();
            } else {
                ax = Cases.num(edit, "x", 0);
                az = Cases.num(edit, "z", 0);
                ay = Math.clamp(Cases.num(edit, "y", 1), Lanes.LOWEST, Lanes.HIGHEST);
            }
            int ew = Math.clamp(Cases.num(edit, "w", 1), 1, 4);
            int ed = Math.clamp(Cases.num(edit, "d", 1), 1, 4);
            int eh = Math.clamp(Cases.num(edit, "h", 1), 1, 3);
            int x0 = ax - ew / 2;
            int z0 = az - ed / 2;
            Map<BlockPos, BlockState> before = new LinkedHashMap<>();
            BlockPos min = new BlockPos(x0, ay - 1, z0);
            BlockPos max = new BlockPos(x0 + ew - 1, ay + eh, z0 + ed - 1);
            switch (kind) {
                case "dig" -> change(before, x0, ay - 1, z0, ew, eh, ed, cell -> Lanes.AIR);
                case "fill" -> change(before, x0, ay, z0, ew, eh, ed, cell -> Lanes.STONE);
                case "wall" -> {
                    boolean alongX = Math.floorMod(Cases.num(edit, "id", 0), 2) == 0;
                    int span = ew + ed;
                    min = new BlockPos(ax - (alongX ? span / 2 : 0), ay, az - (alongX ? 0 : span / 2));
                    max = new BlockPos(min.getX() + (alongX ? span - 1 : 0), ay + eh,
                        min.getZ() + (alongX ? 0 : span - 1));
                    change(before, min.getX(), ay, min.getZ(), alongX ? span : 1, eh + 1, alongX ? 1 : span,
                        cell -> Lanes.BRICKS);
                }
                case "unladder" -> {
                    min = new BlockPos(x0 - 1, ay - 8, z0 - 1);
                    max = new BlockPos(x0 + ew, ay + 8, z0 + ed);
                    change(before, x0 - 1, ay - 8, z0 - 1, ew + 2, 17, ed + 2, cell -> {
                        BlockState there = level.getBlockState(cell);
                        return there.is(Blocks.LADDER) || there.is(Blocks.VINE) ? Lanes.AIR : null;
                    });
                }
                case "iron" -> {
                    min = new BlockPos(x0 - 1, ay - 2, z0 - 1);
                    max = new BlockPos(x0 + ew, ay + 3, z0 + ed);
                    change(before, x0 - 1, ay - 2, z0 - 1, ew + 2, 6, ed + 2, cell -> {
                        BlockState there = level.getBlockState(cell);
                        if (there.is(Blocks.OAK_DOOR)) {
                            return Blocks.IRON_DOOR.defaultBlockState()
                                .setValue(DoorBlock.FACING, there.getValue(DoorBlock.FACING))
                                .setValue(DoorBlock.HALF, there.getValue(DoorBlock.HALF))
                                .setValue(DoorBlock.HINGE, there.getValue(DoorBlock.HINGE))
                                .setValue(DoorBlock.OPEN, false);
                        }
                        return there.getBlock() instanceof FenceGateBlock ? Blocks.STONE_BRICK_WALL.defaultBlockState()
                            : null;
                    });
                }
                case "shut", "unshut" -> {
                    boolean open = kind.equals("unshut");
                    min = new BlockPos(x0 - 2, ay - 2, z0 - 2);
                    max = new BlockPos(x0 + ew + 1, ay + 4, z0 + ed + 1);
                    for (int x = min.getX(); x <= max.getX(); x++) {
                        for (int z = min.getZ(); z <= max.getZ(); z++) {
                            for (int y = min.getY(); y <= max.getY(); y++) {
                                BlockPos at = field.offset(x, y, z);
                                BlockState there = level.getBlockState(at);
                                if (there.getBlock() instanceof DoorBlock door && there.is(Blocks.OAK_DOOR)
                                        && there.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                                    door.setOpen(null, level, there, at, open);
                                } else if (there.getBlock() instanceof FenceGateBlock
                                        || there.getBlock() instanceof TrapDoorBlock) {
                                    level.setBlock(at, there.setValue(BlockStateProperties.OPEN, open),
                                        Block.UPDATE_ALL);
                                }
                            }
                        }
                    }
                }
                case "flood" -> {
                    BlockPos source = field.offset(ax, ay + eh, az);
                    min = new BlockPos(ax - 9, Lanes.BOTTOM, az - 9);
                    max = new BlockPos(ax + 9, ay + eh, az + 9);
                    if (level.getBlockState(source).isAir() && !body(source)) {
                        before.put(source.subtract(field), Lanes.AIR);
                        plot.put(source, Blocks.WATER.defaultBlockState());
                        flooded = true;
                    }
                }
                default -> {
                    return "nothing happens (" + kind + ")";
                }
            }
            landed.put(id, new Landed(id, kind, before, min, max, now));
            return "disturbance " + id + ": " + kind + " at " + "(" + ax + "," + ay + "," + az + ")"
                + (lane != null ? " on lane " + lane.id : "") + ", " + before.size() + " blocks changed";
        }

        interface Change {
            BlockState to(BlockPos cell);
        }

        /** Sets each cell of the box to what {@code change} says (null: leave it), keeping what was there. */
        void change(Map<BlockPos, BlockState> before, int x0, int y0, int z0, int bw, int bh, int bd, Change change) {
            for (int x = x0; x < x0 + bw; x++) {
                for (int z = z0; z < z0 + bd; z++) {
                    for (int y = y0; y < y0 + bh; y++) {
                        if (!lanes.inside(x, y, z)) {
                            continue;
                        }
                        BlockPos at = field.offset(x, y, z);
                        BlockState to = change.to(at);
                        BlockState was = level.getBlockState(at);
                        if (to == null || to.equals(was) || !to.isAir() && body(at)) {
                            continue;
                        }
                        before.put(at.subtract(field), was);
                        plot.put(at, to);
                    }
                }
            }
        }

        /** Whether a resident's body takes up {@code at}: nothing solid is put there, so nobody is buried. */
        boolean body(BlockPos at) {
            for (ResidentEntity person : crew.standing()) {
                BlockPos feet = person.blockPosition();
                if (feet.equals(at) || feet.above().equals(at)) {
                    return true;
                }
            }
            return false;
        }

        /** Puts back what disturbance {@code of} changed, exactly, where nobody stands in the way. */
        String mend(int of) {
            Landed was = landed.get(of);
            if (was == null) {
                return "nothing to mend (" + of + ")";
            }
            int put = 0;
            for (Map.Entry<BlockPos, BlockState> cell : was.before().entrySet()) {
                BlockPos at = field.offset(cell.getKey());
                BlockState state = cell.getValue();
                if (!state.isAir() && body(at)) {
                    continue;
                }
                level.setBlock(at, state, state.isAir() ? Block.UPDATE_ALL
                    : Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                put++;
            }
            return "disturbance " + of + " (" + was.kind() + ") mended, " + put + " of " + was.before().size()
                + " blocks put back";
        }

        String derail(Map<String, Object> edit, String kind) {
            if (rail == null || rail.stage() != FuzzRail.Stage.RUNNING) {
                return "the line is not running, so no " + kind;
            }
            int part = Math.floorMod(Cases.num(edit, "part", 0), 100);
            int which = Math.floorMod(Cases.num(edit, "which", 0), 2);
            switch (kind) {
                case "track" -> {
                    int along = rail.north() + (rail.south() - rail.north()) * part / 100;
                    BlockPos cut = new BlockPos(rail.x(), rail.y(), along);
                    plot.put(cut, Blocks.AIR.defaultBlockState());
                    return "the track is broken at " + rel(cut);
                }
                case "scrap" -> {
                    rail.scrap();
                    return "the train is scrapped";
                }
                case "disassemble" -> {
                    return rail.disassemble() ? "the train is taken apart at its station"
                        : "the train is not at a station to be taken apart";
                }
                case "reschedule" -> {
                    Train train = rail.train();
                    if (train == null) {
                        return "there is no train to reschedule";
                    }
                    int mode = Math.floorMod(Cases.num(edit, "mode", 0), 3);
                    String[] names = rail.names();
                    if (mode == 0) {
                        train.runtime.discardSchedule();
                        return "the train's schedule is taken away";
                    }
                    if (mode == 1) {
                        train.runtime.setSchedule(rail.timetable(new String[] {names[0], "nowhere" + plot.index()},
                            rail.dwell(), true), false);
                        return "the train is sent somewhere that is not on its line";
                    }
                    train.runtime.setSchedule(rail.timetable(new String[] {names[1], names[0]},
                        new int[] {rail.dwell()[1] + 5, rail.dwell()[0]}, true), false);
                    return "the train's timetable changes, the same stops held for other times";
                }
                case "conductor" -> {
                    if (conductor == null || !conductor.isAlive()) {
                        return "the conductor is already gone";
                    }
                    crew.kill(crew.standing().indexOf(conductor));
                    return "the conductor is killed at " + rel(conductor.blockPosition());
                }
                case "platform" -> {
                    int[] span = rail.berth(which);
                    for (int z = span[0]; z <= span[1]; z++) {
                        for (int x : new int[] {rail.x() + 3, rail.x() + 4}) {
                            for (int y = rail.y() + 1; y <= rail.y() + 3; y++) {
                                BlockPos at = new BlockPos(x, y, z);
                                if (!body(at)) {
                                    plot.put(at, Blocks.COBBLESTONE.defaultBlockState());
                                }
                            }
                        }
                    }
                    return "the platform at " + rail.names()[which] + " is walled up";
                }
                case "release" -> {
                    rail.release(colony);
                    return "the train is given back";
                }
                default -> {
                    return "nothing happens to the line";
                }
            }
        }

        /** Whether the case itself may have killed a resident that died of {@code cause} where it lay. */
        boolean excused(ResidentEntity person, String cause) {
            BlockPos at = person.blockPosition().subtract(field);
            for (Landed one : landed.values()) {
                int reach = one.kind().equals("flood") ? 0 : 6;
                if (at.getX() >= one.min().getX() - reach && at.getX() <= one.max().getX() + reach
                        && at.getZ() >= one.min().getZ() - reach && at.getZ() <= one.max().getZ() + reach
                        && at.getY() >= one.min().getY() - 16 && at.getY() <= one.max().getY() + reach) {
                    return true;
                }
            }
            return cause.equals("drown") && flooded;
        }

        // ---- telling ------------------------------------------------------------------------------------------

        /** The kind for an ask on a lane from the far yard, which only the train may reach: kept apart. */
        static String riding(Ask ask, String kind) {
            return "yard".equals(ask.lane.from) ? kind + "-riding" : kind;
        }

        String what(Ask ask) {
            return "ask " + ask.index + " to " + rel(ask.to) + " (asked at " + ask.at + ")";
        }

        String lane(Ask ask) {
            Lane lane = ask.lane;
            List<String> pieces = lane.pieces;
            String told = String.join(", ", pieces.subList(0, Math.min(pieces.size(), 40)))
                + (pieces.size() > 40 ? ", ..." : "");
            return "lane " + lane.id + " from the " + lane.from + ", step " + ask.step + " of " + lane.steps.size()
                + " (" + told + "), walk cost " + ask.cost + " ticks";
        }

        String state(Ask ask) {
            return "its node is " + FuzzLabor.state(level, colony.id(), ask.root).map(String::valueOf).orElse("gone")
                + "; the labor reads " + FuzzLabor.heartbeat(level, colony.id());
        }

        List<String> residents() {
            return crew.standing().stream().map(person -> rel(person.blockPosition())
                + (person.isPassenger() ? " riding" : "")).toList();
        }

        /** Where each resident stands and where its feet are headed: its path's next and last nodes. */
        List<String> walking() {
            return crew.standing().stream().map(person -> {
                var path = person.getNavigation().getPath();
                String going = path == null ? "no path" : path.isDone() ? "path done"
                    : "path node " + path.getNextNodeIndex() + "/" + path.getNodeCount() + " next "
                    + rel(path.getNextNodePos()) + " end " + rel(path.getTarget());
                return rel(person.blockPosition()) + String.format(" at %.2f,%.2f,%.2f ", person.getX() - field.getX(),
                    person.getY() - field.getY(), person.getZ() - field.getZ()) + going;
            }).toList();
        }

        String rails() {
            if (rail == null) {
                return "";
            }
            Train train = rail.train();
            return "; the line is " + rail.stage().name().toLowerCase() + (train == null ? ", no train"
                : ", train " + (train.derailed ? "derailed" : "on its track") + (rail.conducted() ? ", driven"
                : ", undriven") + (train.runtime.getSchedule() == null ? ", unscheduled" : "")
                + (rail.serves(colony) ? "" : ", not serving the colony")) + ", riders " + rode.size();
        }

        public Map<String, Object> summary() {
            int done = (int) asks.stream().filter(ask -> ask.ending instanceof Ending.Done).count();
            return Cases.map("w", w, "d", d, "lanes", lanes.lanes.size(),
                "steps", lanes.lanes.values().stream().mapToInt(lane -> lane.steps.size()).sum(),
                "pockets", lanes.pockets.size(), "residents", crew.size(), "asks", asks.size(), "done", done,
                "owed", owedCount, "doneOwed", doneOwed,
                "wayPending", asks.stream().mapToLong(ask -> ask.pending).sum(),
                "longestPending", asks.stream().mapToLong(ask -> ask.longestPending).max().orElse(0),
                "lapses", Map.copyOf(lapses),
                "rail", rail == null ? "none" : rail.stage().name().toLowerCase() + (drivenOnce ? " driven" : ""),
                "riders", rode.size(),
                "happened", List.copyOf(happened.subList(0, Math.min(happened.size(), 400))));
        }

        public void close() {
            if (colony != null) {
                for (Ask ask : asks) {
                    if (ask.root != null && ask.ending == null) {
                        colony.withdraw(OWNER, ask.root);
                    }
                }
                FuzzColony.raze(level, colony);
            }
            if (rail != null) {
                rail.scrap();
            }
        }

        String rel(BlockPos cell) {
            return "(" + (cell.getX() - field.getX()) + "," + (cell.getY() - field.getY()) + ","
                + (cell.getZ() - field.getZ()) + ")";
        }

        static String describe(Ending how) {
            return how instanceof Ending.Failed failed ? "failed: " + failed.why().translationKey() : how.toString();
        }
    }

    /** A block state told briefly: its block and the properties that matter here. */
    private static final class BlockStateName {
        static String of(BlockState state) {
            String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            if (!state.getFluidState().isEmpty()) {
                name += "+" + BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).getPath();
            }
            return name;
        }
    }
}
