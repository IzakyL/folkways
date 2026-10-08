package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.Urgency;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

record Ask(ResourceLocation kind, Realm realm, long from, LongList goals, UUID requester, Urgency urgency) {

    record Key(ResourceLocation kind, Realm realm, UUID requester, LongList goals) {
    }

    static Optional<Ask> of(ResourceLocation kind, WorldPos from, Collection<WorldPos> goals) {
        return of(kind, null, from, goals, Urgency.PLANNING);
    }

    static Optional<Ask> of(ResourceLocation kind, UUID requester, WorldPos from, Collection<WorldPos> goals,
                            Urgency urgency) {
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
        return Optional.of(new Ask(kind, from.realm(), from.cell().asLong(), cells, requester, urgency));
    }

    Key key() {
        return new Key(kind, realm, requester, goals);
    }

    BlockPos start() {
        return BlockPos.of(from);
    }
}
