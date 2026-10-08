package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.engine.labor.FuzzLabor;
import io.github.izakyl.folkways.fuzz.Cases;
import io.github.izakyl.folkways.fuzz.Crew;
import io.github.izakyl.folkways.fuzz.LiveTarget;
import io.github.izakyl.folkways.fuzz.Plot;
import io.github.izakyl.folkways.fuzz.Rng;
import io.github.izakyl.folkways.fuzz.Violation;
import io.github.izakyl.folkways.plugins.person.FuzzColony;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Construction as it really runs, at the scale of a village quarter: one to eight residents with beds, food, tools
 * and a stock that covers the blueprint, and one build order, filed as a player's would be, for a volume up to
 * {@link #MOST_WIDE} by {@link #MOST_HIGH} by {@link #MOST_WIDE}. The blueprint is a set of features (houses of one
 * or two storeys, walls, towers, pillars, posts, arches, pens, stairs, piles of sand and gravel, pools, digs through
 * laid ground, furnished pads), each on its own patch with {@link #GAP} cells to walk and scaffold in between and
 * each carrying whatever it stands on, so any subset of them, which is what the shrinker makes of a case, is
 * still a blueprint the colony can build.
 *
 * <p>Most cases run undisturbed and are held to all of it:
 * <ul>
 *   <li>the order is filed, and finishes within the case's budget;</li>
 *   <li>when it finishes the world matches the blueprint, and still does once it has tidied up;</li>
 *   <li>no scaffolding stands and nothing lies loose afterwards, and nothing outside the blueprint changed;</li>
 *   <li>no resident died or fell off.</li>
 * </ul>
 * The refusals an order names while it works are not failures of themselves: they say why a cell waits this
 * round (no stance to work it from yet, or working it now would cut off work already under way), and an order that
 * truly cannot go on shows as one that never finishes, its refusals then given in the failure.
 *
 * <p>The rest are disturbed: a player sets blocks in and round the volume, takes stock or adds it, a resident is
 * killed or a zombie stands among them for a while. Those are held to less:
 * <ul>
 *   <li>an order still open at the end accounts for every cell it has not built: a refusal names it, or the work
 *   that holds it is under way or waits for a reason the colony's plan gives;</li>
 *   <li>a finished order matched the blueprint when it finished, and leaves no scaffolding behind;</li>
 *   <li>nothing outside the blueprint changed but where the player changed it;</li>
 *   <li>no resident died of anything but the case killing it, or fell off.</li>
 * </ul>
 */
public final class LiveBuild implements LiveTarget {

    /** Ticks of labor a cell is given per resident, on top of {@link #SETUP}: a night's sleep and fetching included. */
    static final int TICKS_PER_CELL = 200;
    /** Ticks every case is given besides its cells: arriving, taking up tools, the first fetch. */
    static final int SETUP = 6_000;
    /** Ticks a finished order is given to take its scaffolding down and its builders to stow what they carry. */
    static final int TIDY = 300;
    /** Cells left open between two features, for the builders to walk, stand and scaffold in. */
    static final int GAP = 2;
    static final int MOST_WIDE = 40;
    static final int MOST_HIGH = 13;
    static final int MOST_CREW = 8;
    /** The most cells a blueprint is drawn with; its crew is drawn big enough to build it in {@link #LONGEST}. */
    static final int MOST_CELLS = 1_200;
    static final int LONGEST = 30_000;
    /** How many cases a player disturbs. */
    static final double DISTURBED = 0.35;
    /** Ticks a zombie that frightens the builders stands among them before it goes. */
    static final int SPOOK_TICKS = 400;
    /** The platform each way from the plot's origin; the colony lives on its west side, the volume east of it. */
    static final int HALF = 40;

    private static final List<String> SOLID = List.of("minecraft:stone", "minecraft:cobblestone",
        "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:stone_bricks", "minecraft:bricks",
        "minecraft:smooth_stone");
    private static final List<String> PLANKS = List.of("minecraft:oak_planks", "minecraft:spruce_planks",
        "minecraft:birch_planks");
    private static final List<String> LOGS = List.of("minecraft:oak_log", "minecraft:spruce_log",
        "minecraft:stripped_birch_log");
    private static final List<String> DUG = List.of("minecraft:stone", "minecraft:dirt", "minecraft:cobblestone",
        "minecraft:oak_planks", "minecraft:coarse_dirt", "minecraft:andesite");
    private static final List<String> LOOSE = List.of("minecraft:sand", "minecraft:gravel", "minecraft:red_sand");
    private static final List<String> STAIRS = List.of("minecraft:oak_stairs", "minecraft:stone_brick_stairs",
        "minecraft:cobblestone_stairs", "minecraft:spruce_stairs");
    private static final List<String> SLABS = List.of("minecraft:oak_slab", "minecraft:stone_brick_slab",
        "minecraft:cobblestone_slab", "minecraft:spruce_slab");
    private static final List<String> CARPETS = List.of("minecraft:white_carpet", "minecraft:red_carpet",
        "minecraft:blue_carpet");
    private static final List<String> POSTS = List.of("minecraft:oak_fence", "minecraft:spruce_fence",
        "minecraft:cobblestone_wall");
    private static final List<Direction> SIDES = List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST,
        Direction.EAST);
    private static final List<String> KINDS = List.of("house", "house", "house", "wall", "wall", "tower", "pillar",
        "post", "arch", "pen", "steps", "pile", "pool", "pool", "dig", "dig", "decor", "decor");
    private static final List<String> OPS = List.of("poke", "poke", "poke", "poke", "steal", "stock", "kill",
        "spook");

    public String name() {
        return "build";
    }

    public int budget() {
        return budget(MOST_CELLS, 1);
    }

    public int half() {
        return HALF;
    }

    static int budget(int cells, int crew) {
        return SETUP + cells * TICKS_PER_CELL / Math.max(1, crew);
    }

    public Map<String, Object> draw(Rng rng) {
        // Most volumes a street's worth, some a whole quarter: a big one takes many times as long to build.
        int widest = rng.pick(16, 16, 24, 24, 32, MOST_WIDE);
        int w = rng.between(8, widest);
        int h = rng.between(6, MOST_HIGH);
        int d = rng.between(8, widest);
        List<Object> features = new ArrayList<>();
        List<int[]> taken = new ArrayList<>();
        int wanted = rng.between(2, 4 + w * d / 100);
        int cells = 0;
        for (int tries = 0; tries < 400 && features.size() < wanted; tries++) {
            String kind = rng.pick(KINDS);
            int[] size = size(kind, rng, h);
            if (size == null || size[0] > w || size[1] > d) {
                continue;
            }
            int x = rng.between(0, w - size[0]);
            int z = rng.between(0, d - size[1]);
            int[] box = {x, z, x + size[0], z + size[1]};
            if (taken.stream().anyMatch(other -> box[0] < other[2] + GAP && other[0] < box[2] + GAP
                    && box[1] < other[3] + GAP && other[1] < box[3] + GAP)) {
                continue;
            }
            Map<String, Object> feature = Cases.map("kind", kind, "x", x, "z", z, "w", size[0], "d", size[1],
                "h", size[2], "seed", rng.below(1 << 30));
            Draft draft = new Draft(feature);
            draft.draw(kind);
            if (cells + draft.cells.size() > MOST_CELLS) {
                continue;
            }
            cells += draft.cells.size();
            taken.add(box);
            features.add(feature);
        }
        int least = Math.clamp((cells * TICKS_PER_CELL + LONGEST - SETUP - 1) / (LONGEST - SETUP), 1, MOST_CREW);
        int crew = rng.between(least, Math.min(MOST_CREW, least + 4));
        Map<String, Object> kase = Cases.map("w", w, "h", h, "d", d, "features", features,
            "crew", Cases.map("count", crew), "stock", rng.between(100, 200));
        if (rng.chance(DISTURBED)) {
            List<Object> ops = new ArrayList<>();
            int last = Math.max(200, budget(cells, crew) / 3) / 20;
            for (int i = rng.between(1, 6); i > 0; i--) {
                ops.add(Cases.map("op", rng.pick(OPS), "at", 20 * rng.between(10, last),
                    "aim", rng.chance(0.7) ? rng.below(1 << 20) : -1,
                    "x", rng.between(-1, w), "y", rng.between(0, h), "z", rng.between(-1, d),
                    "block", rng.chance(0.5) ? "minecraft:air" : rng.pick(SOLID),
                    "who", rng.below(MOST_CREW), "count", rng.between(1, 48)));
            }
            kase.put("ops", ops);
        }
        return kase;
    }

    /** A feature's footprint and height, {w, d, h}, drawn to fit under {@code most}; null if it cannot. */
    private static int[] size(String kind, Rng rng, int most) {
        boolean alongX = rng.chance(0.5);
        return switch (kind) {
            case "house" -> most < 5 ? null
                : new int[] {rng.between(5, 11), rng.between(5, 11), rng.between(5, Math.min(most, 9))};
            case "wall" -> along(alongX, rng.between(3, 16), 3, rng.between(1, Math.min(most, 7)));
            case "tower" -> most < 4 ? null : new int[] {3, 3, rng.between(4, most)};
            case "pillar" -> along(alongX, 2, 1, rng.between(2, Math.min(most, 10)));
            case "post" -> new int[] {1, 1, rng.between(2, Math.min(most, 4))};
            case "arch" -> along(alongX, rng.between(3, 7), 1, rng.between(3, Math.min(most, 6)));
            case "pen" -> new int[] {rng.between(3, 9), rng.between(3, 9), rng.between(1, 2)};
            case "steps" -> {
                int n = rng.between(2, Math.min(most, 7));
                yield along(alongX, n, rng.between(1, 4), n);
            }
            case "pile" -> new int[] {rng.between(1, 5), rng.between(1, 5), rng.between(1, 3)};
            case "pool" -> {
                int deep = rng.between(3, 9);
                yield new int[] {rng.between(3, 9), deep, deep >= 4 ? rng.between(1, 2) : 1};
            }
            case "dig" -> new int[] {rng.between(3, 7), rng.between(3, 7), rng.between(1, Math.min(most, 4))};
            case "decor" -> new int[] {rng.between(2, 7), rng.between(2, 7), rng.between(2, Math.min(most, 3))};
            default -> null;
        };
    }

    private static int[] along(boolean alongX, int length, int across, int h) {
        return alongX ? new int[] {length, across, h} : new int[] {across, length, h};
    }

    public Run open(Plot plot, Map<String, Object> kase) {
        return new Build(plot, kase).lay();
    }


    /**
     * One feature drawn out in its own frame: {@code u} along x, {@code v} along z, {@code y} up from the floor of
     * the volume. What it lays before the order (ground) and what the blueprint wants there (cells) are kept apart.
     */
    static final class Draft {
        final int ox;
        final int oz;
        final int w;
        final int d;
        final int h;
        final Rng rng;
        final Map<BlockPos, String> cells = new LinkedHashMap<>();
        final Map<BlockPos, String> ground = new LinkedHashMap<>();

        Draft(Map<String, Object> feature) {
            this.ox = Cases.num(feature, "x", 0);
            this.oz = Cases.num(feature, "z", 0);
            this.w = Math.max(1, Cases.num(feature, "w", 1));
            this.d = Math.max(1, Cases.num(feature, "d", 1));
            this.h = Math.max(1, Cases.num(feature, "h", 1));
            this.rng = new Rng(Cases.num(feature, "seed", 0));
        }

        BlockPos at(int u, int y, int v) {
            return new BlockPos(ox + u, y, oz + v);
        }

        void set(int u, int y, int v, String state) {
            if (u >= 0 && u < w && v >= 0 && v < d && y >= 0 && y < h) {
                cells.put(at(u, y, v), state);
            }
        }

        boolean has(int u, int y, int v) {
            return cells.containsKey(at(u, y, v));
        }

        String get(int u, int y, int v) {
            return cells.getOrDefault(at(u, y, v), "");
        }

        void lay(int u, int y, int v, String state) {
            if (u >= 0 && u < w && v >= 0 && v < d && y >= 0 && y < h) {
                ground.put(at(u, y, v), state);
            }
        }

        boolean inside(int u, int v) {
            return u >= 0 && u < w && v >= 0 && v < d;
        }

        void draw(String kind) {
            switch (kind) {
                case "house" -> house();
                case "wall" -> wall();
                case "tower" -> tower();
                case "pillar" -> pillar();
                case "post" -> post();
                case "arch" -> arch();
                case "pen" -> pen();
                case "steps" -> steps();
                case "pile" -> pile();
                case "pool" -> pool();
                case "dig" -> dig();
                case "decor" -> decor();
                default -> { }
            }
        }

        /**
         * A house on a plank floor: walls with a door and windows, one or two storeys joined by a ladder, a flat
         * or gabled roof, and furniture along the inside of the walls, so the middle of every room stays free to
         * walk and stand in.
         */
        void house() {
            String wall = rng.pick(SOLID);
            String post = rng.chance(0.5) ? rng.pick(LOGS) + "[axis=y]" : wall;
            String floor = rng.pick(PLANKS);
            int layers = (d + 1) / 2;
            boolean two = h >= 9 && rng.chance(0.6);
            int top = two ? 7 : 3;
            boolean gable = !two && h >= top + 1 + layers && rng.chance(0.6);
            String slab = rng.pick(SLABS);
            String roof = rng.pick(slab + "[type=bottom]", slab + "[type=top]", slab + "[type=double]",
                rng.pick(PLANKS), rng.pick(STAIRS) + "[facing=" + rng.pick(SIDES).getName() + ",half="
                    + rng.pick("top", "bottom") + "]");
            boolean ceiling = !roof.contains("type=top") && !roof.contains("half=top");
            Direction door = rng.pick(SIDES);
            int[] doorAt = edgeMiddle(door);
            Direction ladderSide = null;
            int[] ladderAt = null;
            if (two) {
                List<Direction> others = new ArrayList<>(SIDES);
                others.remove(door);
                ladderSide = rng.pick(others);
                int[] wallMid = edgeMiddle(ladderSide);
                ladderAt = new int[] {wallMid[0] - ladderSide.getStepX(), wallMid[1] - ladderSide.getStepZ()};
            }
            for (int u = 0; u < w; u++) {
                for (int v = 0; v < d; v++) {
                    set(u, 0, v, floor);
                    boolean edge = u == 0 || v == 0 || u == w - 1 || v == d - 1;
                    boolean corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1);
                    for (int y = 1; y <= top; y++) {
                        if (two && y == 4) {
                            set(u, y, v, edge ? wall : floor);
                        } else if (corner) {
                            set(u, y, v, post);
                        } else if (edge) {
                            set(u, y, v, wall);
                        } else {
                            set(u, y, v, "minecraft:air");
                        }
                    }
                }
            }
            String hinge = rng.pick("left", "right");
            set(doorAt[0], 1, doorAt[1], "minecraft:oak_door[facing=" + door.getName() + ",half=lower,hinge=" + hinge
                + ",open=false]");
            set(doorAt[0], 2, doorAt[1], "minecraft:oak_door[facing=" + door.getName() + ",half=upper,hinge=" + hinge
                + ",open=false]");
            for (int u = 0; u < w; u++) {
                for (int v = 0; v < d; v++) {
                    boolean edge = u == 0 || v == 0 || u == w - 1 || v == d - 1;
                    boolean corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1);
                    boolean nearDoor = Math.abs(u - doorAt[0]) + Math.abs(v - doorAt[1]) <= 1;
                    boolean behindLadder = ladderAt != null && u == ladderAt[0] + ladderSide.getStepX()
                        && v == ladderAt[1] + ladderSide.getStepZ();
                    if (!edge || corner || nearDoor || behindLadder || !rng.chance(0.35)) {
                        continue;
                    }
                    String pane = rng.pick("minecraft:glass", "minecraft:glass_pane");
                    set(u, 2, v, pane);
                    if (two) {
                        set(u, 6, v, pane);
                    }
                }
            }
            if (gable) {
                for (int i = 0; ; i++) {
                    int a = i;
                    int b = d - 1 - i;
                    int y = top + 1 + i;
                    if (a > b) {
                        break;
                    }
                    for (int u = 0; u < w; u++) {
                        if (a == b) {
                            set(u, y, a, slab + "[type=bottom]");
                        } else {
                            set(u, y, a, slab.replace("_slab", "_stairs") + "[facing=south,half=bottom]");
                            set(u, y, b, slab.replace("_slab", "_stairs") + "[facing=north,half=bottom]");
                        }
                    }
                    if (a >= b - 1) {
                        break;
                    }
                    for (int v = a + 1; v < b; v++) {
                        set(0, y, v, wall);
                        set(w - 1, y, v, wall);
                        for (int u = 1; u < w - 1; u++) {
                            set(u, y, v, "minecraft:air");
                        }
                    }
                }
            } else {
                for (int u = 0; u < w; u++) {
                    for (int v = 0; v < d; v++) {
                        set(u, top + 1, v, roof);
                    }
                }
            }
            if (two) {
                for (int y = 1; y <= 5; y++) {
                    set(ladderAt[0], y, ladderAt[1], "minecraft:ladder[facing=" + ladderSide.getOpposite().getName() + "]");
                }
                furnish(1, true, doorAt, ladderAt);
                furnish(5, ceiling, null, ladderAt);
            } else {
                furnish(1, ceiling && !gable, doorAt, null);
            }
        }


        /** The middle cell of the house's wall on {@code side}. */
        private int[] edgeMiddle(Direction side) {
            return switch (side) {
                case NORTH -> new int[] {w / 2, 0};
                case SOUTH -> new int[] {w / 2, d - 1};
                case WEST -> new int[] {0, d / 2};
                default -> new int[] {w - 1, d / 2};
            };
        }

        /** The wall a room cell along the inside of a wall backs onto, or null for a cell in the middle. */
        private Direction backing(int u, int v) {
            List<Direction> found = new ArrayList<>();
            if (v == 1) {
                found.add(Direction.NORTH);
            }
            if (v == d - 2) {
                found.add(Direction.SOUTH);
            }
            if (u == 1) {
                found.add(Direction.WEST);
            }
            if (u == w - 2) {
                found.add(Direction.EAST);
            }
            return found.isEmpty() ? null : rng.pick(found);
        }

        /** One to five pieces of furniture in the room whose floor is {@code y0} and whose top row is {@code y0 + 2}. */
        private void furnish(int y0, boolean ceiling, int[] door, int[] ladder) {
            Set<Long> taken = new LinkedHashSet<>();
            int pieces = rng.between(1, 5);
            for (int tries = 0; tries < 40 && pieces > 0; tries++) {
                int u = rng.between(1, w - 2);
                int v = rng.between(1, d - 2);
                Direction back = backing(u, v);
                if (back == null || blocked(u, v, door, ladder)) {
                    if (ceiling && rng.chance(0.2) && !(ladder != null && u == ladder[0] && v == ladder[1])
                            && taken.add(BlockPos.asLong(u, y0 + 2, v))) {
                        set(u, y0 + 2, v, "minecraft:lantern[hanging=true]");
                        pieces--;
                    }
                    continue;
                }
                Direction in = back.getOpposite();
                String piece = rng.pick("bed", "chest", "chest", "carpet", "torch", "lantern", "wall_torch",
                    "trapdoor", "hanging");
                if (piece.equals("hanging") && !ceiling) {
                    piece = "carpet";
                }
                boolean done = switch (piece) {
                    case "bed" -> {
                        Direction along = rng.chance(0.5) ? back.getClockWise() : back.getCounterClockWise();
                        int hu = u + along.getStepX();
                        int hv = v + along.getStepZ();
                        if (hu < 1 || hu > w - 2 || hv < 1 || hv > d - 2 || !onWall(hu, hv)
                                || blocked(hu, hv, door, ladder) || taken.contains(BlockPos.asLong(u, y0, v))
                                || taken.contains(BlockPos.asLong(hu, y0, hv))) {
                            yield false;
                        }
                        taken.add(BlockPos.asLong(u, y0, v));
                        taken.add(BlockPos.asLong(hu, y0, hv));
                        String bed = rng.pick("minecraft:red_bed", "minecraft:blue_bed");
                        set(u, y0, v, bed + "[facing=" + along.getName() + ",part=foot]");
                        set(hu, y0, hv, bed + "[facing=" + along.getName() + ",part=head]");
                        yield true;
                    }
                    case "chest" -> {
                        if (!taken.add(BlockPos.asLong(u, y0, v))) {
                            yield false;
                        }
                        Direction right = in.getClockWise();
                        int pu = u + right.getStepX();
                        int pv = v + right.getStepZ();
                        if (rng.chance(0.5) && pu >= 1 && pu <= w - 2 && pv >= 1 && pv <= d - 2 && onWall(pu, pv)
                                && !blocked(pu, pv, door, ladder) && taken.add(BlockPos.asLong(pu, y0, pv))) {
                            set(u, y0, v, "minecraft:chest[facing=" + in.getName() + ",type=left]");
                            set(pu, y0, pv, "minecraft:chest[facing=" + in.getName() + ",type=right]");
                        } else {
                            set(u, y0, v, "minecraft:chest[facing=" + in.getName() + ",type=single]");
                        }
                        yield true;
                    }
                    case "carpet" -> taken.add(BlockPos.asLong(u, y0, v)) && put(u, y0, v, rng.pick(CARPETS));
                    case "torch" -> taken.add(BlockPos.asLong(u, y0, v)) && put(u, y0, v, "minecraft:torch");
                    case "lantern" -> taken.add(BlockPos.asLong(u, y0, v))
                        && put(u, y0, v, "minecraft:lantern[hanging=false]");
                    case "trapdoor" -> taken.add(BlockPos.asLong(u, y0, v))
                        && put(u, y0, v, "minecraft:oak_trapdoor[facing=" + rng.pick(SIDES).getName()
                            + ",half=bottom,open=false]");
                    case "hanging" -> taken.add(BlockPos.asLong(u, y0 + 2, v))
                        && put(u, y0 + 2, v, "minecraft:lantern[hanging=true]");
                    default -> {
                        int wu = u + back.getStepX();
                        int wv = v + back.getStepZ();
                        if (!solidAt(wu, y0 + 1, wv) || !taken.add(BlockPos.asLong(u, y0 + 1, v))) {
                            yield false;
                        }
                        yield put(u, y0 + 1, v, "minecraft:wall_torch[facing=" + in.getName() + "]");
                    }
                };
                if (done) {
                    pieces--;
                }
            }
        }

        private boolean put(int u, int y, int v, String state) {
            set(u, y, v, state);
            return true;
        }

        private boolean onWall(int u, int v) {
            return u == 1 || v == 1 || u == w - 2 || v == d - 2;
        }

        /** Room cells kept clear: the one inside the door and its neighbours, and the ladder's. */
        private boolean blocked(int u, int v, int[] door, int[] ladder) {
            if (ladder != null && u == ladder[0] && v == ladder[1]) {
                return true;
            }
            if (door == null) {
                return false;
            }
            int iu = Math.clamp(door[0], 1, w - 2);
            int iv = Math.clamp(door[1], 1, d - 2);
            return Math.abs(u - iu) + Math.abs(v - iv) <= 1;
        }

        private boolean solidAt(int u, int y, int v) {
            String there = get(u, y, v);
            return SOLID.contains(there) || PLANKS.contains(there) || there.endsWith("[axis=y]");
        }

        /** A wall one block thick, of one block, mixed blocks or logs on every axis, with torches or a ladder on it. */
        void wall() {
            boolean alongX = w >= d;
            int length = alongX ? w : d;
            boolean crown = h >= 2 && rng.chance(0.4);
            int height = crown ? h - 1 : h;
            String style = rng.pick("one", "mixed", "logs");
            String one = rng.pick(SOLID);
            String log = rng.pick(LOGS);
            for (int i = 0; i < length; i++) {
                for (int y = 0; y < height; y++) {
                    String block = switch (style) {
                        case "one" -> one;
                        case "mixed" -> rng.pick(SOLID);
                        default -> log + "[axis=" + rng.pick("x", "y", "z") + "]";
                    };
                    cell(alongX, i, y, 1, block);
                }
                if (crown && rng.chance(0.4)) {
                    cell(alongX, i, height, 1, rng.pick("minecraft:torch", "minecraft:lantern[hanging=false]"));
                }
            }
            int ladder = rng.chance(0.4) ? rng.below(length) : -1;
            for (int side : new int[] {0, 2}) {
                Direction out = alongX ? (side == 0 ? Direction.NORTH : Direction.SOUTH)
                    : (side == 0 ? Direction.WEST : Direction.EAST);
                for (int i = 0; i < length; i++) {
                    if (i == ladder && side == 0) {
                        for (int y = 0; y < height; y++) {
                            cell(alongX, i, y, side, "minecraft:ladder[facing=" + out.getName() + "]");
                        }
                    } else if (rng.chance(0.2)) {
                        cell(alongX, i, rng.below(height), side, "minecraft:wall_torch[facing=" + out.getName() + "]");
                    }
                }
            }
        }

        private void cell(boolean alongX, int i, int y, int across, String state) {
            if (alongX) {
                set(i, y, across, state);
            } else {
                set(across, y, i, state);
            }
        }

        /** A hollow tower three across with a doorway and a ladder up the inside of its back wall. */
        void tower() {
            String wall = rng.pick(SOLID);
            boolean crown = h >= 5 && rng.chance(0.6);
            int height = crown ? h - 1 : h;
            Direction front = rng.pick(SIDES);
            int fu = 1 + front.getStepX();
            int fv = 1 + front.getStepZ();
            for (int y = 0; y < height; y++) {
                for (int u = 0; u < 3; u++) {
                    for (int v = 0; v < 3; v++) {
                        if (u == 1 && v == 1) {
                            set(u, y, v, "minecraft:ladder[facing=" + front.getName() + "]");
                        } else if (u == fu && v == fv && y < 2) {
                            set(u, y, v, "minecraft:air");
                        } else {
                            set(u, y, v, wall);
                        }
                    }
                }
            }
            if (crown) {
                String light = rng.pick("minecraft:torch", "minecraft:lantern[hanging=false]");
                for (int u : new int[] {0, 2}) {
                    for (int v : new int[] {0, 2}) {
                        set(u, height, v, light);
                    }
                }
            }
        }

        /** A column with a ladder up one face and a light on top. */
        void pillar() {
            boolean alongX = w >= d;
            Direction out = alongX ? Direction.WEST : Direction.NORTH;
            boolean crown = h >= 3 && rng.chance(0.5);
            int height = crown ? h - 1 : h;
            String block = rng.pick(SOLID);
            for (int y = 0; y < height; y++) {
                cell(alongX, 1, y, 0, block);
                cell(alongX, 0, y, 0, "minecraft:ladder[facing=" + out.getName() + "]");
            }
            if (crown) {
                cell(alongX, 1, height, 0, rng.pick("minecraft:torch", "minecraft:lantern[hanging=false]"));
            }
        }

        /** A fence or wall post with a lantern or torch on top. */
        void post() {
            String post = rng.pick(POSTS);
            for (int y = 0; y < h - 1; y++) {
                set(0, y, 0, post);
            }
            set(0, h - 1, 0, rng.pick("minecraft:torch", "minecraft:lantern[hanging=false]"));
        }

        /** Two posts and a beam across them, with lanterns hanging from the beam. */
        void arch() {
            boolean alongX = w >= d;
            int length = alongX ? w : d;
            String post = rng.pick(rng.pick(LOGS) + "[axis=y]", rng.pick(SOLID), rng.pick(POSTS));
            String beam = rng.pick(rng.pick(LOGS) + "[axis=" + (alongX ? "x" : "z") + "]", rng.pick(SOLID),
                rng.pick(SLABS) + "[type=double]", rng.pick(SLABS) + "[type=bottom]");
            for (int y = 0; y < h - 1; y++) {
                cell(alongX, 0, y, 0, post);
                cell(alongX, length - 1, y, 0, post);
            }
            for (int i = 0; i < length; i++) {
                cell(alongX, i, h - 1, 0, beam);
            }
            for (int i = 1; i < length - 1; i++) {
                if (i == length / 2 || rng.chance(0.3)) {
                    cell(alongX, i, h - 2, 0, "minecraft:lantern[hanging=true]");
                }
            }
        }

        /** A fenced pen with a gate, hay and carpets inside, and lanterns on its corner posts. */
        void pen() {
            String fence = rng.pick("minecraft:oak_fence", "minecraft:spruce_fence");
            Direction gate = rng.pick(SIDES);
            int[] gateAt = edgeMiddle(gate);
            for (int u = 0; u < w; u++) {
                for (int v = 0; v < d; v++) {
                    boolean edge = u == 0 || v == 0 || u == w - 1 || v == d - 1;
                    boolean corner = (u == 0 || u == w - 1) && (v == 0 || v == d - 1);
                    if (u == gateAt[0] && v == gateAt[1]) {
                        set(u, 0, v, fence.replace("_fence", "_fence_gate") + "[facing=" + gate.getName()
                            + ",open=false,in_wall=false]");
                    } else if (edge) {
                        set(u, 0, v, fence);
                        if (corner && h >= 2 && rng.chance(0.5)) {
                            set(u, 1, v, rng.pick("minecraft:lantern[hanging=false]", "minecraft:torch"));
                        }
                    } else if (rng.chance(0.3)) {
                        set(u, 0, v, rng.pick("minecraft:hay_block[axis=" + rng.pick("x", "y", "z") + "]",
                            rng.pick(CARPETS), "minecraft:oak_trapdoor[facing=" + rng.pick(SIDES).getName()
                                + ",half=" + rng.pick("top", "bottom") + ",open=false]"));
                    }
                }
            }
        }

        /** A flight of stairs, one step up per block along, on a solid fill. */
        void steps() {
            boolean alongX = w == h;
            int n = alongX ? w : d;
            int across = alongX ? d : w;
            boolean forward = rng.chance(0.5);
            Direction up = alongX ? (forward ? Direction.EAST : Direction.WEST) : (forward ? Direction.SOUTH : Direction.NORTH);
            String stair = rng.pick(STAIRS);
            String fill = rng.pick(rng.pick(SOLID), rng.pick(LOGS) + "[axis=" + rng.pick("x", "y", "z") + "]",
                rng.pick(SLABS) + "[type=double]");
            for (int i = 0; i < n; i++) {
                int step = forward ? i : n - 1 - i;
                for (int a = 0; a < across; a++) {
                    for (int y = 0; y < step; y++) {
                        cell(alongX, i, y, a, fill);
                    }
                    cell(alongX, i, step, a, stair + "[facing=" + up.getName() + ",half=bottom]");
                }
            }
        }

        /** Columns of sand and gravel, each resting on the floor or on what is under it. */
        void pile() {
            boolean base = h >= 2 && rng.chance(0.3);
            String under = rng.pick(SOLID);
            for (int u = 0; u < w; u++) {
                for (int v = 0; v < d; v++) {
                    if (base) {
                        set(u, 0, v, under);
                    }
                    if (rng.chance(0.25)) {
                        continue;
                    }
                    int from = base ? 1 : 0;
                    int high = rng.between(from + 1, h);
                    for (int y = from; y < high; y++) {
                        set(u, y, v, rng.pick(LOOSE));
                    }
                }
            }
        }

        /**
         * A basin with a rim one or two high, filled with water and a few waterlogged slabs and stairs along its
         * edge. Over a rim two high nobody sees the water from the floor, so such a pool has a step up onto its rim
         * on the north side.
         */
        void pool() {
            String rim = rng.pick("minecraft:stone_bricks", "minecraft:stone", "minecraft:cobblestone",
                "minecraft:bricks");
            int from = h >= 2 && d >= 4 ? 1 : 0;
            if (from > 0) {
                set(w / 2, 0, 0, rng.pick(STAIRS) + "[facing=south,half=bottom]");
            }
            for (int u = 0; u < w; u++) {
                for (int v = from; v < d; v++) {
                    boolean edge = u == 0 || v == from || u == w - 1 || v == d - 1;
                    for (int y = 0; y < h; y++) {
                        set(u, y, v, edge ? rim : "minecraft:water");
                    }
                }
            }
            int logged = rng.between(0, 3);
            for (int tries = 0; tries < 20 && logged > 0 && w > 2 && d > 2; tries++) {
                int u = rng.between(1, w - 2);
                int v = rng.between(1, d - 2);
                Direction back = backing(u, v);
                if (back == null || !get(u, h - 1, v).equals("minecraft:water")) {
                    continue;
                }
                set(u, h - 1, v, rng.pick(rng.pick(SLABS) + "[type=" + rng.pick("bottom", "top") + ",waterlogged=true]",
                    rng.pick(STAIRS) + "[facing=" + back.getName() + ",half=" + rng.pick("bottom", "top")
                        + ",waterlogged=true]"));
                logged--;
            }
        }

        /**
         * A mound laid before the order, every column at most one higher than its neighbours and the edge one high,
         * so it can be walked over; the blueprint cuts it down to a terrace or by a layer, swaps some of the blocks
         * left on top for others and stands torches on a few.
         */
        void dig() {
            boolean torches = h >= 2 && rng.chance(0.4);
            int most = torches ? h - 1 : h;
            int[][] heights = new int[w][d];
            for (int u = 0; u < w; u++) {
                for (int v = 0; v < d; v++) {
                    int edge = Math.min(Math.min(u, v), Math.min(w - 1 - u, d - 1 - v));
                    heights[u][v] = Math.min(most, 1 + edge);
                }
            }
            String mode = rng.pick("terrace", "layer", "keep");
            int level = rng.below(most);
            for (int u = 0; u < w; u++) {
                for (int v = 0; v < d; v++) {
                    int g = heights[u][v];
                    int f = switch (mode) {
                        case "terrace" -> Math.min(g, level);
                        case "layer" -> Math.max(0, g - 1);
                        default -> g;
                    };
                    for (int y = 0; y < g; y++) {
                        String block = y == 0 && rng.chance(0.2) ? rng.pick(LOOSE) : rng.pick(DUG);
                        lay(u, y, v, block);
                        if (y >= f) {
                            set(u, y, v, "minecraft:air");
                        } else if (y == f - 1 && !LOOSE.contains(block) && rng.chance(0.25)) {
                            set(u, y, v, rng.pick(DUG.stream().filter(other -> !other.equals(block)).toList()));
                        } else if (rng.chance(0.5)) {
                            set(u, y, v, block);
                        }
                    }
                    if (torches && f < h && rng.chance(0.2)
                            && (f == 0 || ground.get(at(u, f - 1, v)).equals(cells.get(at(u, f - 1, v))))) {
                        set(u, f, v, "minecraft:torch");
                    }
                }
            }
        }

        /**
         * A pad of solid blocks with things set on it a cell apart: carpets, lights, trapdoors, slabs and stairs
         * of every shape, logs on every axis, fences, gates, chests single and double, beds and doors.
         */
        void decor() {
            String pad = rng.pick(SOLID);
            for (int u = 0; u < w; u++) {
                for (int v = 0; v < d; v++) {
                    set(u, 0, v, pad);
                }
            }
            Set<Long> taken = new LinkedHashSet<>();
            for (int tries = 0; tries < 30; tries++) {
                int u = rng.below(w);
                int v = rng.below(d);
                Direction facing = rng.pick(SIDES);
                String piece = rng.pick("carpet", "torch", "lantern", "trapdoor", "slab", "stairs", "log", "fence",
                    "gate", "chest", "double", "bed", "door");
                int pu = u;
                int pv = v;
                if (piece.equals("double")) {
                    pu = u + facing.getClockWise().getStepX();
                    pv = v + facing.getClockWise().getStepZ();
                } else if (piece.equals("bed")) {
                    pu = u + facing.getStepX();
                    pv = v + facing.getStepZ();
                }
                if (!inside(pu, pv) || piece.equals("door") && h < 3 || near(taken, u, v) || near(taken, pu, pv)) {
                    continue;
                }
                taken.add(BlockPos.asLong(u, 0, v));
                taken.add(BlockPos.asLong(pu, 0, pv));
                String f = facing.getName();
                switch (piece) {
                    case "carpet" -> set(u, 1, v, rng.pick(CARPETS));
                    case "torch" -> set(u, 1, v, "minecraft:torch");
                    case "lantern" -> set(u, 1, v, "minecraft:lantern[hanging=false]");
                    case "trapdoor" -> set(u, 1, v, "minecraft:spruce_trapdoor[facing=" + f + ",half="
                        + rng.pick("top", "bottom") + ",open=false]");
                    case "slab" -> set(u, 1, v, rng.pick(SLABS) + "[type=" + rng.pick("top", "bottom", "double") + "]");
                    case "stairs" -> set(u, 1, v, rng.pick(STAIRS) + "[facing=" + f + ",half=" + rng.pick("top", "bottom") + "]");
                    case "log" -> set(u, 1, v, rng.pick(LOGS) + "[axis=" + rng.pick("x", "y", "z") + "]");
                    case "fence" -> set(u, 1, v, rng.pick(POSTS));
                    case "gate" -> set(u, 1, v, "minecraft:oak_fence_gate[facing=" + f + ",open=false,in_wall=false]");
                    case "chest" -> set(u, 1, v, "minecraft:chest[facing=" + f + ",type=single]");
                    case "double" -> {
                        set(u, 1, v, "minecraft:chest[facing=" + f + ",type=left]");
                        set(pu, 1, pv, "minecraft:chest[facing=" + f + ",type=right]");
                    }
                    case "bed" -> {
                        set(u, 1, v, "minecraft:white_bed[facing=" + f + ",part=foot]");
                        set(pu, 1, pv, "minecraft:white_bed[facing=" + f + ",part=head]");
                    }
                    default -> {
                        String hinge = rng.pick("left", "right");
                        set(u, 1, v, "minecraft:oak_door[facing=" + f + ",half=lower,hinge=" + hinge + ",open=false]");
                        set(u, 2, v, "minecraft:oak_door[facing=" + f + ",half=upper,hinge=" + hinge + ",open=false]");
                    }
                }
            }
        }

        private static boolean near(Set<Long> taken, int u, int v) {
            for (int du = -1; du <= 1; du++) {
                for (int dv = -1; dv <= 1; dv++) {
                    if (taken.contains(BlockPos.asLong(u + du, 0, v + dv))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }


    private static final class Build implements Run {
        static final List<Item> TOOLS = List.of(Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL);
        final Plot plot;
        final ServerLevel level;
        final Map<String, Object> kase;
        final BlockPos corner;
        final int w;
        final int h;
        final int d;
        final Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        final List<String> kinds = new ArrayList<>();
        final Map<BlockPos, String> owner = new HashMap<>();
        final List<String> happened = new ArrayList<>();
        final Map<BuildRefusal, String> refusals = new LinkedHashMap<>();
        final List<BlockPos> chests = new ArrayList<>();
        final List<Map<String, Object>> ops;
        final Set<Integer> landed = new LinkedHashSet<>();
        /** Cells a player set, with the tick they set them at. */
        final Map<BlockPos, Long> poked = new LinkedHashMap<>();
        final Map<Zombie, Long> zombies = new LinkedHashMap<>();
        Crew crew;
        Map<BlockPos, BlockState> before = Map.of();
        List<BlockPos> unbuiltAtFinish = List.of();
        Colony colony;
        UUID order;
        int budget;
        long finishedAt = -1;

        Build(Plot plot, Map<String, Object> kase) {
            this.plot = plot;
            this.level = plot.level();
            this.kase = kase;
            this.w = Math.clamp(Cases.num(kase, "w", 12), 1, MOST_WIDE);
            this.h = Math.clamp(Cases.num(kase, "h", 6), 1, MOST_HIGH);
            this.d = Math.clamp(Cases.num(kase, "d", 12), 1, MOST_WIDE);
            this.corner = plot.at(-20, 1, -20);
            this.ops = Cases.maps(kase, "ops");
        }

        boolean disturbed() {
            return !ops.isEmpty();
        }

        BlockState state(String text) {
            try {
                return BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), text, false).blockState();
            } catch (Exception unparsable) {
                throw new IllegalArgumentException("the generator drew a block state that does not parse: " + text,
                    unparsable);
            }
        }

        boolean inVolume(BlockPos rel) {
            return rel.getX() >= 0 && rel.getX() < w && rel.getY() >= 0 && rel.getY() < h && rel.getZ() >= 0
                && rel.getZ() < d;
        }

        Build lay() {
            for (Map<String, Object> feature : Cases.maps(kase, "features")) {
                Draft draft = new Draft(feature);
                String kind = Cases.text(feature, "kind", "");
                draft.draw(kind);
                kinds.add(kind);
                draft.ground.forEach((rel, text) -> {
                    if (inVolume(rel)) {
                        plot.put(corner.offset(rel), state(text));
                    }
                });
                draft.cells.forEach((rel, text) -> {
                    if (inVolume(rel)) {
                        cells.put(corner.offset(rel), state(text));
                        owner.put(corner.offset(rel), kind);
                    }
                });
            }
            int people = Math.clamp(Cases.num(asMap(kase.get("crew")), "count", 1), 1, MOST_CREW);
            budget = LiveBuild.budget(cells.size(), people);
            stock(people);
            FuzzColony.Founded founded = FuzzColony.found(level, plot.at(-36, 1, -36), chests, people,
                plot.at(-30, 1, -30));
            colony = founded.colony();
            crew = new Crew(founded.residents());
            before = snapshot();
            if (!cells.isEmpty()) {
                file();
            }
            return this;
        }

        @Override
        public int budget() {
            return budget;
        }

        /**
         * Food, every item the blueprint wants at the case's share of it (a hundred percent or more), a water
         * bucket for every cell of water or waterlogged block, scaffolding, two sets of tools each, and empty chests
         * enough to put back what the work leaves over.
         */
        void stock(int people) {
            List<ItemStack> goods = new ArrayList<>();
            add(goods, Items.COOKED_BEEF, 64 * (people + 1));
            Map<Item, Integer> wanted = new LinkedHashMap<>();
            for (BlockState state : cells.values()) {
                if (state.getBlock() instanceof LiquidBlock) {
                    wanted.merge(Items.WATER_BUCKET, 1, Integer::sum);
                    continue;
                }
                Item item = state.getBlock().asItem();
                if (item != Items.AIR) {
                    boolean twice = state.hasProperty(BlockStateProperties.SLAB_TYPE)
                        && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
                    wanted.merge(item, twice ? 2 : 1, Integer::sum);
                }
                if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
                    wanted.merge(Items.WATER_BUCKET, 1, Integer::sum);
                }
            }
            int share = Math.max(100, Cases.num(kase, "stock", 150));
            wanted.forEach((item, count) -> add(goods, item, (count * share + 99) / 100));
            add(goods, Items.SCAFFOLDING, 256 + 64 * people);
            for (int i = 0; i < 2 * people; i++) {
                TOOLS.forEach(tool -> goods.add(new ItemStack(tool)));
            }
            // Room besides to stow what comes back: every bucket poured comes back empty and keeps a slot of its own.
            int buckets = wanted.getOrDefault(Items.WATER_BUCKET, 0);
            int spare = 2 + (buckets + 26) / 27;
            for (int slot = 0, chest = 0; slot < goods.size() || spare-- > 0; chest++) {
                BlockPos at = plot.at(-38, 1, -34 + 2 * chest);
                plot.put(at, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST));
                chests.add(at);
                Container box = (Container) level.getBlockEntity(at);
                for (int i = 0; i < box.getContainerSize() && slot < goods.size(); i++, slot++) {
                    box.setItem(i, goods.get(slot));
                }
            }
        }

        static void add(List<ItemStack> goods, Item item, int count) {
            for (int left = count; left > 0; left -= item.getDefaultMaxStackSize()) {
                goods.add(new ItemStack(item, Math.min(left, item.getDefaultMaxStackSize())));
            }
        }

        void file() {
            Optional<BuildPresence> presence = presence();
            if (presence.isEmpty()) {
                return;
            }
            List<BlueprintBlock> blocks = new ArrayList<>();
            cells.forEach((at, state) -> blocks.add(new BlueprintBlock(at.subtract(corner), state)));
            Blueprint blueprint = new Blueprint(UUID.randomUUID(), "fuzz", blocks,
                BlueprintVolume.of(w, h, d).orElseThrow(), level.getGameTime());
            presence.get().file(level, blueprint, corner, "fuzz", Optional.empty(), "fuzz")
                .ifPresent(filed -> order = filed.id());
        }

        Optional<BuildPresence> presence() {
            return BuildContent.presenceIn(colony.service(BuildContent.ID, Object.class));
        }

        public void step(long elapsed, List<Violation> into) {
            if (order == null && !cells.isEmpty()) {
                file();
            }
            crew.watch(elapsed, this::rel, into);
            Optional<BuildPresence> presence = order == null ? Optional.empty() : presence();
            if (presence.isPresent()) {
                Map<BlockPos, BuildRefusal> refused = presence.get().refusals(order);
                if (!refused.isEmpty()) {
                    note(elapsed, refused);
                }
                // Before the player's next move, so what they set after the order finished is not held against it.
                if (finishedAt < 0 && !presence.get().filed(order)) {
                    finishedAt = elapsed;
                    unbuiltAtFinish = unbuilt();
                    happened.add(elapsed + ": order finished");
                }
            }
            for (int i = 0; i < ops.size(); i++) {
                if (Cases.num(ops.get(i), "at", 0) <= elapsed && landed.add(i)) {
                    land(ops.get(i), elapsed);
                }
            }
            zombies.entrySet().removeIf(zombie -> {
                if (elapsed < zombie.getValue()) {
                    return false;
                }
                zombie.getKey().discard();
                happened.add(elapsed + ": the zombie goes");
                return true;
            });
        }

        /** Keeps the first time each reason was given, with the cells it was given for then. */
        void note(long elapsed, Map<BlockPos, BuildRefusal> refused) {
            Map<BuildRefusal, List<BlockPos>> byWhy = new LinkedHashMap<>();
            refused.forEach((at, why) -> byWhy.computeIfAbsent(why, any -> new ArrayList<>()).add(at));
            byWhy.forEach((why, at) -> refusals.computeIfAbsent(why, any -> {
                happened.add(elapsed + ": refused " + at.size() + " cells, " + why);
                return why + " at tick " + elapsed + " for " + at.size() + " cells";
            }));
        }

        void land(Map<String, Object> op, long elapsed) {
            List<ResidentEntity> standing = crew.standing();
            switch (Cases.text(op, "op", "")) {
                case "poke" -> {
                    BlockPos at = aimed(op);
                    boolean occupied = standing.stream().anyMatch(person -> person.blockPosition().equals(at)
                        || person.blockPosition().above().equals(at));
                    BlockState block = state(Cases.text(op, "block", "minecraft:air"));
                    if (!occupied && level.getBlockState(at) != block) {
                        plot.put(at, block);
                        poked.put(at.immutable(), elapsed);
                        happened.add(elapsed + ": a player sets " + rel(at) + " to " + text(block));
                    }
                }
                case "steal", "stock" -> {
                    List<Item> items = cells.values().stream().map(state -> state.getBlock().asItem())
                        .filter(item -> item != Items.AIR).distinct().toList();
                    if (items.isEmpty()) {
                        return;
                    }
                    Item item = items.get(Math.floorMod(Cases.num(op, "who", 0), items.size()));
                    int count = Math.max(1, Cases.num(op, "count", 1));
                    if (Cases.text(op, "op", "").equals("stock")) {
                        Container box = (Container) level.getBlockEntity(chests.getLast());
                        for (int i = 0; box != null && i < box.getContainerSize(); i++) {
                            if (box.getItem(i).isEmpty()) {
                                box.setItem(i, new ItemStack(item, Math.min(count, item.getDefaultMaxStackSize())));
                                happened.add(elapsed + ": a player adds " + count + " " + item);
                                break;
                            }
                        }
                        return;
                    }
                    int left = count;
                    for (BlockPos chest : chests) {
                        if (level.getBlockEntity(chest) instanceof Container box) {
                            for (int i = 0; i < box.getContainerSize() && left > 0; i++) {
                                if (box.getItem(i).is(item)) {
                                    left -= box.removeItem(i, left).getCount();
                                }
                            }
                        }
                    }
                    happened.add(elapsed + ": a player takes " + (count - left) + " " + item);
                }
                case "kill" -> {
                    // The last one standing is spared: with nobody left there is no plan to account for the work.
                    if (standing.size() < 2) {
                        return;
                    }
                    ResidentEntity victim = crew.kill(Cases.num(op, "who", 0));
                    if (victim != null) {
                        happened.add(elapsed + ": a resident is killed at " + rel(victim.blockPosition()));
                    }
                }
                case "spook" -> {
                    if (standing.isEmpty()) {
                        return;
                    }
                    ResidentEntity near = standing.get(Math.floorMod(Cases.num(op, "who", 0), standing.size()));
                    Zombie zombie = EntityType.ZOMBIE.create(level);
                    if (zombie == null) {
                        return;
                    }
                    zombie.moveTo(near.getX() + 2, near.getY(), near.getZ() + 2, 0.0F, 0.0F);
                    zombie.setNoAi(true);
                    zombie.setPersistenceRequired();
                    // A helmet keeps it from burning in the sun, and from leaving flesh about when it does.
                    zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                    zombie.setDropChance(EquipmentSlot.HEAD, 0.0F);
                    level.addFreshEntity(zombie);
                    zombies.put(zombie, elapsed + SPOOK_TICKS);
                    happened.add(elapsed + ": a zombie stands beside a resident at " + rel(near.blockPosition()));
                }
                default -> { }
            }
        }

        /** Where a player's block goes: a cell of the blueprint picked by {@code aim}, or the cell the op names. */
        BlockPos aimed(Map<String, Object> op) {
            int aim = Cases.num(op, "aim", -1);
            if (aim >= 0 && !cells.isEmpty()) {
                List<BlockPos> all = new ArrayList<>(cells.keySet());
                return all.get(aim % all.size());
            }
            return corner.offset(Math.clamp(Cases.num(op, "x", 0), -1, w), Math.clamp(Cases.num(op, "y", 0), 0, h),
                Math.clamp(Cases.num(op, "z", 0), -1, d));
        }

        List<BlockPos> unbuilt() {
            List<BlockPos> out = new ArrayList<>();
            cells.forEach((at, wanted) -> {
                if (!BuildPlanner.satisfied(level.getBlockState(at), wanted)) {
                    out.add(at);
                }
            });
            return out;
        }

        boolean pokedAfterFinish(BlockPos at) {
            Long when = poked.get(at);
            return when != null && finishedAt >= 0 && when >= finishedAt;
        }

        public boolean settled(long elapsed) {
            if (cells.isEmpty() || (order == null && elapsed > 200)) {
                return true;
            }
            return finishedAt >= 0 && elapsed - finishedAt >= TIDY && zombies.isEmpty();
        }

        public List<Violation> judge(boolean settled, long elapsed) {
            List<Violation> broke = new ArrayList<>();
            if (cells.isEmpty()) {
                return broke;
            }
            if (order == null) {
                broke.add(new Violation("build.not-filed", "the colony took no build order for " + cells.size()
                    + " cells"));
                return broke;
            }
            boolean finished = finishedAt >= 0;
            List<BlockPos> unbuilt = unbuilt().stream().filter(at -> !pokedAfterFinish(at)).toList();
            if (!finished && !disturbed()) {
                broke.add(new Violation(kind("build.unfinished", unbuilt), "after " + elapsed + " ticks (a budget of "
                    + budget + " for " + cells.size() + " cells and " + crew.size() + " residents) the order is still"
                    + " open, " + (cells.size() - unbuilt.size()) + " built; unbuilt " + describe(unbuilt) + "; "
                    + waiting()));
            } else if (!finished && !crew.standing().isEmpty()) {
                List<BlockPos> unexplained = unexplained(unbuilt);
                if (!unexplained.isEmpty()) {
                    broke.add(new Violation(kind("build.unexplained", unexplained), "after " + elapsed + " ticks the"
                        + " order is still open, " + (cells.size() - unbuilt.size()) + " of " + cells.size()
                        + " cells built, and nothing accounts for " + describe(unexplained) + "; " + waiting()));
                }
            }
            if (finished) {
                if (!unbuiltAtFinish.isEmpty()) {
                    broke.add(new Violation(kind("build.false-finish", unbuiltAtFinish), "the order finished at tick "
                        + finishedAt + " but " + describe(unbuiltAtFinish) + " do not match the blueprint"));
                }
                List<BlockPos> undone = unbuilt.stream().filter(at -> !unbuiltAtFinish.contains(at)).toList();
                if (!undone.isEmpty() && !disturbed()) {
                    broke.add(new Violation(kind("build.unstable", undone), "the order finished at tick " + finishedAt
                        + " with these cells built, and by tick " + elapsed + " " + describe(undone)
                        + " no longer match"));
                }
            }
            List<String> scaffolding = new ArrayList<>();
            List<String> outside = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockState> was : before.entrySet()) {
                BlockPos at = was.getKey();
                BlockState now = level.getBlockState(at);
                if (now == was.getValue() || cells.containsKey(at) || nearPoked(at)) {
                    continue;
                }
                if (now.is(Blocks.SCAFFOLDING)) {
                    scaffolding.add(rel(at));
                } else if (finished || !(fluidOrAir(was.getValue()) && fluidOrAir(now))) {
                    // While the work goes on, water a half-built pool lets out is still running back.
                    outside.add(rel(at) + " " + text(was.getValue()) + " -> " + text(now));
                }
            }
            if (finished && !scaffolding.isEmpty()) {
                broke.add(new Violation("build.scaffold-left", "scaffolding still stands " + (elapsed - finishedAt)
                    + " ticks after the order finished: " + clip(scaffolding)));
            }
            if (!outside.isEmpty()) {
                broke.add(new Violation("build.outside-changed", "blocks outside the blueprint changed"
                    + (finished ? "" : " while the order was open") + ": " + clip(outside)));
            }
            if (finished && !crew.anyKilled()) {
                List<String> loose = new ArrayList<>();
                for (Entity entity : plot.entities()) {
                    if (entity instanceof ItemEntity item) {
                        loose.add(item.getItem().getCount() + " " + item.getItem().getItem() + " at "
                            + rel(item.blockPosition()));
                    }
                }
                if (!loose.isEmpty()) {
                    broke.add(new Violation("build.loose-items", "items lie loose " + (elapsed - finishedAt)
                        + " ticks after the order finished: " + clip(loose)));
                }
            }
            return broke;
        }

        /** Whether a player's block may have moved this one: under, over or beside a cell they set. */
        boolean nearPoked(BlockPos at) {
            for (BlockPos cell : poked.keySet()) {
                if (cell.getX() == at.getX() && cell.getZ() == at.getZ() || cell.distManhattan(at) <= 1) {
                    return true;
                }
            }
            return false;
        }

        static boolean fluidOrAir(BlockState state) {
            return state.isAir() || state.getBlock() instanceof LiquidBlock;
        }

        /**
         * The unbuilt cells an open order does not account for: no refusal names them, and no piece of the work it
         * asked for, all of it at once, still lays them.
         */
        List<BlockPos> unexplained(List<BlockPos> unbuilt) {
            Optional<BuildPresence> presence = presence();
            if (presence.isEmpty()) {
                return unbuilt;
            }
            Set<BlockPos> explained = new LinkedHashSet<>(presence.get().refusals(order).keySet());
            for (BuildSite.Piece piece : presence.get().pieces(order)) {
                piece.work().step().ifPresent(step -> explained.addAll(step.after().keySet()));
            }
            return unbuilt.stream().filter(at -> !explained.contains(at)).toList();
        }

        /** What an open order is waiting on, for a failure's detail: its refusals, its work, the plan, the crew. */
        String waiting() {
            Optional<BuildPresence> presence = presence();
            if (presence.isEmpty()) {
                return "the colony has no build presence";
            }
            Map<BuildRefusal, List<BlockPos>> byWhy = new LinkedHashMap<>();
            presence.get().refusals(order).forEach((at, why) -> byWhy.computeIfAbsent(why, any -> new ArrayList<>()).add(at));
            List<String> refused = new ArrayList<>();
            byWhy.forEach((why, at) -> refused.add(why + " for " + at.size() + " " + clip(at.stream().map(this::rel).toList())));
            List<String> work = new ArrayList<>();
            for (BuildSite.Piece piece : presence.get().pieces(order)) {
                Layering.Work held = piece.work();
                work.add(held.kind() + "@" + rel(held.focus()) + " "
                    + FuzzLabor.state(level, colony.id(), piece.node().id()).map(Enum::name).orElse("unknown")
                    + " tried " + piece.tries() + " from " + clip(held.stances().stream().map(this::rel).toList()));
            }
            List<String> people = new ArrayList<>();
            for (ResidentEntity person : crew.standing()) {
                people.add(rel(person.blockPosition()) + (person.isSleeping() ? " asleep" : ""));
            }
            return "refusing now " + refused + "; holding " + work.size() + " pieces of work " + clip(work)
                + "; the plan says " + FuzzLabor.told(level, colony.id()) + "; residents at " + people
                + "; refusals first given " + refusals.values();
        }

        /**
         * A property's kind named after the feature of the lowest cell that broke it, the one the rest most likely
         * wait on, so one feature that fails often does not hide another: the campaign keeps one case per kind. An
         * order with every cell built that still does not finish (its scaffolding never comes down) is "cleanup".
         */
        String kind(String property, List<BlockPos> broke) {
            return property + "." + broke.stream().min(Comparator.comparingInt(BlockPos::getY))
                .map(at -> owner.getOrDefault(at, "?")).orElse("cleanup");
        }

        Map<BlockPos, BlockState> snapshot() {
            Map<BlockPos, BlockState> out = new HashMap<>();
            for (int x = -plot.half() + 1; x < plot.half(); x++) {
                for (int z = -plot.half() + 1; z < plot.half(); z++) {
                    for (int y = 0; y <= MOST_HIGH + 8; y++) {
                        BlockPos at = plot.at(x, y, z);
                        out.put(at, level.getBlockState(at));
                    }
                }
            }
            return out;
        }

        public Map<String, Object> summary() {
            return Cases.map("cells", cells.size(), "features", List.copyOf(kinds), "crew",
                crew == null ? 0 : crew.size(), "budget", budget, "disturbed", disturbed(), "finished_at", finishedAt,
                "happened", happened.subList(0, Math.min(60, happened.size())));
        }

        public void close() {
            zombies.keySet().forEach(Entity::discard);
            if (colony != null) {
                FuzzColony.raze(level, colony);
            }
        }

        String describe(List<BlockPos> list) {
            List<String> out = new ArrayList<>();
            for (BlockPos at : list.subList(0, Math.min(12, list.size()))) {
                BlockState wanted = cells.get(at);
                out.add(rel(at) + (wanted == null ? "" : " wants " + text(wanted)) + ", has " + text(level.getBlockState(at)));
            }
            return out + (list.size() > 12 ? " and " + (list.size() - 12) + " more" : "");
        }

        static String clip(List<String> list) {
            return list.subList(0, Math.min(12, list.size())) + (list.size() > 12 ? " and " + (list.size() - 12)
                + " more" : "");
        }

        String rel(BlockPos at) {
            return "(" + (at.getX() - corner.getX()) + "," + (at.getY() - corner.getY()) + "," + (at.getZ() - corner.getZ()) + ")";
        }

        static String text(BlockState state) {
            return BlockStateParser.serialize(state).replace("minecraft:", "");
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> asMap(Object value) {
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }
    }
}
