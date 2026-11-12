package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.equipment.api.EquipmentPreview;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.equipment.api.EquipmentSupplier;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/** Core supplies personal equipment at any time. A preview only: the planner still reserves and fetches. */
final class PersonalEquipmentPreview implements EquipmentSupplier {

    @Override
    public boolean supplies(EquipmentProfile.Entry entry) {
        return CarriedKit.supplies(entry);
    }

    @Override
    public EquipmentPreview preview(Colony colony, EquipmentProfile profile, MinecraftServer server) {
        Map<UUID, Integer> missing = new LinkedHashMap<>();
        Map<UUID, Integer> stockMissing = new LinkedHashMap<>();
        Set<UUID> incomplete = new LinkedHashSet<>();
        Set<UUID> unloaded = new LinkedHashSet<>();
        var snapshot = colony.inventory().snapshot();
        boolean complete = true;
        ProfileLedger ledger = (ProfileLedger) EquipmentProfiles.of(colony);
        List<UUID> residents = ledger.residents(profile.id());
        for (var level : server.getAllLevels()) {
            List<ItemStack> stock = new ArrayList<>();
            for (var store : snapshot.stores().values()) {
                if (!store.dimension().equals(level.dimension()) || !StoreRule.takes(store.rules())
                    || !store.labor() || !store.usability().usable()) {
                    continue;
                }
                if (store.state() != InventorySnapshot.State.OBSERVED) {
                    colony.inventory().ensureObserved(store.stash());
                    complete = false;
                    continue;
                }
                for (var held : store.entries()) {
                    // Preserve the observed identity for component-filtered equipment.
                    stock.add(held.prototype().copyWithCount((int) Math.min(Integer.MAX_VALUE, held.count())));
                }
            }
            for (var reserved : colony.reservedInputs(level)) {
                if (reserved.store().isEmpty()) {
                    continue;
                }
                long left = reserved.count();
                for (ItemStack stack : stock) {
                    // Reservations of a component variant conservatively reserve the item ID in this ID-only preview.
                    if (left > 0 && Goods.members(reserved.goods()).contains(stack.getItem())) {
                        int used = (int) Math.min(left, stack.getCount());
                        stack.shrink(used);
                        left -= used;
                    }
                }
            }
            for (UUID id : residents) {
                var body = Optional.ofNullable(level.getEntity(id)).flatMap(Bodies::of);
                if (body.isEmpty()) {
                    continue;
                }
                var deficits = CarriedKit.deficits(body.get(), CarriedKit.entries(Optional.of(profile)));
                if (deficits.values().stream().anyMatch(count -> count > 0)) {
                    incomplete.add(id);
                }
                for (EquipmentProfile.Entry entry : CarriedKit.entries(Optional.of(profile))) {
                    int need = deficits.get(entry.id());
                    missing.merge(entry.id(), need, Integer::sum);
                    for (ItemStack stack : stock) {
                        if (need > 0 && entry.matches(stack)) {
                            int used = Math.min(need, stack.getCount());
                            need -= used;
                            stack.shrink(used);
                        }
                    }
                    stockMissing.merge(entry.id(), need, Integer::sum);
                }
            }
        }
        for (UUID id : residents) {
            if (StreamSupport.stream(server.getAllLevels().spliterator(), false)
                .noneMatch(level -> level.getEntity(id) != null)) {
                unloaded.add(id);
                complete = false;
            }
        }
        return new EquipmentPreview(complete, incomplete, unloaded, missing, stockMissing,
            ledger.carried().inTransit(Set.copyOf(residents)), Map.of(), List.of(),
            EquipmentKits.slots(profile));
    }
}
