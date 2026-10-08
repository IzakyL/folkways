package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.fuzz.Cases;
import io.github.izakyl.folkways.fuzz.Rng;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * The ground of a paths case, laid by construction rather than read back: a home yard (the hub), then lanes grown
 * out of it piece by piece from a vocabulary each piece of which a resident's body is known to get through (flat
 * runs on any floor, low ceilings two high, stair flights and slab steps up and down, ladders and vines climbed up
 * and down, wooden doors and gates shut or open, railed bridges over pits, walkways in the air and tunnels in the
 * ground), then pockets nobody can get into by construction (a sealed cell, a pillar too high to climb, an island
 * past a moat no body jumps), then scenery on whatever is left.
 *
 * <p>Every cell a piece needs is <em>hard</em>: its floor, the air its body passes through (four high, or two under
 * a low ceiling or in a doorway), a ladder and the wall it hangs on. Nothing else may take a hard cell, and a later
 * piece may share one only by wanting the very same block there, as two lanes do where they cross. Around its cells
 * a piece asks for <em>soft</em> ones: railings, walls, the pit under a bridge. Those only keep scenery
 * off; a later lane's hard cells take them over. So a lane alone is whole, any set of lanes laid together leaves
 * each one whole, and scenery (laid only where nothing is reserved, fluids only where solid blocks hold them in, and
 * nothing that falls) never touches one. Whether a lane is still whole later is then a matter of reading its own
 * hard cells back from the world.
 *
 * <p>Everything is a function of the case: a lane grows from its own seed, up to its own count of pieces, around
 * what the lanes before it took. Dropping lanes, pockets or scenery, or lowering a lane's count, still lays whole
 * lanes, which is what the shrinker needs.
 */
final class Lanes {

    static final int BOTTOM = -8;
    static final int TOP = 20;
    /** The highest and lowest a lane's feet go. */
    static final int HIGHEST = 14;
    static final int LOWEST = BOTTOM + 2;

    static final BlockState AIR = Blocks.AIR.defaultBlockState();
    static final BlockState STONE = Blocks.STONE.defaultBlockState();
    static final BlockState BRICKS = Blocks.STONE_BRICKS.defaultBlockState();
    static final BlockState PLANKS = Blocks.OAK_PLANKS.defaultBlockState();
    static final BlockState FENCE = Blocks.OAK_FENCE.defaultBlockState();
    private static final List<BlockState> FLOORS = List.of(STONE, STONE, PLANKS, Blocks.COBBLESTONE.defaultBlockState(),
        BRICKS, Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState()
            .setValue(SlabBlock.TYPE, SlabType.TOP), Blocks.POLISHED_ANDESITE.defaultBlockState());

    /** A cell's reserved block and whether a piece needs it (hard) or only keeps scenery off it (soft). */
    record Slot(BlockState state, boolean hard) {
    }

    /**
     * A cell a resident's feet pass through on a lane, what passing it costs in ticks, and whether one can be asked
     * to stand there (flat, with full headroom, not on a ladder, a stair or in a doorway).
     */
    record Step(int x, int y, int z, int cost, boolean stand) {
        long key() {
            return BlockPos.asLong(x, y, z);
        }
    }

    enum Edge { FENCE, WALL, RAIL }

    /** A lane, or the hub, the yard or the line: the cells walked along it and the hard cells it needs. */
    static final class Lane {
        final int id;
        final String from;
        final List<Step> steps = new ArrayList<>();
        final LongArrayList cells = new LongArrayList();
        final List<String> pieces = new ArrayList<>();
        final LongOpenHashSet walked = new LongOpenHashSet();

        Lane(int id, String from) {
            this.id = id;
            this.from = from;
        }

        void add(Step step) {
            steps.add(step);
            walked.add(step.key());
        }

        /** Ticks to walk from the lane's start to step {@code upTo}, both included. */
        int cost(int upTo) {
            int total = 0;
            for (int i = 0; i <= Math.min(upTo, steps.size() - 1); i++) {
                total += steps.get(i).cost();
            }
            return total;
        }

