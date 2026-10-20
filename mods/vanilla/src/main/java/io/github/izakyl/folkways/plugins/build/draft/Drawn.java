package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.front.api.notice.Notice;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

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

    record Refused(DraftRefusal why, String detail, Optional<Notice> explanation) implements Drawn {

        public Refused {
            Objects.requireNonNull(why, "why");
            detail = detail == null ? "" : detail;
            Objects.requireNonNull(explanation, "explanation");
        }

        public Refused(DraftRefusal why, String detail) {
            this(why, detail, Optional.empty());
        }

        public Component component() {
            return Component.translatable(why.translationKey(), explanation.map(PatternText::component)
                .orElseGet(() -> Component.literal(detail)));
        }
    }

    static Drawn refused(DraftRefusal why) {
        return new Refused(why, "");
    }

    static Drawn refused(DraftRefusal why, String detail) {
        return new Refused(why, detail);
    }
}
