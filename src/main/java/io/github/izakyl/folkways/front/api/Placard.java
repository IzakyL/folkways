package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.front.api.notice.Line;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;

public record Placard(UUID id, Outline outline, List<Line> lines) {

    public Placard {
        Objects.requireNonNull(id);
        Objects.requireNonNull(outline);
        lines = List.copyOf(lines);
    }

    public sealed interface Outline {

        record Box(BlockPos min, BlockPos max) implements Outline {
            public Box {
                BlockPos low = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()),
                    Math.min(min.getZ(), max.getZ()));
                max = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()),
                    Math.max(min.getZ(), max.getZ()));
                min = low;
            }
        }

        record Path(List<BlockPos> points) implements Outline {
            public Path {
                if (points.isEmpty()) {
                    throw new IllegalArgumentException("a path outline needs a point");
                }
                points = List.copyOf(points);
            }
        }
    }
}
