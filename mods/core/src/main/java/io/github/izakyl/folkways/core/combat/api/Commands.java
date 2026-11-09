package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.Stake;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Direct command of residents. A resident kind takes orders by standing to both stakes in its own rows. */
public final class Commands {
    public static final ResourceLocation ORDERS = ResourceLocation.fromNamespaceAndPath("folkways_core", "resident_command");
    public static final ResourceLocation SELECTION =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "combat_selection_control");

    private Commands() {
    }

    /** The participation rows a commandable kind adds to its own. */
    public static Map<Stake, Participation> rows() {
        return Map.of(Stake.urge(ORDERS), Participation.ALWAYS, Stake.urge(SELECTION), Participation.ALWAYS);
    }

    /** A live body of a commandable kind that is free of any seat it does not control. */
    public static boolean commandable(Body body) {
        return Participations.between(body.kind().id(), Stake.urge(ORDERS)) != Participation.NONE
            && body.mob().isAlive()
            && !body.mob().isRemoved()
            && Combatant.get().freeToCommand(body.mob());
    }
}
