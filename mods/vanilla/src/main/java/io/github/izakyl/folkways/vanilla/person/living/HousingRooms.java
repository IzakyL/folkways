package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.world.api.Footing;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

// Shared by a colony: one bounded, incremental survey at a time, never a room scan per resident.
final class HousingRooms {
    enum Grade {
        PENDING(0), EXPOSED(-6), DORMITORY(2), COMFORTABLE(6), PRIVATE(10);
        final int mood;
        Grade(int mood) { this.mood = mood; }
        Component title() { return Component.translatable("folkways.housing." + name().toLowerCase(Locale.ROOT)); }
    }

    record Room(Grade grade, int area, int beds, int furnishings, boolean lit, boolean entrance,
                boolean mixed, String problem) {
        Component description() {
            return Component.translatable("folkways.housing.details", grade.title(), area, beds, furnishings);
        }
        Component advice() { return Component.translatable("folkways.housing.reason." + problem); }
    }

    record Cached(Room room, BlockPos anchor, Set<BlockPos> beds, Set<BlockPos> cells, Set<BlockPos> signs,
                  Set<BlockPos> attached, Vec3 center, long checked) { }
    private final Map<ResourceKey<Level>, Map<BlockPos, Cached>> rooms = new HashMap<>();
    // A cell can belong to more than one room, e.g. a barrel in a shared partition wall; each room counts it.
    private final Map<ResourceKey<Level>, Map<BlockPos, Set<Cached>>> interiors = new HashMap<>();
    private Survey survey;
    private static final Room PENDING = new Room(Grade.PENDING, 0, 0, 0, false, false, false, "pending");

    Room get(ServerLevel level, BlockPos bed) {
        Map<BlockPos, Cached> in = rooms.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        Cached cached = in.get(bed);
        if (cached != null && level.getGameTime() - cached.checked() < 600) {
            return cached.room();
        }
        if (survey == null && level.isLoaded(bed)) {
            survey = new Survey(level, bed);
        }
        return cached == null ? PENDING : cached.room();
    }

    List<Cached> surveyed(ServerLevel level) {
        Set<Cached> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        kept.addAll(rooms.getOrDefault(level.dimension(), Map.of()).values());
        return kept.stream()
            .filter(cached -> cached.room().grade() != Grade.PENDING)
            .sorted(Comparator.comparing(Cached::anchor)).toList();
    }

    Map<Cached, Set<BlockPos>> signsOf(ServerLevel level) {
        List<Cached> surveyed = surveyed(level);
        Map<BlockPos, Cached> owners = new HashMap<>();
        Map<Cached, Set<BlockPos>> signs = new LinkedHashMap<>();
        for (Cached room : surveyed) {
            signs.put(room, new HashSet<>());
            for (BlockPos at : room.signs()) { owners.putIfAbsent(at, room); }
        }
        Set<BlockPos> inside = Set.copyOf(owners.keySet());
        for (Cached room : surveyed) {
            for (BlockPos at : room.attached()) {
                if (inside.contains(at) || !level.isLoaded(at)) { continue; }
                BlockState state = level.getBlockState(at);
                Direction facing;
                if (state.getBlock() instanceof WallSignBlock) {
                    facing = state.getValue(WallSignBlock.FACING);
                } else if (state.getBlock() instanceof WallHangingSignBlock) {
                    facing = state.getValue(WallHangingSignBlock.FACING);
                } else {
                    continue;
                }
                Vec3 right = Vec3.atLowerCornerOf(facing.getOpposite().getClockWise().getNormal());
                Vec3 center = Vec3.atCenterOf(at);
                Comparator<Cached> order = Comparator.<Cached>comparingDouble(
                    candidate -> candidate.center().subtract(center).dot(right)).reversed()
                    .thenComparingDouble(candidate -> candidate.center().distanceToSqr(center)).thenComparing(Cached::anchor);
                Cached previous = owners.get(at);
                if (previous == null || order.compare(room, previous) < 0) { owners.put(at, room); }
            }
        }
        owners.forEach((at, room) -> signs.get(room).add(at));
        signs.replaceAll((room, owned) -> Set.copyOf(owned));
        return signs;
    }

    void tick() {
        if (survey == null || !survey.step(64)) {
            return;
        }
        Cached result = survey.result();
        Map<BlockPos, Cached> in = rooms.computeIfAbsent(survey.level.dimension(), ignored -> new HashMap<>());
        var inside = interiors.computeIfAbsent(survey.level.dimension(), ignored -> new HashMap<>());
        Cached previous = in.get(survey.anchor);
        if (previous != null) {
            leave(inside, previous);
            in.entrySet().removeIf(entry -> entry.getValue() == previous);
        }
        for (BlockPos bed : survey.beds) { in.put(bed, result); }
        in.put(survey.anchor, result);
        enter(inside, result);
        survey = null;
    }

