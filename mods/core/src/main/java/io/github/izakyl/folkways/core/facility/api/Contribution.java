package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.Keepouts;
import io.github.izakyl.folkways.core.world.api.Structures;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/** An ordinary world or planning contribution derived from a facility. */
public sealed interface Contribution {

    record Parts(Map<String, ControlRef> controls) implements Contribution {
        public Parts {
            controls = Map.copyOf(controls);
        }
    }

    record Protection(Set<WorldPos> cells) implements Contribution {
        public Protection {
            cells = Set.copyOf(cells);
        }
    }

    record Hop(io.github.izakyl.folkways.core.travel.api.Hop hop) implements Contribution {
    }

    record Keepout(Keepouts.Keepout area) implements Contribution {
    }

    record Closure(WorldPos cell, boolean impassable) implements Contribution {
    }

    record Obstacle(Structures.Obstacles obstacles) implements Contribution {
    }

    /** An edge whose physical transfer is performed by machinery in the world. */
    record Lane(Inlet from, ControlRef to, boolean automatic, List<ItemSpec> items,
                int latency, Optional<Rate> rate) implements Contribution {
        public Lane {
            items = List.copyOf(items);
            if (from instanceof PortInput port && port.ref().equals(to) || latency < 1
                    || automatic && !(from instanceof PortInput)) {
                throw new IllegalArgumentException("invalid lane endpoints or latency");
            }
        }

        public Lane(ControlRef from, ControlRef to, boolean automatic, List<ItemSpec> items,
                    int latency, Optional<Rate> rate) {
            this(new PortInput(from), to, automatic, items, latency, rate);
        }

        public sealed interface Inlet {
            WorldPos at();
        }

        public record PortInput(ControlRef ref) implements Inlet {
            @Override
            public WorldPos at() {
                return ref.at();
            }
        }

        public record WrittenInput(WorldPos at, Write write) implements Inlet {
        }

        /** Returns the number accepted. A simulated write leaves the world and the offered stack untouched. */
        @FunctionalInterface
        public interface Write {
            int deposit(ServerLevel level, WorldPos at, Mob worker, ItemStack offered, boolean simulate);
        }

        public record Rate(int ticks, int count) {
            public Rate {
                if (ticks < 1 || count < 1) {
                    throw new IllegalArgumentException("invalid lane throughput");
                }
            }
        }

        long travelTicks(long count) {
            return latency + rate.map(each -> ((count - 1) / each.count()) * each.ticks()).orElse(0L);
        }
    }

    record Workshop(io.github.izakyl.folkways.core.plan.api.Workshop workshop) implements Contribution {
    }
}
