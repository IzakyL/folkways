package io.github.izakyl.folkways.plugins.build.draft;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The part of the world a drawing may read: the chunks under what was marked and as many more around them as its
 * pattern captures, from the sub-chunk {@code lowest} to {@code highest} (section coordinates, both kept). A path
 * is followed chunk by chunk rather than boxed, so a long diagonal one does not take in the whole square it crosses.
 */
public record Area(LongSet chunks, int lowest, int highest) {

    public Area {
        chunks = LongSets.unmodifiable(new LongOpenHashSet(chunks));
    }

    public static Area around(Hint hint, Growth growth, Capture capture) {
        int reach = capture.around();
        LongSet under = new LongOpenHashSet();
        BlockPos anchor = hint.anchor();
        // A first round reads around the mark itself; later ones around all that has been drawn since.
        if (hint instanceof Hint.Path path && growth.round() == 0) {
            List<BlockPos> points = path.points();
            for (int at = 1; at < points.size(); at++) {
                trace(points.get(at - 1), points.get(at), under);
            }
        } else {
            BoundingBox drawn = growth.reach();
            box(SectionPos.blockToSectionCoord(anchor.getX() + drawn.minX()),
                SectionPos.blockToSectionCoord(anchor.getZ() + drawn.minZ()),
                SectionPos.blockToSectionCoord(anchor.getX() + drawn.maxX()),
                SectionPos.blockToSectionCoord(anchor.getZ() + drawn.maxZ()), under);
        }
        LongSet reached = new LongOpenHashSet(under);
        for (long chunk : under) {
            int x = ChunkPos.getX(chunk);
            int z = ChunkPos.getZ(chunk);
            box(x - reach, z - reach, x + reach, z + reach, reached);
        }
        BoundingBox drawn = growth.reach();
        int low = SectionPos.blockToSectionCoord(anchor.getY() + drawn.minY());
        int high = SectionPos.blockToSectionCoord(anchor.getY() + drawn.maxY());
        return new Area(reached, capture.down() == Capture.ALL ? Integer.MIN_VALUE : low - capture.down(),
            capture.up() == Capture.ALL ? Integer.MAX_VALUE : high + capture.up());
    }

    public boolean covers(int x, int y, int z) {
        int section = SectionPos.blockToSectionCoord(y);
        return section >= lowest && section <= highest && chunks.contains(
            ChunkPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)));
    }

    public boolean coversColumn(int x, int z) {
        return chunks.contains(ChunkPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)));
    }

    /** The lowest cell it reaches, or Integer.MIN_VALUE when it reaches the bottom of the world. */
    public int bottom() {
        return lowest == Integer.MIN_VALUE ? Integer.MIN_VALUE : SectionPos.sectionToBlockCoord(lowest);
    }

    /** The highest cell it reaches, or Integer.MAX_VALUE when it reaches the top of the world. */
    public int top() {
        return highest == Integer.MAX_VALUE ? Integer.MAX_VALUE : SectionPos.sectionToBlockCoord(highest) + 15;
    }

    public int size() {
        return chunks.size();
    }

    private static void box(int minX, int minZ, int maxX, int maxZ, LongSet into) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                into.add(ChunkPos.asLong(x, z));
            }
        }
    }

    // Every chunk the straight line between two marks passes over, corners included.
    private static void trace(BlockPos from, BlockPos to, LongSet into) {
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int step = 0; step <= steps; step++) {
            double along = steps == 0 ? 0 : (double) step / steps;
            int x = SectionPos.blockToSectionCoord((int) Math.floor(from.getX() + (to.getX() - from.getX()) * along));
            int z = SectionPos.blockToSectionCoord((int) Math.floor(from.getZ() + (to.getZ() - from.getZ()) * along));
            if (x == lastX && z == lastZ) {
                continue;
            }
            // Stepping diagonally from one chunk to the next crosses a corner; both chunks beside it are kept.
            if (lastX != Integer.MIN_VALUE && x != lastX && z != lastZ) {
                into.add(ChunkPos.asLong(lastX, z));
                into.add(ChunkPos.asLong(x, lastZ));
            }
            into.add(ChunkPos.asLong(x, z));
            lastX = x;
            lastZ = z;
        }
    }
}
