package io.github.izakyl.folkways.vanilla.compost;

import io.github.izakyl.folkways.core.facility.api.Contribution;
import io.github.izakyl.folkways.core.facility.api.Facility;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.Prefabs;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.plan.api.WorkshopRecipes;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

record CompostPrefab(WorldPos site, Optional<Composting> workshop) implements Facility {

    @Override
    public FacilityExtent extent(ServerLevel level) {
        return FacilityExtent.blocks(List.of(site()));
    }

    static void register() {
        Prefabs.register(CompostContent.POINT, (level, pos) -> level.getBlockState(pos).is(Blocks.COMPOSTER)
            ? Optional.of(new CompostPrefab(WorldPos.of(level, pos), Optional.empty())) : Optional.empty());
    }

    static Optional<CompostPrefab> at(ServerLevel level, BlockPos pos) {
        return Prefabs.at(level, pos).filter(CompostPrefab.class::isInstance).map(CompostPrefab.class::cast);
    }

    CompostPrefab offering(UUID colony, CompostStation station, CompostBookings bookings, Runnable changed) {
        return new CompostPrefab(site, Optional.of(new Composting(id(), site, colony, station, bookings, changed)));
    }

    @Override
    public UUID id() {
        return UUID.nameUUIDFromBytes((CompostContent.POINT + "/" + site).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public List<Contribution> projections(ServerLevel level, Map<String, Value> readings) {
        return workshop.stream().<Contribution>map(Contribution.Workshop::new).toList();
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

    // Composting consumes surplus and collects mature output on the colony sweep.
    // The projected workshop supplies that sweep and offers no on-demand recipes.
    record Composting(UUID key, WorldPos at, UUID colony, CompostStation station, CompostBookings bookings,
                      Runnable changed) implements Workshop {

        @Override
        public ResourceLocation id() {
            return CompostContent.ID;
        }

        @Override
        public WorkSite site() {
            return new WorkSite.AtBlock(at);
        }

        @Override
        public List<WorkshopRecipes.Recipe> recipes(Set<ItemSpec> products) {
            return List.of();
        }

        List<Grown> goals(ServerLevel level, Optional<Stances> stances, CompostStation.Planning planning) {
            return stances.isPresent()
                ? station.recovery(colony, level, at, stances.get(), bookings, changed, planning)
                : station.activeRecovery(colony, level, at, bookings);
        }
    }
}
