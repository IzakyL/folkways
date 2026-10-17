package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyWorks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

final class Urges {

    private static final long BACKOFF = 100L;

    private final Map<ResourceLocation, Long> cooling = new HashMap<>();

    List<Urge> offered(ColonyData colony, ColonyView view, Body body, Worker who) {
        List<Urge> offered = new ArrayList<>();
        for (ColonyWorks.Felt felt : colony.works().urges(who, view)) {
            if (Participations.between(body.kind().id(), Stake.urge(felt.owner()))
                != Participation.NONE && felt.urge().weight() > 0) {
                offered.add(felt.urge());
            }
        }
        return List.copyOf(offered);
    }

    Optional<Urge> keenest(List<Urge> offered, long now, Predicate<Urge> available) {
        Urge best = null;
        for (Urge urge : offered) {
            if (quiet(urge.id(), now) || !available.test(urge)) {
                continue;
            }
            if (best == null || urge.weight() > best.weight()) {
                best = urge;
            }
        }
        return Optional.ofNullable(best);
    }

    void refused(ResourceLocation urge, RefusalKind why, long now) {
        Labor.LOGGER.debug("urge {} refused: {}", urge, why.translationKey());
        cooling.put(urge, now + BACKOFF);
    }

    private boolean quiet(ResourceLocation urge, long now) {
        Long until = cooling.get(urge);
        if (until == null) {
            return false;
        }
        if (until > now) {
            return true;
        }
        cooling.remove(urge);
        return false;
    }
}
