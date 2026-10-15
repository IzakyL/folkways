package io.github.izakyl.folkways.plugins.cookery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class KitchenStations {
    private static volatile List<KitchenStation> stations = List.of();

    private KitchenStations() {
    }

    public static synchronized void register(KitchenStation station) {
        Objects.requireNonNull(station, "station");
        List<KitchenStation> next = new ArrayList<>(stations);
        if (next.stream().anyMatch(known -> known.getClass() == station.getClass())) {
            throw new IllegalArgumentException("kitchen station already registered: " + station.getClass());
        }
        next.add(station);
        stations = List.copyOf(next);
    }

    public static List<KitchenStation> all() {
        return stations;
    }

    public static Optional<KitchenStation> find(BlockState state) {
        return stations.stream().filter(station -> station.matches(state)).findFirst();
    }

    public static BlockPos anchor(Level level, BlockPos clicked) {
        return find(level.getBlockState(clicked)).map(station -> station.anchor(level, clicked))
            .orElse(clicked).immutable();
    }
}
