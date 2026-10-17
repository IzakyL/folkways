package io.github.izakyl.folkways.effect.perk;

import io.github.izakyl.folkways.effect.Flag;
import io.github.izakyl.folkways.effect.Modifier;
import io.github.izakyl.folkways.effect.Scope;
import io.github.izakyl.folkways.effect.Stat;
import io.github.izakyl.folkways.effect.Stats;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

public record Perk(String id, int maxRank, Grant grant) {

    public Perk {
        if (maxRank < 1) {
            throw new IllegalArgumentException("perk " + id + " has no rank, so a draw of it grants nothing");
        }
        Objects.requireNonNull(grant, "grant");
    }

    // What holding a perk at a rank does to its holder.
    @FunctionalInterface
    public interface Grant {

        List<Modifier> at(int rank);

        static Grant flag(Flag flag) {
            return rank -> List.of(Modifier.grant(flag));
        }

        static Grant flag(Flag flag, Scope scope) {
            return rank -> List.of(new Modifier.Grant(flag, scope));
        }

        // One more of the stat for every rank.
        static Grant perRank(Stat stat, double each) {
            return rank -> List.of(Modifier.add(stat, each * rank));
        }

        // The stat times the ladder's entry for the rank. A rank the ladder has no entry for changes nothing, and
        // neither does a factor of nothing or less.
        static Grant multiplied(Stat stat, Supplier<? extends List<? extends Number>> ladder) {
            return byLadder(ladder, factor -> factor > 0.0 ? List.of(Modifier.multiply(stat, factor)) : List.of());
        }

        static Grant added(Stat stat, Supplier<? extends List<? extends Number>> ladder) {
            return byLadder(ladder, amount -> List.of(Modifier.add(stat, amount)));
        }

        // A trade's work done in the ladder's share of its ticks: 0.85 at a rank makes it take 85% as long.
        static Grant haste(ResourceLocation trade, Supplier<? extends List<? extends Number>> ladder) {
            return byLadder(ladder, share -> share > 0.0
                ? List.of(new Modifier.Multiply(Stats.WORK_SPEED, 1.0 / share, Scope.trade(trade)))
                : List.of());
        }

        private static Grant byLadder(Supplier<? extends List<? extends Number>> ladder,
                                      DoubleFunction<List<Modifier>> step) {
            return rank -> {
                List<? extends Number> steps = ladder.get();
                return rank >= 1 && rank <= steps.size()
                    ? step.apply(steps.get(rank - 1).doubleValue())
                    : List.of();
            };
        }
    }
}
