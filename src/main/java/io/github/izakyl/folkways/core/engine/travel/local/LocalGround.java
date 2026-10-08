package io.github.izakyl.folkways.core.engine.travel.local;

import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.terms.Reach;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

final class LocalGround implements Ground, LevelReader {

    private static final int STANCES_PER_TARGET = 16;

    private static final int MOST_REACHED = 1 << 20;

    private final LocalWatch watch;
    private final List<Walker> walkers;
    private final List<Mob> bodies;
    private final Map<BlockPos, BlockState> changes;
    private final LongOpenHashSet changedSections = new LongOpenHashSet();
    private final Map<Walker.Kind, Long2ObjectOpenHashMap<Section>> trial = new HashMap<>();
    private final Long2ObjectOpenHashMap<List<BlockPos>> stances = new Long2ObjectOpenHashMap<>();
    @Nullable
    private LongOpenHashSet reached;

    LocalGround(LocalWatch watch, List<Walker> walkers, List<Mob> bodies, Map<BlockPos, BlockState> changes) {
        this.watch = watch;
        this.walkers = walkers;
        this.bodies = bodies;
        this.changes = changes;
        changes.keySet().forEach(cell -> Section.touchedBy(cell, changedSections));
    }

    @Override
    public boolean known(BlockPos cell) {
        return watch.covers(cell.getX(), cell.getY(), cell.getZ()) && watch.level().isLoaded(cell);
    }

    @Override
    public BlockState state(BlockPos cell) {
        return getBlockState(cell);
    }

    @Override
    public LevelReader level() {
        return this;
    }

    @Override
    public boolean standable(BlockPos feet) {
        long cell = feet.asLong();
        for (Walker walker : walkers) {
            if (section(walker, Section.keyOf(cell)).stands().contains(cell)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean reachable(BlockPos feet) {
        return reached().contains(feet.asLong());
    }

    @Override
    public List<BlockPos> stancesFor(BlockPos target) {
        List<BlockPos> known = stances.get(target.asLong());
        if (known == null) {
            known = findStances(target);
            stances.put(target.asLong(), known);
        }
        return known;
    }

    private List<BlockPos> findStances(BlockPos target) {
        List<BlockPos> found = new ArrayList<>();
        int span = Mth.ceil(Reach.PLAYER_BLOCK_REACH);
        for (BlockPos feet : BlockPos.betweenClosed(target.offset(-span, -span, -span), target.offset(span, span, span))) {
            if (!feet.equals(target) && reachable(feet) && Reach.withinReach(feet, target) && inSight(feet, target)) {
                found.add(feet.immutable());
            }
        }
        found.sort(Comparator.<BlockPos>comparingDouble(feet -> feet.distSqr(target))
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ));
        return found.size() <= STANCES_PER_TARGET ? List.copyOf(found) : List.copyOf(found.subList(0, STANCES_PER_TARGET));
    }

    @Override
    public Collection<BlockPos> lostSince(Ground earlier) {
        List<BlockPos> lost = new ArrayList<>();
        LongOpenHashSet now = reached();
        ((LocalGround) earlier).reached().forEach((long cell) -> {
            if (!now.contains(cell)) {
                lost.add(BlockPos.of(cell));
            }
        });
        return lost;
    }

    @Override
    public Ground with(Map<BlockPos, BlockState> more) {
        if (more.isEmpty()) {
            return this;
        }
        Map<BlockPos, BlockState> merged = new HashMap<>(changes);
        more.forEach((cell, state) -> merged.put(cell.immutable(), state));
        return new LocalGround(watch, walkers, bodies, merged);
    }

    private Section section(Walker walker, long key) {
        if (!changedSections.contains(key)) {
            return watch.section(walker, key);
        }
        Long2ObjectOpenHashMap<Section> sections =
            trial.computeIfAbsent(walker.kind(), kind -> new Long2ObjectOpenHashMap<>());
        Section known = sections.get(key);
        if (known == null) {
            known = Survey.of(watch.level(), key, walker, changes);
            sections.put(key, known);
        }
        return known;
    }

    private LongOpenHashSet reached() {
        if (reached == null) {
            reached = new LongOpenHashSet();
            for (Walker walker : walkers) {
                walk(walker, reached);
            }
        }
        return reached;
    }

    private void walk(Walker walker, LongOpenHashSet into) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue open = new LongArrayFIFOQueue();
        for (long entry : entries(walker)) {
            if (seen.add(entry)) {
                open.enqueue(entry);
            }
        }
        while (!open.isEmpty() && seen.size() < MOST_REACHED) {
            long cell = open.dequeueLong();
            long[] steps = section(walker, Section.keyOf(cell)).steps().get(cell);
            if (steps == null) {
                continue;
            }
            for (long next : steps) {
                if (seen.contains(next) || !watch.covers(BlockPos.getX(next), BlockPos.getY(next), BlockPos.getZ(next))) {
                    continue;
                }
                Section there = section(walker, Section.keyOf(next));
                if (there.stands().contains(next) && there.stepsTo(next, cell)) {
                    seen.add(next);
                    open.enqueue(next);
                }
            }
        }
        into.addAll(seen);
    }

    private LongOpenHashSet entries(Walker walker) {
        LongOpenHashSet entries = new LongOpenHashSet();
        for (Mob body : bodies) {
            BlockPos feet = body.blockPosition();
            for (int dy = -1; dy <= 1; dy++) {
                offerEntry(walker, feet.getX(), feet.getY() + dy, feet.getZ(), entries);
            }
        }
        BlockPos min = watch.min();
        BlockPos max = watch.max();
        for (int x = min.getX(); x <= max.getX(); x++) {
            offerSurface(walker, x, min.getZ(), entries);
            offerSurface(walker, x, max.getZ(), entries);
        }
        for (int z = min.getZ() + 1; z < max.getZ(); z++) {
            offerSurface(walker, min.getX(), z, entries);
            offerSurface(walker, max.getX(), z, entries);
        }
        return entries;
    }

    private void offerSurface(Walker walker, int x, int z, LongOpenHashSet entries) {
        ServerLevel level = watch.level();
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return;
        }
        offerEntry(walker, x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z, entries);
    }