    boolean comfortableAt(ServerLevel level, BlockPos at) {
        Set<Cached> by = interiors.getOrDefault(level.dimension(), Map.of()).getOrDefault(at, Set.of());
        return by.stream().anyMatch(cached -> cached.room().grade().ordinal() >= Grade.COMFORTABLE.ordinal());
    }

    private static void enter(Map<BlockPos, Set<Cached>> inside, Cached cached) {
        cached.cells().forEach(at -> inside.computeIfAbsent(at, ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
            .add(cached));
    }

    private static void leave(Map<BlockPos, Set<Cached>> inside, Cached cached) {
        cached.cells().forEach(at -> inside.computeIfPresent(at, (ignored, by) -> {
            by.remove(cached);
            return by.isEmpty() ? null : by;
        }));
    }

    void retain(ServerLevel level, Set<BlockPos> registered) {
        Map<BlockPos, Cached> in = rooms.get(level.dimension());
        if (in != null && in.keySet().retainAll(registered)) {
            var inside = interiors.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
            inside.clear();
            Set<Cached> kept = Collections.newSetFromMap(new IdentityHashMap<>());
            kept.addAll(in.values());
            kept.forEach(cached -> enter(inside, cached));
        }
    }

    void changed(ServerLevel level, BlockPos at) {
        var inside = interiors.get(level.dimension());
        Map<BlockPos, Cached> in = rooms.get(level.dimension());
        if (in != null) {
            Set<Cached> invalid = Collections.newSetFromMap(new IdentityHashMap<>());
            in.forEach((bed, cached) -> { if (near(bed, at)) { invalid.add(cached); } });
            if (inside != null) { invalid.forEach(cached -> leave(inside, cached)); }
            in.entrySet().removeIf(entry -> invalid.contains(entry.getValue()));
        }
        if (survey != null && survey.level == level && near(survey.anchor, at)) {
            survey = null;
        }
    }

    private static boolean near(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= 34 && Math.abs(a.getZ() - b.getZ()) <= 34
            && Math.abs(a.getY() - b.getY()) <= 14;
    }

    static Grade grade(int area, int beds, int furnishings, boolean lit, boolean entrance, boolean mixed) {
        if (beds < 1 || area < beds * 4 || !lit || !entrance) {
            return Grade.EXPOSED;
        }
        if (!mixed && beds <= 2 && area >= beds * 9 && furnishings >= 3) {
            return Grade.PRIVATE;
        }
        return beds <= 4 && area >= beds * 6 && furnishings >= 2 ? Grade.COMFORTABLE : Grade.DORMITORY;
    }

    private static final class Survey {
        final ServerLevel level;
        final BlockPos anchor;
        final ArrayDeque<BlockPos> open = new ArrayDeque<>();
        final Set<BlockPos> seen = new HashSet<>();
        final Set<BlockPos> cells = new HashSet<>();
        final Set<BlockPos> beds = new HashSet<>();
        final Set<BlockPos> signs = new HashSet<>();
        final Set<BlockPos> attached = new HashSet<>();
        final Set<String> furnishings = new HashSet<>();
        boolean exposed, pending, lit = true, entrance, mixed;

        Survey(ServerLevel level, BlockPos bed) {
            this.level = level;
            this.anchor = bed.immutable();
            open.add(anchor);
        }

        boolean step(int budget) {
            while (budget-- > 0 && !open.isEmpty()) {
                BlockPos at = open.removeFirst();
                if (!seen.add(at)) { continue; }
                if (!level.isLoaded(at) || !level.isLoaded(at.above(12))) { pending = true; open.clear(); break; }
                if (Math.abs(at.getX() - anchor.getX()) > 32 || Math.abs(at.getZ() - anchor.getZ()) > 32
                        || cells.size() >= 1024) { pending = true; open.clear(); break; }
                BlockState state = level.getBlockState(at);
                sign(at);
                if (state.getBlock() instanceof DoorBlock) {
                    Direction facing = state.getValue(DoorBlock.FACING);
                    entrance |= Footing.withFeetAt(level, at.relative(facing))
                        && Footing.withFeetAt(level, at.relative(facing.getOpposite()));
                    boundary(at);
                    continue;
                }
                collect(at, state);
                boolean furniture = furniture(state) || state.getBlock() instanceof BedBlock;
                if (furniture && Footing.nearlyFullHeight(state.getCollisionShape(level, at))) {
                    // Full-height furniture counts as room area but forms a boundary like a wall.
                    if (Footing.holdsAbove(level, at.below())) { cells.add(at); roof(at); }
                    boundary(at);
                    continue;
                }
                if (!furniture && !state.getCollisionShape(level, at).isEmpty()) { boundary(at); continue; }
                if (!furniture && !level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()) {
                    boundary(at); continue;
                }
                if (!Footing.holdsAbove(level, at.below())) {
                    // An open stairwell is a boundary between floors; an opening to the outdoors has no roof.
                    if (!roof(at)) { exposed = true; open.clear(); break; }
                    continue;
                }
                cells.add(at);
                if (!roof(at)) { exposed = true; open.clear(); break; }
                if (!furniture && level.getMaxLocalRawBrightness(at) < 8) { lit = false; }
                for (Direction side : Direction.Plane.HORIZONTAL) { open.add(at.relative(side)); }
            }
            return open.isEmpty();
        }

        boolean roof(BlockPos at) {
            for (int y = 1; y <= 12; y++) {
                BlockPos above = at.above(y);
                BlockState state = level.getBlockState(above);
                if (state.is(tag("lighting"))) { furnishings.add("lighting"); }
                // Small fixtures and signs hang below the roof.
                boolean fixture = state.is(tag("lighting"))
                    && !Footing.nearlyFullHeight(state.getCollisionShape(level, above));
                boolean sign = level.getBlockEntity(above) instanceof SignBlockEntity;
                if (y >= 2 && !state.is(BlockTags.LEAVES) && !state.is(Blocks.BARRIER)
                        && !fixture && !sign
                        && !state.getCollisionShape(level, above).isEmpty()) { return true; }
                if (sign) { signs.add(above.immutable()); }
            }
            return false;
        }

        void sign(BlockPos at) {
            if (level.getBlockEntity(at) instanceof SignBlockEntity) { signs.add(at.immutable()); }
        }

        void boundary(BlockPos at) {
            for (int y = 0; y <= 2; y++) {
                BlockPos support = at.above(y);
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbour = support.relative(side);
                    if (!level.isLoaded(neighbour)) { continue; }
                    BlockState state = level.getBlockState(neighbour);
                    if (state.getBlock() instanceof WallSignBlock
                            && state.getValue(WallSignBlock.FACING) == side) {
                        attached.add(neighbour);
                    } else if (state.getBlock() instanceof WallHangingSignBlock
                            && state.getValue(WallHangingSignBlock.FACING).getAxis() != side.getAxis()) {
                        // Hanging signs attach along their crossbar, on either side of their facing.
                        attached.add(neighbour);
                    }
                }
            }
        }

        void collect(BlockPos at, BlockState state) {
            for (String category : List.of("storage", "textile", "decoration", "table", "lighting")) {
                if (state.is(tag(category))) { furnishings.add(category); }
            }
            if (state.is(tag("production"))) { mixed = true; }
            if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) { beds.add(at); }
            String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            if (state.hasBlockEntity() && level.getBlockEntity(at) instanceof Container
                    && !path.contains("furnace") && !path.contains("smoker")) { furnishings.add("storage"); }
            if (path.contains("carpet")) { furnishings.add("textile"); }
            if (path.contains("potted_") || path.equals("bookshelf") || path.equals("chiseled_bookshelf")) { furnishings.add("decoration"); }
            if (path.contains("table") && !path.equals("crafting_table") && !path.contains("smithing")) { furnishings.add("table"); }
            if (path.equals("crafting_table") || path.contains("furnace") || path.equals("smoker")
                    || path.equals("stonecutter") || path.equals("loom") || path.equals("smithing_table")) { mixed = true; }
        }

