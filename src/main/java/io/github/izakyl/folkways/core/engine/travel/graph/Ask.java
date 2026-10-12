package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.Urgency;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.Collection;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

// The way is the same for every body of a kind, so one ask stands for all of them: which of them asked is not kept.
record Ask(ResourceLocation kind, Realm realm, long from, LongList goals, Urgency urgency) {

    record Key(ResourceLocation kind, Realm realm, LongList goals) {
    }

    static Optional<Ask> of(ResourceLocation kind, WorldPos from, Collection<WorldPos> goals) {
        return of(kind, from, goals, Urgency.PLANNING);
    }

    static Optional<Ask> of(ResourceLocation kind, WorldPos from, Collection<WorldPos> goals, Urgency urgency) {
        LongArrayList cells = new LongArrayList(goals.size());
        for (WorldPos goal : goals) {
            if (goal.sameRealm(from) && !goal.cell().equals(from.cell()) && !cells.contains(goal.cell().asLong())) {
                cells.add(goal.cell().asLong());
            }
        }
        if (cells.isEmpty()) {
            return Optional.empty();
        }
        cells.sort(null);
        return Optional.of(new Ask(kind, from.realm(), from.cell().asLong(), cells, urgency));
    }

    // Asked again, from wherever the latest asker stands, as urgently as the most urgent of them.
    Ask again(Ask later) {
        return later.urgency.compareTo(urgency) <= 0 ? later
            : new Ask(later.kind, later.realm, later.from, later.goals, urgency);
    }

    Key key() {
        return new Key(kind, realm, goals);
    }

    BlockPos start() {
        return BlockPos.of(from);
    }
}
