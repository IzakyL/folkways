package io.github.izakyl.folkways.plugins.build.draft;

import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;

public sealed interface Drawn {

    record Ready(BlockPos corner, Draft draft, Map<String, Double> reports) implements Drawn {

        public Ready {
            corner = corner.immutable();
            Objects.requireNonNull(draft, "draft");
            reports = Map.copyOf(reports);
        }

        public Ready(BlockPos corner, Draft draft) {
            this(corner, draft, Map.of());
        }

        Ready joining(Draft joined) {
            return new Ready(corner, joined, reports);
        }

        Ready counting(Map<String, Double> counted) {
            return counted.isEmpty() ? this : new Ready(corner, draft, counted);
        }
    }

    record Refused(DraftRefusal why, String detail) implements Drawn {

        public Refused {
            Objects.requireNonNull(why, "why");
            detail = detail == null ? "" : detail;
        }
    }

    static Drawn refused(DraftRefusal why) {
        return new Refused(why, "");
    }

    static Drawn refused(DraftRefusal why, String detail) {
        return new Refused(why, detail);
    }
}