        Cached result() {
            Grade grade = pending ? Grade.PENDING : exposed ? Grade.EXPOSED
                : HousingRooms.grade(cells.size(), beds.size(), furnishings.size(), lit, entrance, mixed);
            if (grade == Grade.PRIVATE && !furnishings.contains("decoration")) { grade = Grade.COMFORTABLE; }
            String reason = pending ? "pending" : exposed ? "roof" : !entrance ? "entrance" : !lit ? "light"
                : cells.size() < beds.size() * 4 ? "area" : grade == Grade.PRIVATE ? "complete"
                : mixed ? "mixed" : beds.size() > 2 ? "beds" : cells.size() < beds.size() * 9 ? "area" : "furnishings";
            Vec3 center = Vec3.ZERO;
            for (BlockPos at : cells) { center = center.add(Vec3.atCenterOf(at)); }
            center = cells.isEmpty() ? Vec3.atCenterOf(anchor) : center.scale(1.0 / cells.size());
            return new Cached(new Room(grade, cells.size(), beds.size(), furnishings.size(), lit, entrance, mixed, reason),
                anchor, Set.copyOf(beds), Set.copyOf(cells), Set.copyOf(signs), Set.copyOf(attached), center,
                level.getGameTime());
        }
    }

    private static TagKey<Block> tag(String category) {
        return TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "housing/" + category));
    }

    private static boolean furniture(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return state.is(tag("storage")) || state.is(tag("textile")) || state.is(tag("decoration"))
            || state.is(tag("table")) || state.is(tag("lighting")) || state.is(tag("production"))
            || state.is(BlockTags.WOOL_CARPETS) || path.contains("chest") || path.equals("barrel")
            || path.contains("potted_") || path.contains("table") || path.contains("furnace")
            || path.equals("smoker") || path.equals("stonecutter") || path.equals("loom");
    }
}
