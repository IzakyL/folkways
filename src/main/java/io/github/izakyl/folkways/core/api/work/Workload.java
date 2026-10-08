package io.github.izakyl.folkways.core.api.work;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;

public sealed interface Workload {

    // Wind up once, then change the world in one commit that either all happens or none of it does.
    record Once(int baseTicks, Haste haste) implements Workload {

        public Once {
            if (baseTicks < 0) {
                throw new IllegalArgumentException("work cannot cost less than no wind-up");
            }
        }

        public static Once of(int baseTicks) {
            return new Once(baseTicks, Haste.NONE);
        }

        public static Once of(int baseTicks, Haste haste) {
            return new Once(baseTicks, haste);
        }

        public int ticksFor(Worker who) {
            if (baseTicks == 0) {
                return 0;
            }
            return Math.max(1, (int) Math.round(baseTicks * haste.of(who)));
        }
    }

    non-sealed interface Continuous extends Workload {

        boolean holds(ServerLevel level, Worker who);
    }

    @FunctionalInterface
    interface Haste {

        Haste NONE = who -> 1.0;

        double of(Worker who);

        static Haste ranked(String perk, Supplier<? extends List<? extends Number>> byRank) {
            return who -> at(who.rankOf(perk), byRank.get());
        }

        static double at(int rank, List<? extends Number> byRank) {
            return rank >= 1 && rank <= byRank.size() ? byRank.get(rank - 1).doubleValue() : 1.0;
        }
    }
}
