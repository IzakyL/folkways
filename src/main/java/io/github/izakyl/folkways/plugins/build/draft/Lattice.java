package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysConfig;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class Lattice {

    static final int UNCOVERED = -1;

    static final long MOST_SCANNED = 1L << 24;

    private static final int MASK_BITS = 8;
    private static final int MASK = (1 << MASK_BITS) - 1;

    private final BlockPos origin;
    private final List<Massing.Part> parts;
    private final Long2IntOpenHashMap covered;

    private Lattice(BlockPos origin, List<Massing.Part> parts, Long2IntOpenHashMap covered) {
        this.origin = origin;
        this.parts = parts;
        this.covered = covered;
    }

    static int maxCells() {
        return FolkwaysConfig.maxBuildCells();
    }

    static long volume(BoundingBox box) {
        return (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
    }

    public static Optional<Lattice> of(Massing massing) {
        List<Massing.Part> parts = massing.parts();
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        BoundingBox reach = null;
        long scanned = 0;
        for (Massing.Part part : parts) {
            BoundingBox box = part.solid().bounds();
            reach = reach == null ? box : Solid.union(reach, box);
            scanned += volume(box);
        }
        if (scanned > MOST_SCANNED || Extent.of(reach.getXSpan(), reach.getYSpan(), reach.getZSpan()).isEmpty()) {
            return Optional.empty();
        }
        Long2IntOpenHashMap covered = new Long2IntOpenHashMap();
        covered.defaultReturnValue(UNCOVERED);
        int most = maxCells();
        for (int index = 0; index < parts.size(); index++) {
            paint(parts, index, covered);
            if (covered.size() > most) {
                return Optional.empty();
            }
        }
        return Optional.of(new Lattice(new BlockPos(reach.minX(), reach.minY(), reach.minZ()), parts, covered));
    }

    private static void paint(List<Massing.Part> parts, int index, Long2IntOpenHashMap covered) {
        Massing.Part part = parts.get(index);
        if (part.skin() instanceof Skin.Fitted fitted && fitted.fit() == Fit.CONNECTED) {
            return;
        }
        BoundingBox box = part.solid().bounds();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    int mask = part.skin() instanceof Skin.Fitted fitted && fitted.fit() == Fit.LAYERED
                        ? Octants.layers(part.solid(), x, y, z) : Octants.of(part.solid(), x, y, z);
                    if (mask == Octants.NONE) {
                        continue;
                    }
                    long cell = BlockPos.asLong(x, y, z);
                    int held = covered.get(cell);
                    if (held != UNCOVERED) {
                        Massing.Part before = parts.get(held >>> MASK_BITS);
                        if (!part.over().takes(before)) {
                            continue;
                        }
                        if (part.skin() instanceof Skin.Fitted incoming && incoming.fit().partial()
                                && before.skin() instanceof Skin.Fitted existing && existing.fit().partial()) {
                            mask |= held & MASK;
                        }
                    }
                    covered.put(cell, index << MASK_BITS | mask);
                }
            }
        }
    }

    public BlockPos origin() {
        return origin;
    }

    public List<Massing.Part> parts() {
        return parts;
    }

    public void forEachCovered(Cell visitor) {
        long[] cells = covered.keySet().toLongArray();
        LongArrays.quickSort(cells, (a, b) -> {
            int byY = Integer.compare(BlockPos.getY(a), BlockPos.getY(b));
            if (byY != 0) {
                return byY;
            }
            int byZ = Integer.compare(BlockPos.getZ(a), BlockPos.getZ(b));
            return byZ != 0 ? byZ : Integer.compare(BlockPos.getX(a), BlockPos.getX(b));
        });
        for (long cell : cells) {
            int held = covered.get(cell);
            visitor.at(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell), held >>> MASK_BITS,
                held & MASK);
        }
    }

    @FunctionalInterface
    public interface Cell {

        void at(int x, int y, int z, int part, int octants);
    }
}
