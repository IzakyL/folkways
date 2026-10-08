package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

final class Upkeep {

    record Cold(WorldPos at, Block block, Stances stances, ItemSpec fuel) {
    }

    record Stranded(WorldPos at, Block block, Stances stances, ItemStack held) {
    }

    private final Bookings spokenFor;
    private final Runnable changed;

    private volatile List<Cold> cold = List.of();
    private volatile List<Stranded> stranded = List.of();

    Upkeep(Bookings spokenFor, Runnable changed) {
        this.spokenFor = spokenFor;
        this.changed = changed;
    }

    void found(List<Cold> cold, List<Stranded> stranded) {
        this.cold = List.copyOf(cold);
        this.stranded = List.copyOf(stranded);
    }

    List<Grown> goals(ServerLevel level) {
        List<Grown> goals = new ArrayList<>();
        // A cooker booked brings its own fuel with its smelt; only one loaded by some other hand is lit here.
        for (Cold one : cold) {
            if (one.at().in(level) && !spokenFor.taken(one.at())) {
                goals.add(Grown.of(new FuelNode(idOf("fuel/" + one.fuel().describe(), one.at()), one.at(),
                    one.block(), one.stances(), one.fuel(), 1, () -> lit(one.at()))));
            }
        }
        for (Stranded one : stranded) {
            if (one.at().in(level) && !spokenFor.expects(one.at(), one.held())) {
                goals.add(Grown.of(new ClearNode(idOf("clear", one.at()), one.at(), one.block(),
                    one.stances(), held -> !spokenFor.expects(one.at(), held))));
            }
        }
        return goals;
    }

    void clear() {
        cold = List.of();
        stranded = List.of();
    }

    private void lit(WorldPos at) {
        cold = cold.stream().filter(one -> !one.at().equals(at)).toList();
        changed.run();
    }

    private static UUID idOf(String chore, WorldPos at) {
        return UUID.nameUUIDFromBytes(("folkways:wares/" + chore + "/" + at)
            .getBytes(StandardCharsets.UTF_8));
    }
}
