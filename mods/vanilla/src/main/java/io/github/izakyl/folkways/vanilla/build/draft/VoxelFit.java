package io.github.izakyl.folkways.vanilla.build.draft;

import io.github.izakyl.folkways.core.work.api.Voxelizer;
import io.github.izakyl.folkways.core.world.api.StructurePayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class VoxelFit {

    record Layer(int part, BitSet mask) {}

    record Cell(BlockState state, StructurePayload payload) {}

    private final Voxelizer encoder;
    private final int size;
    private final int grid;
    private final int stride;
    private final Map<BlockState, Boolean> supported = new HashMap<>();
    private final Map<Long, List<Layer>> cells = new HashMap<>();
    private final BitSet full;

    VoxelFit(Voxelizer encoder) {
        this(encoder, Pattern.DEFAULT_GRAIN);
    }

    VoxelFit(Voxelizer encoder, int grain) {
        this.encoder = encoder;
        size = encoder.resolution();
        if (size < 1 || size > 32) {
            throw new IllegalArgumentException("Unsupported voxel resolution: " + size);
        }
        // The largest divisor keeps coarse cells aligned while retaining the encoder's storage resolution.
        int fitted = Math.min(size, grain);
        while (fitted > 1 && size % fitted != 0) {
            fitted--;
        }
        grid = fitted;
        stride = size / grid;
        full = new BitSet(size * size * size);
        full.set(0, size * size * size);
    }

    boolean paint(List<Massing.Part> parts) {
        for (int index = 0; index < parts.size(); index++) {
            Massing.Part part = parts.get(index);
            if (!(part.skin() instanceof Skin.Fitted fitted) || fitted.fit() == Fit.CONNECTED) {
                continue;
            }
            var box = part.solid().bounds();
            if (!paint(part.solid(), index, box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ())) {
                return false;
            }
        }
        return true;
    }

    private boolean paint(Solid solid, int part, int x0, int y0, int z0, int x1, int y1, int z1) {
        Drafting.checkTime();
        double inset = 0.5D / grid;
        double last = (grid - 0.5D) / grid;
        int coverage = solid.coverage(new Solid.SampleBox(x0 + inset, y0 + inset, z0 + inset,
            x1 + last, y1 + last, z1 + last));
        if (coverage == 0) {
            return true;
        }
        if (coverage == 1 || x0 == x1 && y0 == y1 && z0 == z1) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        Drafting.checkTime();
                        // Full masks are shared read-only across cells. Only boundary cells need voxel sampling.
                        BitSet mask = coverage == 1 ? full : sample(solid, x, y, z);
                        if (!mask.isEmpty()) {
                            cells.computeIfAbsent(BlockPos.asLong(x, y, z), ignored -> new ArrayList<>())
                                .add(new Layer(part, mask));
                            if (cells.size() > Lattice.maxCells()) {
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        }
        // Split the longest block interval so that proven interiors reuse one decision across many cells.
        if (x1 - x0 >= y1 - y0 && x1 - x0 >= z1 - z0) {
            int mid = x0 + (x1 - x0) / 2;
            return paint(solid, part, x0, y0, z0, mid, y1, z1)
                && paint(solid, part, mid + 1, y0, z0, x1, y1, z1);
        }
        if (y1 - y0 >= z1 - z0) {
            int mid = y0 + (y1 - y0) / 2;
            return paint(solid, part, x0, y0, z0, x1, mid, z1)
                && paint(solid, part, x0, mid + 1, z0, x1, y1, z1);
        }
        int mid = z0 + (z1 - z0) / 2;
        return paint(solid, part, x0, y0, z0, x1, y1, mid)
            && paint(solid, part, x0, y0, mid + 1, x1, y1, z1);
    }

    Map<Long, List<Layer>> cells() {
        return cells;
    }

    Cell fit(List<Massing.Part> parts, Map<Integer, Palette> palettes, BlockPos pos, long hash, int legacy) {
        List<Layer> layers = cells.get(pos.asLong());
        if (!(parts.get(legacy).skin() instanceof Skin.Fitted)) {
            return null;
        }
        if (layers == null) {
            return new Cell(Blocks.AIR.defaultBlockState(), StructurePayload.EMPTY);
        }
        int count = size * size * size;
        if (parts.size() == 1 && layers.getFirst().mask().cardinality() == count) {
            return null;
        }
        int[] owners = new int[count];
        Arrays.fill(owners, -1);
        int layer = 0;
        for (int index = 0; index < parts.size(); index++) {
            Massing.Part part = parts.get(index);
            BitSet mask;
            if (layer < layers.size() && layers.get(layer).part() == index) {
                mask = layers.get(layer++).mask();
            } else if (!(part.skin() instanceof Skin.Fitted) && Octants.of(part.solid(),
                    pos.getX(), pos.getY(), pos.getZ()) != Octants.NONE) {
                mask = new BitSet(count);
                mask.set(0, count);
            } else {
                continue;
            }
            for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1)) {
                if (owners[bit] < 0 || part.over().takes(parts.get(owners[bit]))) {
                    owners[bit] = index;
                }
            }
        }
        BlockState[] materials = new BlockState[count];
        Map<Integer, BlockState> chosen = new HashMap<>();
        BlockState first = null;
        boolean full = true;
        for (int bit = 0; bit < count; bit++) {
            int owner = owners[bit];
            if (owner < 0 || parts.get(owner).skin() instanceof Skin.Cleared) {
                full = false;
                continue;
            }
            if (!(parts.get(owner).skin() instanceof Skin.Fitted fitted)) {
                return null;
            }
            BlockState material = chosen.computeIfAbsent(owner,
                key -> palettes.get(key).nearest(Octants.FULL, fitted.axis(), hash));
            if (!supported.computeIfAbsent(material, encoder::supports)) {
                return null;
            }
            materials[bit] = material;
            if (first == null) {
                first = material;
            } else if (first != material) {
                full = false;
            }
        }
        if (first == null) {
            return new Cell(Blocks.AIR.defaultBlockState(), StructurePayload.EMPTY);
        }
        if (full) {
            return new Cell(first, StructurePayload.EMPTY);
        }
        Drafting.checkTime();
        return new Cell(encoder.carrier(), encoder.encode(materials));
    }

    private BitSet sample(Solid solid, int x, int y, int z) {
        BitSet mask = new BitSet(size * size * size);
        subdivide(solid, x, y, z, 0, 0, 0, grid, mask);
        return mask;
    }

    private void subdivide(Solid solid, int x, int y, int z, int bx, int by, int bz, int width, BitSet mask) {
        Drafting.checkTime();
        if (width == 1) {
            if (solid.holds(x + (bx + 0.5D) / grid, y + (by + 0.5D) / grid, z + (bz + 0.5D) / grid)) {
                fill(mask, bx, by, bz, 1);
            }
            return;
        }
        int coverage = solid.coverage(new Solid.SampleBox(x + (bx + 0.5D) / grid, y + (by + 0.5D) / grid,
            z + (bz + 0.5D) / grid, x + (bx + width - 0.5D) / grid,
            y + (by + width - 0.5D) / grid, z + (bz + width - 0.5D) / grid));
        if (coverage == 0) {
            return;
        }
        if (coverage == 1) {
            fill(mask, bx, by, bz, width);
            return;
        }
        if (width > 1 && width % 2 == 0) {
            int half = width / 2;
            for (int corner = 0; corner < 8; corner++) {
                subdivide(solid, x, y, z, bx + ((corner & 1) == 0 ? 0 : half),
                    by + ((corner & 2) == 0 ? 0 : half), bz + ((corner & 4) == 0 ? 0 : half), half, mask);
            }
            return;
        }
        for (int dx = bx; dx < bx + width; dx++) {
            for (int dy = by; dy < by + width; dy++) {
                for (int dz = bz; dz < bz + width; dz++) {
                    if (solid.holds(x + (dx + 0.5D) / grid, y + (dy + 0.5D) / grid, z + (dz + 0.5D) / grid)) {
                        fill(mask, dx, dy, dz, 1);
                    }
                }
            }
        }
    }

    private void fill(BitSet mask, int x, int y, int z, int width) {
        for (int dx = x * stride; dx < (x + width) * stride; dx++) {
            for (int dy = y * stride; dy < (y + width) * stride; dy++) {
                mask.set((dx * size + dy) * size + z * stride,
                    (dx * size + dy) * size + (z + width) * stride);
            }
        }
    }
}