    private void offerEntry(Walker walker, int x, int y, int z, LongOpenHashSet entries) {
        if (!watch.covers(x, y, z)) {
            return;
        }
        long cell = BlockPos.asLong(x, y, z);
        if (section(walker, Section.keyOf(cell)).stands().contains(cell)) {
            entries.add(cell);
        }
    }

    private boolean inSight(BlockPos feet, BlockPos target) {
        if (Math.abs(feet.getX() - target.getX()) <= 1 && Math.abs(feet.getY() - target.getY()) <= 1
            && Math.abs(feet.getZ() - target.getZ()) <= 1) {
            return true;
        }
        Vec3 eye = Vec3.atBottomCenterOf(feet).add(0.0D, Reach.EYE_HEIGHT, 0.0D);
        BlockHitResult hit = clip(new ClipContext(eye, Vec3.atCenterOf(target), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState changed = changes.get(pos);
        return changed != null ? changed : watch.level().getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return changes.containsKey(pos) ? null : watch.level().getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return watch.level().getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return watch.level().getMinBuildHeight();
    }
    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
        return watch.level().getChunk(x, z, status, false);
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return watch.level().hasChunk(x, z);
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return watch.level().getHeight(type, x, z);
    }

    @Override
    public int getSkyDarken() {
        return watch.level().getSkyDarken();
    }

    @Override
    public BiomeManager getBiomeManager() {
        return watch.level().getBiomeManager();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return watch.level().getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public int getSeaLevel() {
        return watch.level().getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return watch.level().dimensionType();
    }

    @Override
    public RegistryAccess registryAccess() {
        return watch.level().registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return watch.level().enabledFeatures();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return watch.level().getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return watch.level().getLightEngine();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return watch.level().getWorldBorder();
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB bounds) {
        return List.of();
    }
}