        /** The step an ask {@code along} permille of the way should stand on: the last standable one up to there. */
        int standAt(int along) {
            int at = (int) ((long) Math.clamp(along, 0, 1000) * (steps.size() - 1) / 1000);
            for (int i = at; i >= 0; i--) {
                if (steps.get(i).stand()) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** A place nobody can stand in, by construction, and the hard cells that make it so. */
    static final class Pocket {
        final int id;
        final String kind;
        final BlockPos target;
        final LongArrayList cells = new LongArrayList();

        Pocket(int id, String kind, BlockPos target) {
            this.id = id;
            this.kind = kind;
            this.target = target;
        }
    }

    /** A rectangle of open ground at level 1 with a wall round it: the hub, or the yard by the far platform. */
    record Yard(int x0, int z0, int x1, int z1, boolean openWest) {
        boolean perimeter(int x, int z) {
            return x == x0 || x == x1 || z == z0 || z == z1;
        }
    }

    final int w;
    final int d;
    final Long2ObjectOpenHashMap<Slot> slots = new Long2ObjectOpenHashMap<>();
    final Map<Integer, Lane> lanes = new LinkedHashMap<>();
    final Map<Integer, Pocket> pockets = new LinkedHashMap<>();
    final List<String> skipped = new ArrayList<>();
    Lane hub;
    Lane yard;
    Lane line;
    Yard hubYard;
    Yard farYard;

    Lanes(int w, int d) {
        this.w = w;
        this.d = d;
    }

    boolean inside(int x, int y, int z) {
        return x >= 0 && z >= 0 && x < w && z < d && y >= BOTTOM && y <= TOP;
    }

    boolean reserved(int x, int y, int z) {
        return slots.containsKey(BlockPos.asLong(x, y, z));
    }

    Slot slot(long key) {
        return slots.get(key);
    }

    // ---- drafts: a piece's cells, checked against what is laid before any of them is taken -----------------------

    final class Draft {
        final Long2ObjectLinkedOpenHashMap<Slot> pending = new Long2ObjectLinkedOpenHashMap<>();
        final List<Step> steps = new ArrayList<>();
        boolean bad;

        boolean hard(int x, int y, int z, BlockState state) {
            if (bad) {
                return false;
            }
            if (!inside(x, y, z)) {
                bad = true;
                return false;
            }
            long key = BlockPos.asLong(x, y, z);
            Slot mine = pending.get(key);
            if (mine != null && mine.hard()) {
                bad |= !mine.state().equals(state);
                return !bad;
            }
            Slot there = slots.get(key);
            if (there != null && there.hard() && !there.state().equals(state)) {
                bad = true;
                return false;
            }
            pending.put(key, new Slot(state, true));
            return true;
        }

        void soft(int x, int y, int z, BlockState state) {
            if (!inside(x, y, z)) {
                return;
            }
            long key = BlockPos.asLong(x, y, z);
            if (!pending.containsKey(key) && !slots.containsKey(key)) {
                pending.put(key, new Slot(state, false));
            }
        }

        /** Whether every cell of the box is free of anything reserved, laid or drafted. */
        boolean clear(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = y0; y <= y1; y++) {
                        if (!inside(x, y, z) || reserved(x, y, z) || pending.containsKey(BlockPos.asLong(x, y, z))) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        /** Takes the draft's cells; answers the hard ones, which {@code owner} now needs. */
        void commit(LongArrayList owner) {
            for (Long2ObjectMap.Entry<Slot> entry : pending.long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                Slot slot = entry.getValue();
                if (slot.hard()) {
                    slots.put(key, slot);
                    if (owner != null) {
                        owner.add(key);
                    }
                } else {
                    slots.putIfAbsent(key, slot);
                }
            }
        }

        /**
         * A cell walked at feet level {@code y}: its floor (none when the caller laid it in the cell, as a slab),
         * {@code clear} cells of air above the floor (a low ceiling over two), and edges round it.
         */
        void walk(int x, int y, int z, BlockState floor, int clear, BlockState ceiling, Edge edge, int cost,
                boolean stand) {
            if (x < 1 || z < 1 || x > w - 2 || z > d - 2) {
                // Its edges would fall outside the field, leaving a way off it.
                bad = true;
                return;
            }
            if (floor != null) {
                hard(x, y - 1, z, floor);
            }
            for (int k = 0; k < clear; k++) {
                hard(x, y + k, z, AIR);
            }
            if (ceiling != null) {
                hard(x, y + clear, z, ceiling);
            }
            edges(x, y, z, edge, clear + (ceiling != null ? 1 : 0));
            steps.add(new Step(x, y, z, cost, stand && clear >= 4));
        }

        void edges(int x, int y, int z, Edge edge, int high) {
            Edge kind = y < 1 ? Edge.WALL : edge;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    switch (kind) {
                        case FENCE -> {
                            soft(nx, y - 1, nz, BRICKS);
                            soft(nx, y, nz, FENCE);
                        }
                        case RAIL -> {
                            soft(nx, y - 1, nz, PLANKS);
                            soft(nx, y, nz, FENCE);
                        }
                        case WALL -> {
                            for (int k = -1; k < high; k++) {
                                soft(nx, y + k, nz, BRICKS);
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- the hub, the yard and the line ---------------------------------------------------------------------------

    /** Lays a walled rectangle of open ground; {@code hole} cells inside it (beds, a chest) are kept but not walked. */
    Lane yard(int id, String name, Yard at, List<long[]> holes) {
        Lane lane = new Lane(id, name);
        Draft draft = new Draft();
        LongOpenHashSet kept = new LongOpenHashSet();
        for (long[] hole : holes) {
            kept.add(BlockPos.asLong((int) hole[0], 1, (int) hole[1]));
        }
        for (int x = at.x0(); x <= at.x1(); x++) {
            for (int z = at.z0(); z <= at.z1(); z++) {
                if (at.perimeter(x, z)) {
                    if (at.openWest() && x == at.x0()) {
                        continue;
                    }
                    draft.soft(x, 0, z, STONE);
                    draft.soft(x, 1, z, BRICKS);
                    draft.soft(x, 2, z, BRICKS);
                    draft.soft(x, 3, z, BRICKS);
                    continue;
                }
                draft.hard(x, 0, z, STONE);
                if (kept.contains(BlockPos.asLong(x, 1, z))) {
                    draft.hard(x, 1, z, LAID_LATER);
                    for (int y = 2; y <= 4; y++) {
                        draft.hard(x, y, z, AIR);
                    }
                    continue;
                }
                for (int y = 1; y <= 4; y++) {
                    draft.hard(x, y, z, AIR);
                }
                draft.steps.add(new Step(x, 1, z, 5, true));
            }
        }
        if (draft.bad) {
            throw new IllegalStateException(name + " does not fit at " + at);
        }
        draft.commit(lane.cells);
        draft.steps.forEach(lane::add);
        return lane;
    }

    /** What the line's blocks are reserved as until they are laid and read back: nothing a lane ever lays. */
    static final BlockState LAID_LATER = Blocks.BEDROCK.defaultBlockState();

    /** The railway's keep-out where nothing else holds it: air no lane lays, so no lane can take the cell. */
    static final BlockState KEPT_OUT = Blocks.CAVE_AIR.defaultBlockState();

    /** How far the keep-out reaches from the rails' middle: half the five-wide car, and a body's half-width. */
    static final double KEPT_REACH = 2.8D;

    /** Whole cells past an end of the track the keep-out still reaches. */
    static final int KEPT_PAST = 3;

    /** The highest feet over the rails the keep-out holds (Trackways: four over the track's own cell). */
    static final int KEPT_HIGH = 5;

    /**
     * Reserves the line's corridor: its track, stations and platform bricks hard (as {@code needed} lists them, to be
     * read back once laid), the air over its platforms and through which the car runs hard, the rest of the corridor
     * soft, and walls along it but where a platform meets the hub or the yard. {@code car} is the span of z the car
     * stands on before it is assembled.
     */
    Lane line(int x, int north, int south, int[] berthA, int[] berthB, int[] car, List<int[]> needed) {
        Lane lane = new Lane(-3, "line");
        Draft draft = new Draft();
        for (int[] cell : needed) {
            draft.hard(cell[0], cell[1], cell[2], LAID_LATER);
        }
        for (int which = 0; which < 2; which++) {
            int[] span = which == 0 ? berthA : berthB;
            for (int z = span[0]; z <= span[1]; z++) {
                for (int y = 2; y <= 5; y++) {
                    draft.hard(x + 4, y, z, AIR);
                }
                for (int y = 3; y <= 6; y++) {
                    draft.hard(x + 3, y, z, AIR);
                }
                draft.steps.add(new Step(x + 4, 2, z, 5, false));
                draft.steps.add(new Step(x + 3, 3, z, 5, false));
            }
        }
        for (int cx = x - 2; cx <= x + 2; cx++) {
            for (int z = north; z <= south; z++) {
                for (int y = 2; y <= 6; y++) {
                    if (y > 3 || z < car[0] || z > car[1]) {
                        draft.hard(cx, y, z, AIR);
                    }
                }
            }
        }
        // The colony keeps everyone off the railway as wide as its widest train and round past each end of the track
        // (Trackways), so no lane may stand there either: the ground beside the rails, and the caps past both ends.
        for (int cx = x - 2; cx <= x + 2; cx++) {
            for (int z = north - KEPT_PAST; z <= south + KEPT_PAST; z++) {
                double past = z < north ? north - z - 0.5D : z > south ? z - south - 0.5D : 0.0D;
                if ((cx - x) * (cx - x) + past * past >= KEPT_REACH * KEPT_REACH) {
                    continue;
                }
                // Not where the car stands before it is assembled: its blocks are laid there.
                int top = z >= car[0] && z <= car[1] ? 1 : KEPT_HIGH;
                for (int y = 1; y <= top; y++) {
                    long key = BlockPos.asLong(cx, y, z);
                    if (inside(cx, y, z) && !draft.pending.containsKey(key) && !reserved(cx, y, z)) {
                        draft.hard(cx, y, z, KEPT_OUT);
                    }
                }
            }
        }
        for (int cx = x - 4; cx <= x + 4; cx++) {
            for (int z = north - 2; z <= south + 2; z++) {
                boolean side = Math.abs(cx - x) >= 3;
                boolean platform = cx > x && (z >= berthA[0] && z <= berthA[1] || z >= berthB[0] && z <= berthB[1]);
                for (int y = 0; y <= 7; y++) {
                    if (side && !platform && y >= 1 && y <= 3) {
                        draft.soft(cx, y, z, BRICKS);
                    } else {
                        draft.soft(cx, y, z, y == 0 ? STONE : AIR);
                    }
                }
            }
        }
        if (draft.bad) {
            throw new IllegalStateException("the line does not fit");
        }
        draft.commit(lane.cells);
        draft.steps.forEach(lane::add);
        return lane;
    }

    /** Marks a block the line laid as one it needs, read back after laying. */
    void needs(int x, int y, int z, BlockState state) {
        slots.put(BlockPos.asLong(x, y, z), new Slot(state, true));
    }

    // ---- pockets -------------------------------------------------------------------------------------------------

    /** Lays a pocket of {@code kind} at (x, z) if the ground there is free; answers it, or null. */
    Pocket pocket(int id, String kind, int x, int z, int high) {
        Draft draft = new Draft();
        int h = Math.clamp(high, 3, 8);
        BlockPos target;
        switch (kind) {
            case "sealed" -> {
                // A stone block three across and five high, with a body's room hollowed in its middle.
                if (!draft.clear(x - 1, 0, z - 1, x + 1, 5, z + 1)) {
                    return null;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int y = 0; y <= 4; y++) {
                            boolean room = dx == 0 && dz == 0 && (y == 2 || y == 3);
                            draft.hard(x + dx, y, z + dz, room ? AIR : STONE);
                        }
                    }
                }
                target = new BlockPos(x, 2, z);
            }
            case "pillar" -> {
                // A stone column standing h above the ground, three clear cells of air all round it to the top.
                int r = 3;
                if (!draft.clear(x - r, 0, z - r, x + r, TOP, z + r)) {
                    return null;
                }
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        draft.hard(x + dx, 0, z + dz, STONE);
                        for (int y = 1; y <= TOP; y++) {
                            boolean column = dx == 0 && dz == 0 && y <= h;
                            draft.hard(x + dx, y, z + dz, column ? STONE : AIR);
                        }
                    }
                }
                target = new BlockPos(x, h + 1, z);
            }
            case "island" -> {
                // A three-wide island at ground level in a moat three wide, its floor far below, air over it all.
                int r = 4;
                if (!draft.clear(x - r, BOTTOM, z - r, x + r, TOP, z + r)) {
                    return null;
                }
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        boolean land = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                        draft.hard(x + dx, BOTTOM, z + dz, STONE);
                        for (int y = BOTTOM + 1; y <= TOP; y++) {
                            draft.hard(x + dx, y, z + dz, land && y <= 0 ? STONE : AIR);
                        }
                    }
                }
                target = new BlockPos(x, 1, z);
            }
            case "cage" -> {
                // A room walled and roofed in stone bricks whose only way in is an iron door, shut.
                if (!draft.clear(x - 2, 0, z - 2, x + 2, 5, z + 2)) {
                    return null;
                }
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                        draft.hard(x + dx, 0, z + dz, STONE);
                        for (int y = 1; y <= 3; y++) {
                            draft.hard(x + dx, y, z + dz, wall ? BRICKS : AIR);
                        }
                        draft.hard(x + dx, 4, z + dz, BRICKS);
                    }
                }
                BlockState iron = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                    .setValue(DoorBlock.OPEN, false);
                draft.pending.put(BlockPos.asLong(x, 1, z - 2),
                    new Slot(iron.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), true));
                draft.pending.put(BlockPos.asLong(x, 2, z - 2),
                    new Slot(iron.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), true));
                target = new BlockPos(x, 1, z);
            }
            default -> {
                return null;
            }
        }
        if (draft.bad) {
            return null;
        }
        Pocket pocket = new Pocket(id, kind, target);
        draft.commit(pocket.cells);
        pockets.put(id, pocket);
        return pocket;
    }

    // ---- lanes ---------------------------------------------------------------------------------------------------

    /** Where a lane stands after each piece: its last cell, the way it faces, and the headroom it has there. */
    private record At(int x, int y, int z, Direction facing, int clear) {
        At next(int x, int y, int z) {
            return new At(x, y, z, facing, 4);
        }
    }

    private static final List<String> PIECES = List.of("run", "run", "run", "run", "run", "turn", "turn", "turn",
        "turn", "stairs", "stairs", "slab", "ladder", "ladder", "vine", "door", "gate", "low", "bridge", "bridge");

    /** What a pinned piece asks for beyond its kind: "up" or "down" for a climb, "left" or "right" for a turn. */
    private String way = "";

    /**
     * Grows lane {@code id} from {@code origin}'s wall, piece by piece from {@code seed}, at most {@code count}
     * pieces; answers it, or null if it could not leave the wall. {@code pinned}, when not empty, names the pieces
     * in order ("run", "vine up", "turn left"...), the seed only choosing their lengths and looks.
     */
    Lane grow(int id, int seed, int count, Lane origin, Yard from, List<String> pinned) {
        Rng rng = new Rng(Rng.mix(seed, 0x5eed));
        Lane lane = new Lane(id, origin.from.equals("yard") ? "yard" : "hub");
        At at = null;
        for (int attempt = 0; attempt < 16 && at == null; attempt++) {
            at = leave(lane, rng, from);
        }
        if (at == null) {
            return null;
        }
        int pieces = Math.clamp(count, 1, 64);
        if (!pinned.isEmpty()) {
            pieces = Math.min(pieces, pinned.size());
        }
        for (int piece = 0; piece < pieces; piece++) {
            At next = null;
            for (int attempt = 0; attempt < 10 && next == null; attempt++) {
                String kind = at.clear() < 4 ? "run" : rng.pick(PIECES);
                way = "";
                if (!pinned.isEmpty()) {
                    String[] words = pinned.get(piece).trim().split("\\s+");
                    kind = words[0];
                    way = words.length > 1 ? words[1] : "";
                }
                Draft draft = new Draft();
                StringBuilder name = new StringBuilder(kind);
                At tried = piece(draft, kind, at, rng, name);
                if (tried != null && !draft.bad && !draft.steps.isEmpty()) {
                    draft.commit(lane.cells);
                    draft.steps.forEach(lane::add);
                    lane.pieces.add(name.toString());
                    next = tried;
                }
            }
            if (next == null) {
                break;
            }
            at = next;
        }
        lanes.put(id, lane);
        return lane;
    }

    /** The lane's first cell: a gap in the yard's wall, facing out. */
    private At leave(Lane lane, Rng rng, Yard yard) {
        int side = rng.below(4);
        Direction out = switch (side) {
            case 0 -> Direction.NORTH;
            case 1 -> Direction.SOUTH;
            case 2 -> Direction.EAST;
            default -> Direction.WEST;
        };
        if (yard.openWest() && out == Direction.WEST) {
            return null;
        }
        int x;
        int z;
        if (out.getAxis() == Direction.Axis.Z) {
            if (yard.x1() - yard.x0() < 2) {
                return null;
            }
            x = rng.between(yard.x0() + 1, yard.x1() - 1);
            z = out == Direction.NORTH ? yard.z0() : yard.z1();
        } else {
            if (yard.z1() - yard.z0() < 2) {
                return null;
            }
            z = rng.between(yard.z0() + 1, yard.z1() - 1);
            x = out == Direction.WEST ? yard.x0() : yard.x1();
        }
        // The cell inside must be walked ground of the yard, not a bed or the chest.
        int ix = x - out.getStepX();
        int iz = z - out.getStepZ();
        Lane home = yard == hubYard ? hub : this.yard;
        if (home == null || !home.walked.contains(BlockPos.asLong(ix, 1, iz))) {
            return null;
        }
        Draft draft = new Draft();
        draft.walk(x, 1, z, STONE, 4, null, Edge.WALL, 5, false);
        if (draft.bad) {
            return null;
        }
        draft.commit(lane.cells);
        draft.steps.forEach(lane::add);
        lane.pieces.add("out " + out.getName());
        return new At(x, 1, z, out, 4);
    }

    private At piece(Draft draft, String kind, At at, Rng rng, StringBuilder name) {
        Direction f = at.facing();
        Edge edge = rng.chance(0.5) ? Edge.FENCE : Edge.WALL;
        BlockState floor = rng.pick(FLOORS);
        switch (kind) {
            case "turn", "run", "low", "bridge" -> {
                if (kind.equals("turn")) {
                    boolean right = way.equals("right") || !way.equals("left") && rng.chance(0.5);
                    f = right ? f.getClockWise() : f.getCounterClockWise();
                }
                int n = kind.equals("turn") ? rng.between(1, 3) : rng.between(2, 8);
                boolean bridge = kind.equals("bridge");
                if (bridge && at.y() != 1) {
                    return null;
                }
                BlockState ceiling = kind.equals("low") ? (rng.chance(0.5) ? STONE : Blocks.OAK_TRAPDOOR
                    .defaultBlockState().setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                    .setValue(TrapDoorBlock.OPEN, false)) : null;
                int x = at.x();
                int z = at.z();
                for (int i = 0; i < n; i++) {
                    x += f.getStepX();
                    z += f.getStepZ();
                    if (bridge) {
                        draft.walk(x, 1, z, PLANKS, 4, null, Edge.RAIL, 5, true);
                        pit(draft, x, z, f);
                    } else {
                        draft.walk(x, at.y(), z, floor, ceiling != null ? 2 : 4, ceiling, edge, ceiling != null ? 6 : 5,
                            true);
                    }
                }
                name.append(' ').append(n).append(' ').append(f.getName());
                return new At(x, at.y(), z, f, ceiling != null ? 2 : 4);
            }
            case "stairs" -> {
                boolean up = climbUp(rng, at);
                int k = rng.between(1, 4);
                if (up ? at.y() + k > HIGHEST : at.y() - k < LOWEST) {
                    return null;
                }
                BlockState stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.BOTTOM)
                    .setValue(StairBlock.FACING, up ? f : f.getOpposite());
                int x = at.x();
                int z = at.z();
                for (int i = 1; i <= k; i++) {
                    x += f.getStepX();
                    z += f.getStepZ();
                    int feet = up ? at.y() + i : at.y() - i + 1;
                    draft.walk(x, feet, z, stair, 4, null, edge, 7, false);
                }
                x += f.getStepX();
                z += f.getStepZ();
                int landing = up ? at.y() + k : at.y() - k;
                draft.walk(x, landing, z, floor, 4, null, edge, 5, true);
                name.append(up ? " up " : " down ").append(k).append(' ').append(f.getName());
                return new At(x, landing, z, f, 4);
            }
            case "slab" -> {
                boolean up = climbUp(rng, at);
                if (up ? at.y() + 1 > HIGHEST : at.y() - 1 < LOWEST) {
                    return null;
                }
                BlockState slab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
                int x = at.x() + f.getStepX();
                int z = at.z() + f.getStepZ();
                // The slab lies in the lower half of the cell its walker's feet are in: at.y up, one lower down.
                int cell = up ? at.y() : at.y() - 1;
                draft.hard(x, cell, z, slab);
                draft.hard(x, cell - 1, z, STONE);
                for (int k = 1; k <= 4; k++) {
                    draft.hard(x, cell + k, z, AIR);
                }
                draft.edges(x, cell, z, edge, 5);
                draft.steps.add(new Step(x, cell, z, 6, false));
                x += f.getStepX();
                z += f.getStepZ();
                int landing = up ? at.y() + 1 : at.y() - 1;
                draft.walk(x, landing, z, floor, 4, null, edge, 5, true);
                name.append(up ? " up " : " down ").append(f.getName());
                return new At(x, landing, z, f, 4);
            }
            case "ladder", "vine" -> {
                boolean up = climbUp(rng, at);
                int k = rng.between(2, 7);
                if (up ? at.y() + k > HIGHEST : at.y() - k < LOWEST) {
                    return null;
                }
                int cx = at.x() + f.getStepX();
                int cz = at.z() + f.getStepZ();
                int bx = cx + f.getStepX();
                int bz = cz + f.getStepZ();
                // Up, the climb hangs on the column ahead and lands on top of it; down, it hangs on the column the
                // lane stood on and lands ahead at the foot.
                Direction toWall = up ? f : f.getOpposite();
                BlockState climb = kind.equals("ladder")
                    ? Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, toWall.getOpposite())
                    : Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(toWall), true);
                int wallX = up ? bx : at.x();
                int wallZ = up ? bz : at.z();
                int low = up ? at.y() : at.y() - k;
                int high = up ? at.y() + k : at.y();
                draft.hard(cx, low - 1, cz, STONE);
                for (int y = low; y < high; y++) {
                    draft.hard(cx, y, cz, climb);
                    draft.hard(wallX, y, wallZ, STONE);
                }
                for (int y = high; y <= high + 3; y++) {
                    draft.hard(cx, y, cz, AIR);
                }
                // Steps in the order they are climbed: up the rungs to the top, or from the top down them.
                for (int i = 0; i <= k; i++) {
                    int y = up ? low + i : high - i;
                    draft.steps.add(new Step(cx, y, cz, 10, false));
                }
                Direction across = f.getClockWise();
                for (int y = low - 1; y <= high + 2; y++) {
                    draft.soft(cx + across.getStepX(), y, cz + across.getStepZ(), BRICKS);
                    draft.soft(cx - across.getStepX(), y, cz - across.getStepZ(), BRICKS);
                }
                int landing = up ? high : low;
                draft.walk(bx, landing, bz, STONE, 4, null, edge, 5, true);
                name.append(up ? " up " : " down ").append(k).append(' ').append(f.getName());
                return new At(bx, landing, bz, f, 4);
            }
            case "door", "gate" -> {
                int x = at.x() + f.getStepX();
                int z = at.z() + f.getStepZ();
                boolean open = rng.chance(0.4);
                if (kind.equals("door")) {
                    BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, f)
                        .setValue(DoorBlock.OPEN, open)
                        .setValue(DoorBlock.HINGE, rng.chance(0.5) ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT);
                    draft.hard(x, at.y() - 1, z, STONE);
                    draft.hard(x, at.y(), z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
                    draft.hard(x, at.y() + 1, z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
                    draft.hard(x, at.y() + 2, z, BRICKS);
                    draft.edges(x, at.y(), z, Edge.WALL, 4);
                    draft.steps.add(new Step(x, at.y(), z, 20, false));
                } else {
                    BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, f)
                        .setValue(FenceGateBlock.OPEN, open);
                    draft.hard(x, at.y() - 1, z, STONE);
                    draft.hard(x, at.y(), z, gate);
                    for (int k = 1; k <= 3; k++) {
                        draft.hard(x, at.y() + k, z, AIR);
                    }
                    draft.edges(x, at.y(), z, Edge.FENCE, 4);
                    draft.steps.add(new Step(x, at.y(), z, 12, false));
                }
                name.append(open ? " open " : " shut ").append(f.getName());
                return new At(x, at.y(), z, f, 2);
            }
            default -> {
                return null;
            }
        }
    }

    private boolean climbUp(Rng rng, At at) {
        if (way.equals("up") || way.equals("down")) {
            return way.equals("up");
        }
        if (at.y() <= 1) {
            return rng.chance(0.75);
        }
        return rng.chance(0.5);
    }

    /** The pit under a bridge cell: air down to the field's floor under it and two cells either side. */
    private void pit(Draft draft, int x, int z, Direction f) {
        Direction across = f.getClockWise();
        for (int off = -2; off <= 2; off++) {
            int px = x + across.getStepX() * off;
            int pz = z + across.getStepZ() * off;
            for (int y = BOTTOM + 1; y <= (Math.abs(off) == 2 ? 1 : -1); y++) {
                draft.soft(px, y, pz, AIR);
            }
        }
    }

    // ---- reading cases ---------------------------------------------------------------------------------------------

    static int seed(Map<String, Object> from) {
        return Cases.num(from, "seed", 0);
    }

    /** Whether {@code actual} is the block a lane laid as {@code expected}: doors may stand open or shut. */
    static boolean same(BlockState expected, BlockState actual) {
        if (expected == actual) {
            return true;
        }
        if (expected.getBlock() != actual.getBlock()) {
            return false;
        }
        for (var property : expected.getProperties()) {
            String name = property.getName();
            if (name.equals("open") || name.equals("powered") || name.equals("in_wall")) {
                continue;
            }
            if (!expected.getValue(property).equals(actual.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    static boolean connects(Block block) {
        return block == Blocks.OAK_FENCE || block == Blocks.STONE_BRICK_WALL || block == Blocks.COBBLESTONE_WALL
            || block == Blocks.IRON_BARS;
    }
}
