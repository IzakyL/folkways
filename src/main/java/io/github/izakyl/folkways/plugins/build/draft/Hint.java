package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public sealed interface Hint {

    String ZONE = "zone";
    String PATH = "path";

    int MOST_POINTS = 4096;

    String TAG_KIND = "kind";
    String TAG_MIN = "min";
    String TAG_MAX = "max";
    String TAG_POINTS = "points";

    BlockPos anchor();

    String kind();

    BoundingBox bounds();

    default CompoundTag save() {
        return switch (this) {
            case Zone zone -> Writer.of().string(TAG_KIND, ZONE).blockPos(TAG_MIN, zone.min())
                .blockPos(TAG_MAX, zone.max()).tag();
            case Path path -> {
                int[] cells = new int[path.points().size() * 3];
                for (int at = 0; at < path.points().size(); at++) {
                    BlockPos point = path.points().get(at);
                    cells[at * 3] = point.getX();
                    cells[at * 3 + 1] = point.getY();
                    cells[at * 3 + 2] = point.getZ();
                }
                yield Writer.of().string(TAG_KIND, PATH).intArray(TAG_POINTS, cells).tag();
            }
        };
    }

    static Optional<Hint> load(CompoundTag saved) {
        Reader reader = Reader.of(saved);
        String kind = reader.string(TAG_KIND).orElse("");
        if (kind.equals(ZONE)) {
            Optional<BlockPos> min = reader.blockPos(TAG_MIN);
            Optional<BlockPos> max = reader.blockPos(TAG_MAX);
            return min.isEmpty() || max.isEmpty() ? Optional.empty() : Optional.of(new Zone(min.get(), max.get()));
        }
        if (kind.equals(PATH)) {
            int[] cells = reader.intArray(TAG_POINTS).orElse(new int[0]);
            if (cells.length % 3 != 0 || cells.length < 6 || cells.length > MOST_POINTS * 3) {
                return Optional.empty();
            }
            List<BlockPos> points = new ArrayList<>(cells.length / 3);
            for (int at = 0; at < cells.length; at += 3) {
                points.add(new BlockPos(cells[at], cells[at + 1], cells[at + 2]));
            }
            return Optional.of(new Path(points));
        }
        return Optional.empty();
    }

    record Zone(BlockPos min, BlockPos max) implements Hint {

        public Zone {
            BlockPos first = Objects.requireNonNull(min, "min");
            BlockPos second = Objects.requireNonNull(max, "max");
            min = new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()));
            max = new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ()));
        }

        @Override
        public BlockPos anchor() {
            return min;
        }

        @Override
        public String kind() {
            return ZONE;
        }

        @Override
        public BoundingBox bounds() {
            return new BoundingBox(0, 0, 0, max.getX() - min.getX(), max.getY() - min.getY(),
                max.getZ() - min.getZ());
        }
    }

    record Path(List<BlockPos> points) implements Hint {

        public Path {
            points = points.stream().map(BlockPos::immutable).toList();
            if (points.size() < 2) {
                throw new IllegalArgumentException("a path has at least two points");
            }
        }

        @Override
        public BlockPos anchor() {
            return points.get(0);
        }

        @Override
        public String kind() {
            return PATH;
        }

        @Override
        public BoundingBox bounds() {
            return BoundingBox.encapsulatingPositions(relative()).orElseThrow();
        }

        List<BlockPos> relative() {
            BlockPos anchor = anchor();
            return points.stream().map(point -> point.subtract(anchor)).toList();
        }
    }
}
