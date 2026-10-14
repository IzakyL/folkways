package io.github.izakyl.folkways.plugins.build.draft;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The chunks of an area as they stood when it was taken, to be drawn over on another thread while the world goes
 * on. A chunk that was not loaded then reads as unloaded, as it would have in the world.
 */
final class Captured implements World {

    private static final int SIDE = 16;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState VOID = Blocks.VOID_AIR.defaultBlockState();

    private final Long2ObjectOpenHashMap<Column> columns;
    private final int floor;
    private final int ceiling;

    private Captured(Long2ObjectOpenHashMap<Column> columns, int floor, int ceiling) {
        this.columns = columns;
        this.floor = floor;
        this.ceiling = ceiling;
    }

    /** Takes the area from the level; this runs on the level's own thread. */
    static Captured of(Level level, Area area) {
        Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>(area.size());
        for (long chunk : area.chunks()) {
            LevelChunk found = level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
            if (found != null) {
                columns.put(chunk, Column.of(found, area));
            }
        }
        return new Captured(columns, level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    @Override
    public Optional<BlockState> block(BlockPos at) {
        Column column = columns.get(ChunkPos.asLong(SectionPos.blockToSectionCoord(at.getX()),
            SectionPos.blockToSectionCoord(at.getZ())));
        if (column == null) {
            return Optional.empty();
        }
        if (at.getY() < floor || at.getY() >= ceiling) {
            return Optional.of(VOID);
        }
        PalettedContainer<BlockState> section = column.sections()[(at.getY() - floor) >> 4];
        return Optional.of(section == null ? AIR : section.get(at.getX() & 15, at.getY() & 15, at.getZ() & 15));
    }

    @Override
    public OptionalInt top(int x, int z) {
        Column column = columns.get(ChunkPos.asLong(SectionPos.blockToSectionCoord(x),
            SectionPos.blockToSectionCoord(z)));
        return column == null ? OptionalInt.empty() : OptionalInt.of(column.tops()[(z & 15) * SIDE + (x & 15)]);
    }

    @Override
    public int floor() {
        return floor;
    }

    // An all-air section is kept as null; the rest are copied, as the world may change them while they are read.
    // Sections outside the area are not taken: the drawing refuses to read them before it asks.
    private record Column(PalettedContainer<BlockState>[] sections, int[] tops) {

        @SuppressWarnings("unchecked")
        static Column of(LevelChunk chunk, Area area) {
            LevelChunkSection[] held = chunk.getSections();
            PalettedContainer<BlockState>[] sections = new PalettedContainer[held.length];
            int first = chunk.getMinSection();
            for (int at = 0; at < held.length; at++) {
                int section = first + at;
                if (section >= area.lowest() && section <= area.highest() && !held[at].hasOnlyAir()) {
                    sections[at] = held[at].getStates().copy();
                }
            }
            // As Level.getHeight answers: one above the highest cell that blocks motion.
            int[] tops = new int[SIDE * SIDE];
            for (int z = 0; z < SIDE; z++) {
                for (int x = 0; x < SIDE; x++) {
                    tops[z * SIDE + x] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
                }
            }
            return new Column(sections, tops);
        }
    }
}
