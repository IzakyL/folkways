package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.inventory.InventorySnapshot;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** A stock preview, never a reservation. Loaded members consume preview units once in roster order. */
public record ProfileShortages(
    int assigned, int incomplete, int unloaded, Map<UUID, Integer> missing, Map<UUID, Integer> unavailableStock,
    boolean complete) {
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
            ResidentEntity person = body;
            InventorySnapshot.Resident resident = snapshot.residents().get(member);
            if (resident == null || resident.state() != InventorySnapshot.State.OBSERVED) {
                colony.inventory().ensureObservedResident(member);
                complete = false;
                continue;
            }
            Map<UUID, Integer> deficits = supplies.observedDeficits(body, profile, resident);
            boolean blocked =
                profile.entries().stream()
                .filter(entry -> entry.slot().isPresent())
                .anyMatch(
                entry ->
                    resident.equipment().containsKey(entry.slot().orElseThrow())
                        && !resident.equipment().get(entry.slot().orElseThrow()).stackCopy().isEmpty()
                        && !entry.matches(resident.equipment().get(entry.slot().orElseThrow()).stackCopy()));
            if (blocked || deficits.values().stream().anyMatch(count -> count > 0)) {
                incomplete++;
            }
            ServerLevel level = (ServerLevel) body.level();
            if (supplies.unresolvedReservations(level, snapshot)) {
                complete = false;
            }
            List<WorldPos> nearby =
                colony.storesIn(level.dimension()).stream()
                .filter(
                source ->
                    source.sameRealm(WorldSpaces.at(person))
                        && WorldSpaces.world(level, source)
                        .filter(
                        at ->
                            at.distanceToSqr(person.position())
                                <= 32 * 32)
                        .isPresent())
                .sorted(
                Comparator.comparingDouble(
                source ->
                    WorldSpaces.world(level, source)
                        .orElseThrow()
                        .distanceToSqr(person.position())))
                .limit(32)
                .toList();
            for (LoadoutProfile.Entry entry : profile.entries()) {
                int left = deficits.get(entry.id());
                missing.merge(entry.id(), left, Integer::sum);
                for (WorldPos source : nearby) {
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
            Map.copyOf(unavailable), complete);
    }
}
