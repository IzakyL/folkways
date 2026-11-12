package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Supplies;
import java.util.UUID;

/** Reports supply collection so direct guard orders can wait for restocking. */
public record PatrolSupplyReadout(Colony colony) implements Supplies {

    @Override
    public boolean underway(UUID resident) {
        return PatrolSupplies.of(colony).underway(resident);
    }
}
