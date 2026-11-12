package io.github.izakyl.folkways.core.inventory.api;

import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Additional pack-crafting capabilities supplied by enrolled stores. */
public final class StoreWorkstations {

    public enum Capability {
        CRAFTING(Blocks.CRAFTING_TABLE),
        STONECUTTING(Blocks.STONECUTTER),
        SMITHING(Blocks.SMITHING_TABLE);

        private final Block station;

        Capability(Block station) {
            this.station = station;
        }

        public Block station() {
            return station;
        }
    }

    @FunctionalInterface
    public interface Provider {
        /** Read current effective capabilities; return empty for an unrelated block or an inactive upgrade. */
        Set<Capability> at(ServerLevel level, BlockPos pos);
    }

    private static volatile Map<ResourceLocation, Provider> providers = Map.of();

    private StoreWorkstations() {
    }

    /** Register once during mod setup. Providers are additive and must not mutate the world when queried. */
    public static synchronized void register(ResourceLocation owner, Provider provider) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(provider, "provider");
        Map<ResourceLocation, Provider> next = new LinkedHashMap<>(providers);
        if (next.putIfAbsent(owner, provider) != null) {
            throw new IllegalStateException(owner + " already provides store workstations");
        }
        providers = Map.copyOf(next);
    }

    /**
     * Read live declarations, excluding unloaded, unusable and condemned stores. This does not enroll a block:
     * the caller must check its colony's store membership. Consumers refresh these capabilities periodically,
     * uses the store's footings, and checks again before executing work. No separate STATION claim is needed.
     * Providers grant only ordinary pack crafting; they do not expose or operate native upgrade inventories.
     */
    public static Set<Capability> at(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || StorePorts.condemned(WorldPos.of(level, pos)).isPresent()) {
            return Set.of();
        }
        boolean usable = StorePorts.at(level, pos).map(port -> port.usability().usable())
            .orElseGet(() -> Containers.at(level, pos).isPresent());
        if (!usable) {
            return Set.of();
        }
        Set<Capability> found = EnumSet.noneOf(Capability.class);
        for (Provider provider : providers.values()) {
            found.addAll(provider.at(level, pos));
        }
        return Set.copyOf(found);
    }

    public static Capability vanilla(Block block) {
        for (Capability capability : Capability.values()) {
            if (capability.station() == block) {
                return capability;
            }
        }
        throw new IllegalArgumentException(block + " is not a pack-crafting station");
    }
}
