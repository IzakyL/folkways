package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.effect.Stats;
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

    non-sealed interface Continuous extends Workload {

        boolean holds(ServerLevel level, Worker who);
    }

    // Work a body goes into and stays in while its owner has pieces for it, such as a site going up: the plan sees
    // one piece of work, with what all of it needs and how long it is reckoned to take, and gives it to one body.
    // Once that body has come to it, the owner hands it one piece after another, each done as any work is; when
    // the owner has no more for it, the body comes out and the work is done. An urge that takes the body away
    // meanwhile takes it from the piece in hand only: it goes back in after.
    non-sealed interface Within extends Workload {

        // What the body does next: a piece, a wait for one to come up, or leaving.
        sealed interface Turn {

            record Piece(Node node) implements Turn {
            }

            record Wait() implements Turn {
            }

            record Leave() implements Turn {
            }
        }

        Turn WAIT = new Turn.Wait();
        Turn LEAVE = new Turn.Leave();

        Turn next(ServerLevel level, Worker who);

        // How a piece handed out ended, once for each.
        default void ended(ServerLevel level, Node piece, Ending how) {
        }
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
