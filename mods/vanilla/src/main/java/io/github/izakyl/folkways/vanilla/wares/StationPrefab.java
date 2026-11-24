package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.facility.api.Contribution;
import io.github.izakyl.folkways.core.facility.api.Facility;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.Prefabs;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/** A native processing station, recognisable before a colony enrols it. */
record StationPrefab(WorldPos site, Block block, Optional<Offer> offer) implements Facility {

    static void register() {
        Prefabs.register(WaresContent.STATION, (level, pos) -> {
            var state = level.getBlockState(pos);
            return Stations.isStation(state)
                ? Optional.of(new StationPrefab(WorldPos.of(level, pos), state.getBlock(), Optional.empty()))
                : Optional.empty();
        });
    }

    static Optional<StationPrefab> at(ServerLevel level, BlockPos pos) {
        return Prefabs.at(level, pos).filter(StationPrefab.class::isInstance).map(StationPrefab.class::cast);
    }

    StationHost host(Stances stances, Bookings bookings, Function<Block, Set<WorldPos>> kin, Runnable changed) {
        var cooking = Stations.COOKERS.get(block);
        return cooking == null ? new PackCraftHost(site, block, stances)
            : new CookHost(site, block, cooking, stances, bookings, kin, changed);
    }

    StationPrefab offering(StationHost host, RecipeIndex recipes, Optional<ItemSpec> fuel) {
        return new StationPrefab(site, block, Optional.of(new Offer(host, recipes, fuel)));
    }

    @Override
    public List<Contribution> projections(ServerLevel level, Map<String, Value> readings) {
        return offer.stream().<Contribution>map(work ->
            new Contribution.Workshop(work.host().frozen(work.recipes(), level, work.fuel()))).toList();
    }

    private record Offer(StationHost host, RecipeIndex recipes, Optional<ItemSpec> fuel) {
    }

    @Override
    public FacilityExtent extent(ServerLevel level) {
        return FacilityExtent.blocks(List.of(site()));
    }

    @Override
    public UUID id() {
        return UUID.nameUUIDFromBytes((WaresContent.STATION + "/" + site).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public List<Trigger> triggers() {
        return List.of();
    }

    @Override
    public Map<String, Routine.Use> routines() {
        return Map.of();
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
