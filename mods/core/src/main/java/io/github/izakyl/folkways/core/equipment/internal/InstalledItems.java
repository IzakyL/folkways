package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/** All transfers cross native handlers. Refused removals remain installed and never become a second item. */
final class InstalledItems {

    private InstalledItems() {
    }

    static ItemStack install(Body body, EquipmentProfile profile, EquipmentProfile.Entry entry, ItemStack stack) {
        if (entry.parent().isEmpty()) {
            return stack;
        }
        ItemStack rest = stack.copy();
        var parts = EquipmentKits.parts(body, profile).getOrDefault(entry.parent().orElseThrow(), List.of());
        for (var parent : parts) {
            var contents = parent.children();
            if (contents.isEmpty()) {
                continue;
            }
            for (int slot = 0; slot < contents.get().getSlots() && !rest.isEmpty(); slot++) {
                rest = contents.get().insertItem(slot, rest, false);
            }
        }
        if (rest.getCount() != stack.getCount()) {
            body.pack().setChanged();
        }
        return rest;
    }

    static void reconcile(Body body, EquipmentProfile profile) {
        var claimed = EquipmentKits.parts(body, profile);
        for (var stack : EquipmentKits.worn(body)) {
            trim(body, new EquipmentKits.Part(stack, List.of(), stack), claimed);
        }
        // Goods already fetched into the pack can be installed without waiting for another delivery.
        for (var entry : profile.entries()) {
            if (entry.parent().isEmpty()) {
                continue;
            }
            int held = EquipmentKits.parts(body, profile).getOrDefault(entry.id(), List.of()).stream()
                .mapToInt(part -> part.stack().getCount()).sum();
            for (int slot = 0; slot < body.pack().getContainerSize() && held < entry.target(); slot++) {
                ItemStack candidate = body.pack().getItem(slot);
                if (!entry.matches(candidate)) {
                    continue;
                }
                ItemStack taken = body.pack().removeItem(slot, Math.min(entry.target() - held, candidate.getCount()));
                ItemStack rest = install(body, profile, entry, taken);
                held += taken.getCount() - rest.getCount();
                if (!rest.isEmpty()) {
                    body.pack().setItem(slot, merge(body.pack().getItem(slot), rest));
                }
            }
        }
    }

    private static ItemStack merge(ItemStack there, ItemStack rest) {
        return there.isEmpty() ? rest : there.copyWithCount(there.getCount() + rest.getCount());
    }

    private static void trim(Body body, EquipmentKits.Part parent, Map<UUID, List<EquipmentKits.Part>> claimed) {
        parent.children().ifPresent(contents -> {
            for (int slot = 0; slot < contents.getSlots(); slot++) {
                ItemStack stack = contents.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                var child = parent.child(slot, stack);
                int kept = claimed.values().stream().flatMap(List::stream)
                    .filter(part -> part.root() == parent.root() && part.path().equals(child.path()))
                    .mapToInt(part -> part.stack().getCount()).sum();
                if (kept < stack.getCount() && contents.extractItem(slot, stack.getCount() - kept, true).isEmpty()) {
                    continue;
                }
                trim(body, child, claimed);
                int surplus = contents.getStackInSlot(slot).getCount() - kept;
                if (surplus > 0) {
                    ItemStack taken = contents.extractItem(slot, surplus, false);
                    ItemStack rest = body.pack().insert(taken);
                    if (!rest.isEmpty()) {
                        // The native extraction already succeeded; dropping an overflow preserves ownership.
                        body.mob().spawnAtLocation(rest);
                    }
                }
            }
        });
    }
}
