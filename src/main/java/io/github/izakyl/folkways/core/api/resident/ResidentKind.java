package io.github.izakyl.folkways.core.api.resident;

import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

// Barehanded names the trades this kind works with its own body: their work asks it for none of
// the tools it asks of others, and it goes looking for none of their kit.
public record ResidentKind(ResourceLocation id, Growth growth, Locomotion locomotion,
                           Embodiment embodiment, Map<Stake, Participation> participations,
                           Set<ResourceLocation> barehanded) {

    public ResidentKind {
        participations = Map.copyOf(participations);
        barehanded = Set.copyOf(barehanded);
    }

    public sealed interface Growth {

        record Climbs() implements Growth {
        }

        record Built(Map<String, Integer> ranks) implements Growth {

            public Built {
                ranks = Map.copyOf(ranks);
            }
        }

        Growth CLIMBS = new Climbs();
    }

    public interface Embodiment {

        Optional<Body> read(ResidentKind kind, Entity entity);

        Embodiment ITSELF = (kind, entity) ->
            entity instanceof Body body ? Optional.of(body) : Optional.empty();
    }

    public ResidentKind(ResourceLocation id, Growth growth, Locomotion locomotion) {
        this(id, growth, locomotion, Embodiment.ITSELF, Map.of(), Set.of());
    }

    public static ResidentKind growing(ResourceLocation id, Locomotion locomotion) {
        return new ResidentKind(id, Growth.CLIMBS, locomotion);
    }

    public ResidentKind embodiedBy(Embodiment embodiment) {
        return new ResidentKind(id, growth, locomotion, embodiment, participations, barehanded);
    }

    public ResidentKind taking(Map<Stake, Participation> participations) {
        return new ResidentKind(id, growth, locomotion, embodiment, participations, barehanded);
    }

    public ResidentKind barehanded(Set<ResourceLocation> trades) {
        return new ResidentKind(id, growth, locomotion, embodiment, participations, trades);
    }

    public boolean worksBarehanded(ResourceLocation trade) {
        return barehanded.contains(trade);
    }

    public Optional<Body> read(Entity entity) {
        return embodiment.read(this, entity);
    }
}
