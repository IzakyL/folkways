package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Value;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * When a facility runs one of its routines. Player facilities run manual, conditional and scheduled triggers and project workshops for demand.
 */
public sealed interface Trigger {

    /** The routine it runs, by the facility's name for it. */
    String routine();

    default Retry retry() {
        return Retry.DEFAULT;
    }

    record Retry(int retries, int backoff) {

        public static final Retry DEFAULT = new Retry(3, 20);
        public static final int MAX_RETRIES = 10;
        public static final int MAX_BACKOFF = 24_000;

        public Retry {
            if (retries < 0 || retries > MAX_RETRIES || backoff < 1 || backoff > MAX_BACKOFF) {
                throw new IllegalArgumentException("invalid trigger retry policy");
            }
        }

        public long delay(int retriesUsed) {
            return (long) backoff << retriesUsed;
        }
    }

    /** An explicit order: one run per order, with the order's arguments. */
    record Manual(String routine, Retry retry) implements Trigger {

        public Manual(String routine) {
            this(routine, Retry.DEFAULT);
        }

        public Manual {
            Objects.requireNonNull(routine, "routine");
            Objects.requireNonNull(retry, "retry");
        }
    }

    /**
     * Always on while the facility is enabled: the routine is run again whenever its last run ends, done or failed.
     */
    record Standing(String routine) implements Trigger {

        public Standing {
            Objects.requireNonNull(routine, "routine");
        }
    }

    /**
     * Pulled by the colony's needs: the facility offers what the routine makes and the plan runs it when it chooses
     * this facility, as it chooses a workshop. Processing and hauling only.
     */
    record Demand(String routine) implements Trigger {

        public Demand {
            Objects.requireNonNull(routine, "routine");
        }
    }

    /** Runs while all predicates agree. Hysteresis counts consecutive matching evaluations. */
    record Condition(String routine, Map<String, Value> when, Optional<ResourceLocation> source, int period, int cooldown,
                     int hysteresis, Optional<String> script, Retry retry) implements Trigger {

        public Condition(String routine, Map<String, Value> when, Optional<ResourceLocation> source, int period, int cooldown,
                         int hysteresis) {
            this(routine, when, source, period, cooldown, hysteresis, Optional.empty(), Retry.DEFAULT);
        }

        public Condition(String routine, Map<String, Value> when, Optional<ResourceLocation> source, int period, int cooldown,
                         int hysteresis, Optional<String> script) {
            this(routine, when, source, period, cooldown, hysteresis, script, Retry.DEFAULT);
        }

        public Condition {
            Objects.requireNonNull(routine, "routine");
            Objects.requireNonNull(retry, "retry");
            when = Map.copyOf(when);
            Objects.requireNonNull(script, "script");
            Objects.requireNonNull(source, "source");
            if ((source.isEmpty() && when.isEmpty() && script.isEmpty()) || period < 10 || cooldown < 0 || hysteresis < 1) {
                throw new IllegalArgumentException("invalid condition");
            }
        }
    }

    default boolean switchable() {
        return this instanceof Condition || this instanceof Schedule;
    }

    /** Runs once a day at this time of day, in ticks from dawn (0 to 23999). */
    record Schedule(String routine, int dayTime, Retry retry) implements Trigger {

        public Schedule(String routine, int dayTime) {
            this(routine, dayTime, Retry.DEFAULT);
        }

        public Schedule {
            Objects.requireNonNull(routine, "routine");
            Objects.requireNonNull(retry, "retry");
            if (dayTime < 0 || dayTime >= 24000) {
                throw new IllegalArgumentException("a time of day is within one day, not " + dayTime);
            }
        }
    }

    default CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("routine", routine());
        switch (this) {
            case Manual ignored -> tag.putString("kind", "manual");
            case Standing ignored -> tag.putString("kind", "standing");
            case Demand ignored -> tag.putString("kind", "demand");
            case Condition condition -> {
                tag.putString("kind", "condition");
                tag.put("when", Value.saveAll(condition.when()));
                condition.source().ifPresent(id -> tag.putString("source", id.toString()));
                condition.script().ifPresent(source -> tag.putString("script", source));
                tag.putInt("hysteresis", condition.hysteresis());
                tag.putInt("period", condition.period());
                tag.putInt("cooldown", condition.cooldown());
            }
            case Schedule schedule -> {
                tag.putString("kind", "schedule");
                tag.putInt("dayTime", schedule.dayTime());
            }
        }
        if (!retry().equals(Retry.DEFAULT)) {
            tag.putInt("retries", retry().retries());
            tag.putInt("backoff", retry().backoff());
        }
        return tag;
    }

    static Trigger load(CompoundTag tag) {
        String routine = tag.getString("routine");
        Retry retry = new Retry(tag.contains("retries") ? tag.getInt("retries") : Retry.DEFAULT.retries(),
            tag.contains("backoff") ? tag.getInt("backoff") : Retry.DEFAULT.backoff());
        return switch (tag.getString("kind")) {
            case "manual" -> new Manual(routine, retry);
            case "standing" -> new Standing(routine);
            case "demand" -> new Demand(routine);
            case "condition" -> new Condition(routine, Value.loadAll(tag.getCompound("when")),
                tag.contains("source") ? Optional.of(ResourceLocation.parse(tag.getString("source"))) : Optional.empty(),
                tag.getInt("period"), tag.getInt("cooldown"), tag.getInt("hysteresis"),
                tag.contains("script") ? Optional.of(tag.getString("script")) : Optional.empty(), retry);
            case "schedule" -> new Schedule(routine, tag.getInt("dayTime"), retry);
            default -> throw new IllegalArgumentException("unknown trigger kind " + tag.getString("kind"));
        };
    }
}
