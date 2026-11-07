package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.pasture.PastureHorses;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** A stock preview, never a reservation. Loaded members consume preview units once in roster order. */
public record ProfileShortages(
    int assigned, int incomplete, int unloaded, Map<UUID, Integer> missing, Map<UUID, Integer> unavailableStock,
    boolean complete, int missingHorses, int unavailableHorses) {
    public static ProfileShortages read(Colony colony, LoadoutProfile profile, MinecraftServer server) {
        PatrolSupplies supplies = PatrolSupplies.of(colony);
        Map<UUID, Integer> missing = new LinkedHashMap<>();
        Map<UUID, Integer> unavailable = new LinkedHashMap<>();
        Map<Stash, SimpleContainer> stock = new LinkedHashMap<>();
        InventorySnapshot snapshot = colony.inventory().snapshot();
        if (!snapshot.coverage().complete()) {
            colony.inventory().ensureObserved();
        }
        boolean complete = !snapshot.coverage().membershipPending();
        int incomplete = 0, unloaded = 0;
        int missingHorses = 0, unavailableHorses = 0;
        Set<UUID> previewHorses = new LinkedHashSet<>();
        List<UUID> members = supplies.profiles().residents(profile.id());
        for (UUID member : members) {
            ResidentEntity body = null;
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(member) instanceof ResidentEntity person) {
                    body = person;
                    break;
                }
            }
            if (body == null) {
                colony.inventory().ensureObservedResident(member);
                complete = false;
                unloaded++;
                continue;
            }
            InventorySnapshot.Resident resident = snapshot.residents().get(member);
            if (resident == null || resident.state() != InventorySnapshot.State.OBSERVED) {
                colony.inventory().ensureObservedResident(member);
                complete = false;
                continue;
            }
            Map<UUID, Integer> deficits = supplies.observedDeficits(body, profile, resident);
            boolean needsHorse = profile.entries().stream().anyMatch(entry -> entry.kind() == LoadoutProfile.Kind.MOUNT)
                && !supplies.mounts().assigned(member);
            if (needsHorse) {
                missingHorses++;
                var free = PastureHorses.available(colony, (ServerLevel) body.level()).stream()
                    .filter(horse -> !previewHorses.contains(horse.getUUID())).findFirst();
                if (free.isEmpty()) {
                    unavailableHorses++;
                } else {
                    previewHorses.add(free.get().getUUID());
                }
            }
            ResidentEntity loaded = body;
            boolean blocked =
                profile.entries().stream()
                .filter(entry -> entry.kind() == LoadoutProfile.Kind.ARMOR)
                .anyMatch(
                entry ->
                    resident.equipment().containsKey(entry.slot().orElseThrow())
                        && !resident.equipment().get(entry.slot().orElseThrow()).stackCopy().isEmpty()
                        && !entry.matches(resident.equipment().get(entry.slot().orElseThrow()).stackCopy()))
                || profile.entries().stream().filter(entry -> entry.kind() == LoadoutProfile.Kind.HORSE_ARMOR)
                    .anyMatch(entry -> ResidentMounts.horse(loaded).filter(horse ->
                        !horse.getItemBySlot(EquipmentSlot.BODY).isEmpty()
                            && !entry.matches(horse.getItemBySlot(EquipmentSlot.BODY)))
                        .isPresent());
            if (needsHorse || blocked || deficits.values().stream().anyMatch(count -> count > 0)) {
                incomplete++;
            }
            ServerLevel level = (ServerLevel) body.level();
            if (supplies.unresolvedReservations(level, snapshot)) {
                complete = false;
            }
            // The planner draws supplies from any of the colony's stores, however far.
            List<WorldPos> sources = colony.storesIn(level.dimension());
            for (LoadoutProfile.Entry entry : profile.entries()) {
                int left = deficits.get(entry.id());
                missing.merge(entry.id(), left, Integer::sum);
                for (WorldPos source : sources) {
                    if (left == 0) {
                        break;
                    }
                    var sourceStock = snapshot.store(source);
                    if (sourceStock.isEmpty() || sourceStock.get().state() != InventorySnapshot.State.OBSERVED) {
                        colony.inventory().ensureObserved(Stash.at(source));
                        complete = false;
                        continue;
                    }
                    if (!StoreRule.takes(sourceStock.get().rules())) {
                        continue;
                    }
                    var store = sourceStock.get();
                    stock.computeIfAbsent(store.stash(),
                        ignored -> supplies.observedUnreserved(level, snapshot, store));
                    SimpleContainer available = stock.get(store.stash());
                    while (left > 0) {
                        ItemStack taken = Containers.extract(available, entry::matches, left);
                        if (taken.isEmpty()) {
                            break;
                        }
                        left -= taken.getCount();
                    }
                }
                unavailable.merge(entry.id(), left, Integer::sum);
            }
        }
        return new ProfileShortages(members.size(), incomplete, unloaded, Map.copyOf(missing),
            Map.copyOf(unavailable), complete, missingHorses, unavailableHorses);
    }
}
