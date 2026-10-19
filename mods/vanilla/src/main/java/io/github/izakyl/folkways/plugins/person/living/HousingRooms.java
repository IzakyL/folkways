package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.terms.Footing;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

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

    private record Cached(Room room, Set<BlockPos> cells, long checked) { }
    private final Map<ResourceKey<Level>, Map<BlockPos, Cached>> rooms = new HashMap<>();
    private final Map<ResourceKey<Level>, Map<BlockPos, Grade>> interiors = new HashMap<>();
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

    void tick() {
        if (survey == null || !survey.step(64)) {
            return;
        }
        Cached result = survey.result();
        Map<BlockPos, Cached> in = rooms.computeIfAbsent(survey.level.dimension(), ignored -> new HashMap<>());
        var inside = interiors.computeIfAbsent(survey.level.dimension(), ignored -> new HashMap<>());
        Cached previous = in.get(survey.anchor);
        if (previous != null) {
            previous.cells().forEach(inside::remove);
            in.entrySet().removeIf(entry -> entry.getValue() == previous);
        }
        for (BlockPos bed : survey.beds) { in.put(bed, result); }
        in.put(survey.anchor, result);
        result.cells().forEach(at -> inside.put(at, result.room().grade()));
        survey = null;
    }

    boolean comfortableAt(ServerLevel level, BlockPos at) {
        Grade grade = interiors.getOrDefault(level.dimension(), Map.of()).get(at);
        return grade != null && grade.ordinal() >= Grade.COMFORTABLE.ordinal();
    }

    void retain(ServerLevel level, Set<BlockPos> registered) {
        Map<BlockPos, Cached> in = rooms.get(level.dimension());
        if (in != null && in.keySet().retainAll(registered)) {
            var inside = interiors.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
            inside.clear();
            in.values().forEach(cached -> cached.cells().forEach(at -> inside.put(at, cached.room().grade())));
        }
    }

    void changed(ServerLevel level, BlockPos at) {
        var inside = interiors.get(level.dimension());
        Map<BlockPos, Cached> in = rooms.get(level.dimension());
        if (in != null) {
            Set<Cached> invalid = Collections.newSetFromMap(new IdentityHashMap<>());
            in.forEach((bed, cached) -> { if (near(bed, at)) { invalid.add(cached); } });
            if (inside != null) { invalid.forEach(cached -> cached.cells().forEach(inside::remove)); }
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
                if (state.getBlock() instanceof DoorBlock) {
                    Direction facing = state.getValue(DoorBlock.FACING);
                    entrance |= Footing.withFeetAt(level, at.relative(facing))
                        && Footing.withFeetAt(level, at.relative(facing.getOpposite()));
                    continue;
                }
                collect(at, state);
                boolean furniture = furniture(state) || state.getBlock() instanceof BedBlock;
                if (!furniture && !state.getCollisionShape(level, at).isEmpty()) { continue; }
                if (!furniture && !level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()) { continue; }
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
            for (int y = 2; y <= 12; y++) {
                BlockPos above = at.above(y);
                BlockState state = level.getBlockState(above);
                if (!state.is(BlockTags.LEAVES) && !state.is(Blocks.BARRIER)
                        && !state.getCollisionShape(level, above).isEmpty()) { return true; }
            }
            return false;
        }

        void collect(BlockPos at, BlockState state) {
            for (String category : List.of("storage", "textile", "decoration", "table")) {
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
            return new Cached(new Room(grade, cells.size(), beds.size(), furnishings.size(), lit, entrance, mixed, reason),
                Set.copyOf(cells), level.getGameTime());
        }
    }

    private static TagKey<Block> tag(String category) {
        return TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("folkways", "housing/" + category));
    }

    private static boolean furniture(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return state.is(tag("storage")) || state.is(tag("textile")) || state.is(tag("decoration"))
            || state.is(tag("table")) || state.is(tag("production")) || state.is(BlockTags.WOOL_CARPETS) || path.contains("chest") || path.equals("barrel")
            || path.contains("potted_") || path.contains("table") || path.contains("furnace")
            || path.equals("smoker") || path.equals("stonecutter") || path.equals("loom");
    }
}
