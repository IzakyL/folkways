package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.effect.Stats;
import net.minecraft.resources.ResourceLocation;
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

    // Cost is described by NodeSpec.estimate; execution is supplied by the node.
    record Custom() implements Workload {
    }

    non-sealed interface Continuous extends Workload {

        boolean holds(ServerLevel level, Worker who);
    }

    @FunctionalInterface
    interface Haste {

        Haste NONE = who -> 1.0;

        double of(Worker who);

        // Work of a trade takes its ticks over the worker's speed at that trade.
        static Haste of(ResourceLocation trade) {
            return who -> 1.0 / who.effects().value(Stats.WORK_SPEED, trade);
        }
    }
}
