package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Physical ownership of ordinary stock, with access points distinct from the owned body. */
public record ContainerFacility(WorldPos site, FacilityExtent body, Set<WorldPos> access, boolean network, List<Workshop> workshops)
        implements Facility {

    public ContainerFacility {
        access = Set.copyOf(access);
        workshops = List.copyOf(workshops);
    }

    public static ContainerFacility enrolled(ServerLevel level, WorldPos at) {
        Set<WorldPos> cells = Set.copyOf(chestCells(level, at));
        return new ContainerFacility(at, FacilityExtent.blocks(cells), cells, false, List.of());
    }

    public static Collection<WorldPos> chestCells(ServerLevel level, WorldPos at) {
        if (at.in(level) && level.isLoaded(at.block(level))) {
            BlockPos pos = at.block(level);
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                return List.of(at, WorldPos.of(level, pos.relative(ChestBlock.getConnectedDirection(state))));
            }
        }
        return List.of(at);
    }

    public static ContainerFacility network(WorldPos site, Collection<WorldPos> cells, Set<WorldPos> access) {
        return new ContainerFacility(site, FacilityExtent.blocks(new LinkedHashSet<>(cells)), access, true, List.of());
    }

    public Optional<ItemPort> port(ServerLevel level, WorldPos at) {
        return access.contains(at) ? Ports.itemAt(level, at) : Optional.empty();
    }

    public ContainerFacility withWorkshops(List<Workshop> offers) {
        return new ContainerFacility(site, body, access, network, offers);
    }

    @Override
    public List<Contribution> projections(ServerLevel level, Map<String, Value> readings) {
        return workshops.stream().<Contribution>map(Contribution.Workshop::new).toList();
    }

    @Override
    public UUID id() {
        return UUID.nameUUIDFromBytes(("container/" + site).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public FacilityExtent extent(ServerLevel level) {
        return body;
    }

    @Override
    public Map<String, Routine.Use> routines() {
        return Map.of();
    }

    @Override
    public List<Trigger> triggers() {
        return List.of();
    }

    @Override
    public Map<String, Value> parameters() {
        return Map.of();
    }

    @Override
    public Map<String, Reading> readings(ServerLevel level) {
        return Map.of();
    }
}
