package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
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

    private List<Holding> holdings;

    private final Map<ResourceLocation, Set<BlockPos>> blocks = new LinkedHashMap<>();

    private List<Body> bodies;

    private List<Resident> residents;

    private Map<UUID, Map<String, Integer>> ranks;

    private ColonySnapshot(ColonyData colony, ServerLevel level) {
        this.colony = colony;
        this.level = level;
    }

    public static ColonySnapshot of(ColonyData colony, ServerLevel level) {
        return new ColonySnapshot(colony, level);
    }

    private List<Body> bodies() {
        if (bodies == null) {
            bodies = colony.holdings().bodiesIn(level);
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
    public List<Holding> holdings() {
        if (holdings == null) {
            List<Holding> here = new ArrayList<>();
            for (Holding holding : colony.holdings().all()) {
                if (holding.what().cells().stream().anyMatch(cell -> cell.in(level))) {
                    here.add(holding);
                }
            }
            holdings = List.copyOf(here);
        }
        return holdings;
    }

    @Override
    public Set<BlockPos> blocks(ResourceLocation owner) {
        return blocks.computeIfAbsent(owner, key -> {
            Set<BlockPos> found = new LinkedHashSet<>();
            for (Holding holding : colony.holdings().of(key)) {
                if (holding.what() instanceof Held.Block(WorldPos cell)) {
                    WorldSpaces.storage(level, cell).filter(level::isLoaded).ifPresent(found::add);
                }
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
    public int rankOf(Resident resident, String perk) {
        if (ranks == null) {
            Map<UUID, Map<String, Integer>> held = new LinkedHashMap<>();
            for (Body body : bodies()) {
                held.put(body.id(), body.perks().ranks());
            }
            ranks = Map.copyOf(held);
        }
        return ranks.getOrDefault(resident.id(), Map.of()).getOrDefault(perk, 0);
    }
}
