package io.github.izakyl.folkways.plugins.build;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
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
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The world as it would be with some cells drawn over, changed in place: a plan asks whether a block stays with one
 * neighbour taken away, and puts it back after, without copying every cell it has drawn.
 */
final class Sketch implements LevelReader {

    private final LevelReader world;
    private final Map<BlockPos, BlockState> drawn = new HashMap<>();

    Sketch(LevelReader world) {
        this.world = world;
    }

    void draw(BlockPos cell, BlockState state) {
        drawn.put(cell.immutable(), state);
    }

    void drawAll(Map<BlockPos, BlockState> cells) {
        cells.forEach(this::draw);
    }

    @Nullable
    BlockState lift(BlockPos cell) {
        return drawn.remove(cell);
    }

    void restore(BlockPos cell, @Nullable BlockState was) {
        if (was == null) {
            drawn.remove(cell);
        } else {
            drawn.put(cell.immutable(), was);
        }
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = drawn.get(pos);
        return state != null ? state : world.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return drawn.containsKey(pos) ? null : world.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return world.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return world.getMinBuildHeight();
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
        return world.getChunk(x, z, status, false);
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return world.hasChunk(x, z);
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return world.getHeight(type, x, z);
    }

    @Override
    public int getSkyDarken() {
        return world.getSkyDarken();
    }

    @Override
    public BiomeManager getBiomeManager() {
        return world.getBiomeManager();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return world.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return world.dimensionType();
    }

    @Override
    public RegistryAccess registryAccess() {
        return world.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return world.enabledFeatures();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return world.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return world.getLightEngine();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return world.getWorldBorder();
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB bounds) {
        return List.of();
    }
}
