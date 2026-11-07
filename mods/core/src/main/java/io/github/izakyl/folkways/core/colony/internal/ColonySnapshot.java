package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.effect.Effects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class ColonySnapshot implements ColonyView {

    private final ColonyData colony;

    private final ServerLevel level;

    private List<Claim> claims;

    private final Map<ResourceLocation, Set<BlockPos>> blocks = new LinkedHashMap<>();

    private List<Body> bodies;

    private List<Resident> residents;

    private Map<UUID, Effects> effects;

    private ColonySnapshot(ColonyData colony, ServerLevel level) {
        this.colony = colony;
        this.level = level;
    }

    public static ColonySnapshot of(ColonyData colony, ServerLevel level) {
        return new ColonySnapshot(colony, level);
    }

    private List<Body> bodies() {
        if (bodies == null) {
            bodies = colony.claims().bodiesIn(level);
        }
        return bodies;
    }

    @Override
    public ServerLevel level() {
        return level;
    }

    @Override
    public ResourceKey<Level> dimension() {
        return level.dimension();
    }

    @Override
    public List<Claim> claims() {
        if (claims == null) {
            List<Claim> here = new ArrayList<>();
            for (Claim claim : colony.claims().all()) {
                if (claim.what().cells().stream().anyMatch(cell -> cell.in(level))) {
                    here.add(claim);
                }
            }
            claims = List.copyOf(here);
        }
        return claims;
    }

    @Override
    public Set<BlockPos> blocks(ResourceLocation owner) {
        return blocks.computeIfAbsent(owner, key -> {
            Set<BlockPos> found = new LinkedHashSet<>();
            for (Claim claim : colony.claims().of(key)) {
                claim.what().singleCell().flatMap(cell -> WorldSpaces.storage(level, cell)).filter(level::isLoaded).ifPresent(found::add);
            }
            return Set.copyOf(found);
        });
    }

    @Override
    public List<Resident> residents() {
        if (residents == null) {
            List<Resident> living = new ArrayList<>(bodies().size());
            for (Body body : bodies()) {
                living.add(body.resident());
            }
            residents = List.copyOf(living);
        }
        return residents;
    }

    @Override
    public Effects effectsOf(Resident resident) {
        if (effects == null) {
            Map<UUID, Effects> held = new LinkedHashMap<>();
            for (Body body : bodies()) {
                held.put(body.id(), body.effects());
            }
            effects = Map.copyOf(held);
        }
        return effects.getOrDefault(resident.id(), Effects.NONE);
    }
}
