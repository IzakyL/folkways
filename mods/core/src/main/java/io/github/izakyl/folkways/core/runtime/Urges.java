package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.colony.internal.ColonyWorks;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

final class Urges {

    private static final long BACKOFF = 100L;

    private record Cooling(UUID resident, ResourceLocation urge) { }
    private final Map<Cooling, Long> cooling = new HashMap<>();

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

    Optional<Urge> keenest(UUID resident, List<Urge> offered, long now, Predicate<Urge> available) {
        // One-shot controllers may never offer a refused identity again.
        cooling.values().removeIf(until -> until <= now);
        Urge best = null;
        for (Urge urge : offered) {
            if (urge.reservation() || quiet(new Cooling(resident, urge.id()), now) || !available.test(urge)) {
                continue;
            }
            if (best == null || urge.outranks(best)) {
                best = urge;
            }
        }
        return Optional.ofNullable(best);
    }

    void refused(UUID resident, ResourceLocation urge, RefusalKind why, long now) {
        Labor.LOGGER.debug("urge {} refused: {}", urge, why.translationKey());
        cooling.put(new Cooling(resident, urge), now + BACKOFF);
    }

    void gone(UUID resident) {
        cooling.keySet().removeIf(key -> key.resident().equals(resident));
    }

    long remaining(UUID resident, ResourceLocation urge, long now) {
        return Math.max(0L, cooling.getOrDefault(new Cooling(resident, urge), now) - now);
    }

    private boolean quiet(Cooling key, long now) {
        Long until = cooling.get(key);
        if (until == null) {
            return false;
        }
        if (until > now) {
            return true;
        }
        cooling.remove(key);
        return false;
    }
}
