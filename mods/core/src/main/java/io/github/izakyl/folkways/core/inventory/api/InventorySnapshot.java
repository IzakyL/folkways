package io.github.izakyl.folkways.core.inventory.api;

import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

public record InventorySnapshot(UUID colony, Version version, Map<Stash, Store> stores,
                                Map<UUID, Resident> residents, Coverage coverage) {

    public InventorySnapshot {
        stores = Map.copyOf(stores);
        residents = Map.copyOf(residents);
    }

    public Optional<Store> store(WorldPos at) {
        Store direct = stores.get(Stash.at(at));
        return direct != null ? Optional.of(direct)
            : stores.values().stream().filter(store -> store.aliases().contains(at)).findFirst();
    }

    public enum State {
        UNOBSERVED, QUEUED, OBSERVED, DIRTY, UNLOADED, UNSUPPORTED
    }

    public record Version(UUID epoch, long contents, long policy, long topology, long approaches, long coverage) {
    }

    public record Approach(long revision, State state, Set<Stand> stands) {

        public Approach {
            stands = Set.copyOf(stands);
        }
    }

    public record Store(Stash stash, Set<WorldPos> aliases, ResourceKey<Level> dimension, long incarnation,
                        Version version, State state, boolean labor, List<StoreRule> rules,
                        List<SlotSnapshot> slots, Set<Item> refused, Approach approach,
                        Optional<ResourceLocation> block) {

        public Store(Stash stash, Set<WorldPos> aliases, ResourceKey<Level> dimension, long incarnation,
                     Version version, State state, boolean labor, List<StoreRule> rules,
                     List<SlotSnapshot> slots, Set<Item> refused, Approach approach) {
            this(stash, aliases, dimension, incarnation, version, state, labor, rules, slots, refused, approach,
                Optional.empty());
        }

        public Store {
            Objects.requireNonNull(block, "block");
            aliases = Set.copyOf(aliases);
            rules = List.copyOf(rules);
            slots = List.copyOf(slots);
            refused = Set.copyOf(refused);
        }
    }

    public record Resident(UUID resident, WorldPos at, long incarnation, Version version, State state,
                           List<SlotSnapshot> pack, Map<EquipmentSlot, SlotSnapshot> equipment) {

        public Resident {
            pack = List.copyOf(pack);
            equipment = Map.copyOf(equipment);
        }
    }

    // Complete covers membership, not arbitrary external mutations that have not been reported.
    // An absent provider key has not yet been discovered; true covers its discovery and endpoints.
    public record Coverage(Map<Stash, State> stores, Map<UUID, State> residents, boolean membershipPending,
                           Map<ResourceLocation, Boolean> providers) {

        public Coverage(Map<Stash, State> stores, Map<UUID, State> residents, boolean membershipPending) {
            this(stores, residents, membershipPending, Map.of());
        }

        public Coverage {
            stores = Map.copyOf(stores);
            residents = Map.copyOf(residents);
            providers = Map.copyOf(providers);
        }

        public boolean complete() {
            return !membershipPending && stores.values().stream().allMatch(state -> state == State.OBSERVED)
                && residents.values().stream().allMatch(state -> state == State.OBSERVED);
        }
    }

    public static InventorySnapshot empty(UUID colony) {
        return new InventorySnapshot(colony, new Version(colony, 0, 0, 0, 0, 0), Map.of(), Map.of(),
            new Coverage(Map.of(), Map.of(), false));
    }
}
