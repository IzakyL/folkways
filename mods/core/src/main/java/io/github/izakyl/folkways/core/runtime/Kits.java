package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.colony.api.KitRules;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.Message;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.internal.HaulWork;
import io.github.izakyl.folkways.core.work.internal.Kit;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.world.item.ItemStack;

final class Kits {

    private final Map<UUID, Set<UUID>> asked = new HashMap<>();

    void read(Colony colony, Body body, Consumer<Message> to) {
        read(colony, body, KitRules.supplies(colony, body), to);
    }

    void read(Colony colony, Body body, boolean supply, Consumer<Message> to) {
        if (!supply) {
            left(body.id(), to);
            return;
        }
        InventorySnapshot.Resident observed = colony.inventory().snapshot().residents().get(body.id());
        if (observed == null || observed.state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObservedResident(body.id());
            return;
        }
        List<ItemStack> contents = observed.pack().stream().map(SlotSnapshot::stackCopy).toList();
        Set<UUID> mine = asked.computeIfAbsent(body.id(), ignored -> new LinkedHashSet<>());
        WorkSite at = new WorkSite.AtEntity(body.id(), WorldSpaces.at(body.mob()));
        Set<UUID> still = new HashSet<>();
        for (ItemSpec tool : Kit.of(colony, body).specs()) {
            UUID id = idOf(body.id(), tool);
            boolean carried = contents.stream().anyMatch(stack -> Goods.matches(stack, tool));
            if (!carried) {
                still.add(id);
            }
            if (!carried && mine.add(id)) {
                to.accept(new Message.Submitted(HaulWork.DOMAIN, Grown.of(
                    Delivery.to(id, HaulWork.DOMAIN, at, Stances.WHEREVER, tool, 1, count -> { }))));
            }
        }
        for (Equipment.Demand clothing : Equipment.wanted(colony, body)) {
            if (!clothing.automaticSupply()) {
                continue;
            }
            UUID id = UUID.nameUUIDFromBytes((body.id() + "|clothing|" + clothing.slot().getName()
                + "|" + clothing.items().describe()).getBytes(StandardCharsets.UTF_8));
            ItemStack worn = observed == null ? body.mob().getItemBySlot(clothing.slot())
                : observed.equipment().get(clothing.slot()).stackCopy();
            if (!clothing.matches(worn) && contents.stream().noneMatch(clothing::matches)) {
                still.add(id);
                if (mine.add(id)) {
                    to.accept(new Message.Submitted(HaulWork.DOMAIN, Grown.of(Delivery.to(id, HaulWork.DOMAIN, at,
                        Stances.WHEREVER, clothing.items(), 1, count -> { }))));
                }
            }
        }
        // Asked for, but now carried or no longer in the kit - the colony may have narrowed its tools.
        for (UUID id : List.copyOf(mine)) {
            if (!still.contains(id)) {
                mine.remove(id);
                to.accept(new Message.Withdrawn(HaulWork.DOMAIN, id));
            }
        }
    }

    void ended(UUID node) {
        asked.values().forEach(mine -> mine.remove(node));
    }

    void left(UUID resident, Consumer<Message> to) {
        Set<UUID> mine = asked.remove(resident);
        if (mine != null) {
            mine.forEach(id -> to.accept(new Message.Withdrawn(HaulWork.DOMAIN, id)));
        }
    }

    private static UUID idOf(UUID resident, ItemSpec tool) {
        return UUID.nameUUIDFromBytes((resident + "|" + tool.describe()).getBytes(StandardCharsets.UTF_8));
    }
}
