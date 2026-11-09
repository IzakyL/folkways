package io.github.izakyl.folkways.vanilla.build;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * The schematicannon printer's level, as far as it can be had without Create: an empty level holding only the
 * schematic, with dirt under its bottom layer and every furnace cold. Blocks reshape against one another as a
 * structure placement asks; nothing else happens in it.
 */
final class PrinterLevel implements ServerLevelAccessor, SchematicPlacement.Printed {

    private final ServerLevel level;
    private final BoundingBox bounds;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final Map<BlockPos, BlockEntity> entities = new HashMap<>();

    PrinterLevel(ServerLevel level, Vec3i size) {
        this.level = level;
        this.bounds = new BoundingBox(0, 0, 0, Math.max(0, size.getX() - 1), Math.max(0, size.getY() - 1),
            Math.max(0, size.getZ() - 1));
    }

    @Override
    public BlockState state(BlockPos pos) {
        return getBlockState(pos);
    }

    @Nullable
    @Override
    public BlockEntity entity(BlockPos pos) {
        return getBlockEntity(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (pos.getY() == bounds.minY() - 1) {
            return Blocks.DIRT.defaultBlockState();
        }
        BlockState state = bounds.isInside(pos) ? blocks.get(pos) : null;
        if (state == null) {
            return Blocks.AIR.defaultBlockState();
        }
        if (state.getBlock() instanceof AbstractFurnaceBlock && state.hasProperty(BlockStateProperties.LIT)) {
            return state.setValue(BlockStateProperties.LIT, false);
        }
        return state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        BlockEntity known = entities.get(pos);
        if (known != null) {
            return known;
        }
        BlockState state = blocks.get(pos);
        if (state == null || !state.hasBlockEntity() || !(state.getBlock() instanceof EntityBlock block)) {
            return null;
        }
        BlockEntity made = block.newBlockEntity(pos.immutable(), state);
        if (made != null) {
            entities.put(pos.immutable(), made);
        }
        return made;
    }

    @Override
    public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
        BlockEntity found = getBlockEntity(pos);
        return found != null && found.getType() == type ? Optional.of(type).map(known -> known.getBlockEntity(this, pos))
            : Optional.empty();
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
        BlockPos at = pos.immutable();
        blocks.put(at, state);
        BlockEntity entity = entities.get(at);
        if (entity != null && !entity.getType().isValid(state)) {
            entities.remove(at);
        }
        return true;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moving) {
        return setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity breaker, int recursion) {
        return setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> test) {
        return test.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> test) {
        return test.test(getFluidState(pos));
    }

    @Override
    public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos pos) {
        return pos;
    }

    @Override
    public ServerLevel getLevel() {
        return level;
    }

    @Override
    public long nextSubTickCount() {
        return 0L;
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelData getLevelData() {
        return level.getLevelData();
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return level.getCurrentDifficultyAt(pos);
    }

    @Nullable
    @Override
    public MinecraftServer getServer() {
        return level.getServer();
    }

    @Override
    public ChunkSource getChunkSource() {
        return level.getChunkSource();
    }

    @Override
    public RandomSource getRandom() {
        return level.getRandom();
    }

    @Override
    public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume,
            float pitch) {
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
    }

    @Override
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 at, GameEvent.Context context) {
    }

    @Override
    public long dayTime() {
        return level.dayTime();
    }

    @Override
    public List<Entity> getEntities(@Nullable Entity except, AABB bounds, Predicate<? super Entity> test) {
        return List.of();
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB bounds,
            Predicate<? super T> test) {
        return List.of();
    }

    @Override
    public List<? extends Player> players() {
        return List.of();
    }

    @Nullable
    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
        return null;
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return true;
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return getMinBuildHeight();
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public BiomeManager getBiomeManager() {
        return level.getBiomeManager();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return level.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public int getSeaLevel() {
        return level.getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return level.dimensionType();
    }

    @Override
    public RegistryAccess registryAccess() {
        return level.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return level.enabledFeatures();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1.0F;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return level.getLightEngine();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return level.getWorldBorder();
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return level.getMinBuildHeight();
    }
}
