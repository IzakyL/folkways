package io.github.izakyl.folkways.core.api.resident;

import io.github.izakyl.folkways.core.api.Ledger;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.perk.PerkPool;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

public final class ResidentKinds {

    private static final Ledger<ResidentKind> KINDS =
        new Ledger<>("a sort of resident kind", ResidentKind::id);

    private ResidentKinds() {
    }

    public static ResidentKind register(ResidentKind kind) {
        Locomotion moving = kind.locomotion();
        for (ResidentKind standing : KINDS.values()) {
            if (standing.locomotion() != moving && standing.locomotion().id().equals(moving.id())) {
                throw new IllegalStateException(kind.id() + " moves as " + moving.id()
                    + ", which " + standing.id() + " already declared as a different implementation");
            }
        }
        KINDS.claim(kind);
        for (Map.Entry<Stake, Participation> taken : kind.participations().entrySet()) {
            Participations.register(kind.id(), taken.getKey(), taken.getValue());
        }
        return kind;
    }

    public static Optional<ResidentKind> of(ResourceLocation id) {
        return KINDS.of(id);
    }

    // Whether a resident of this kind needs no tool for work done in this trade.
    public static boolean barehanded(ResourceLocation kind, Optional<Vocation> trade) {
        return trade.isPresent() && of(kind).filter(held -> held.worksBarehanded(trade.get().id())).isPresent();
    }

    public static Set<ResourceLocation> ids() {
        return Set.copyOf(KINDS.ids());
    }

    public static void verify() {
        for (ResidentKind kind : KINDS.values()) {
            if (kind.growth() instanceof ResidentKind.Growth.Built(Map<String, Integer> ranks)) {
                verifyRanks(kind, ranks);
            }
        }
    }

    private static void verifyRanks(ResidentKind kind, Map<String, Integer> ranks) {
        for (Map.Entry<String, Integer> held : ranks.entrySet()) {
            if (PerkPool.byId(held.getKey()).isEmpty()) {
                throw new IllegalStateException(kind.id() + " is built with " + held.getKey()
                    + ", which is not a perk anything declared");
            }
            int cap = PerkPool.maxRank(held.getKey());
            if (held.getValue() < 1 || held.getValue() > cap) {
                throw new IllegalStateException(kind.id() + " is built with " + held.getKey()
                    + " at " + held.getValue() + ", outside the 1.." + cap + " that perk climbs to");
            }
        }
    }
}
