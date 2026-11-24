package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.resident.api.Pack;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.ReservationPool;
import io.github.izakyl.folkways.core.work.api.ReservedInput;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** An item movement by one resident. A null endpoint denotes that resident's pack. */
record PostTransfer(ControlRef from, ControlRef into, List<Amount> wanted, boolean all) {

    static PostTransfer deposit(ControlRef into, Need need) {
        return new PostTransfer(null, into, List.of(new Amount(need.spec(), need.count())), false);
    }

    List<ControlRef> controls() {
        if (from == null) {
            return List.of(into);
        }
        return into == null ? List.of(from) : List.of(from, into);
    }

    static Optional<Port<ItemStack>> port(ServerLevel level, ControlRef ref) {
        return Ports.at(level, ref.at(), ResourceKind.ITEM, ref.part()).filter(port -> port.valid(level));
    }

    long missing(Worker worker, Amount amount, ReservationPool reservations, List<ReservedInput> reserved) {
        var available = available(null, null, worker, reservations, reserved);
        long held = available.stream().filter(stack -> Goods.matches(stack, amount.spec()))
            .mapToLong(ItemStack::getCount).sum();
        return Math.max(0, amount.count() - held);
    }

    RefusalKind move(ServerLevel level, Worker worker, ReservationPool reservations, List<ReservedInput> reserved) {
        Port<ItemStack> source = from == null ? null : port(level, from).orElse(null);
        Port<ItemStack> destination = into == null ? null : port(level, into).orElse(null);
        if (from != null && source == null || into != null && destination == null) {
            return FacilityRefusal.GONE;
        }
        List<Lease> taken = new ArrayList<>();
        try {
            for (ControlRef ref : controls()) {
                Port<ItemStack> port = ref.equals(from) ? source : destination;
                if (!port.admits(worker.body())) {
                    return FacilityRefusal.REFUSED;
                }
                boolean held = Lease.held(port).isPresent();
                var lease = Lease.take(port, worker);
                if (lease.isEmpty()) {
                    return FacilityRefusal.BUSY;
                }
                if (!held) {
                    taken.add(lease.get());
                }
            }
            return move(level, worker, reservations, reserved, source, destination);
        } finally {
            taken.forEach(Lease::close);
        }
    }

    private List<ItemStack> available(ServerLevel level, Port<ItemStack> source, Worker worker,
            ReservationPool reservations, List<ReservedInput> reserved) {
        List<ItemStack> available = new ArrayList<>();
        if (source == null) {
            Container pack = worker.pack();
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                available.add(pack.getItem(slot).copy());
            }
        } else {
            source.contents(level).forEach(stack -> available.add(stack.copy()));
        }
        var resident = worker.resident().id();
        for (var input : reserved) {
            if (source == null ? input.resident().filter(resident::equals).isPresent()
                    : input.store().filter(from.at()::equals).isPresent()) {
                setAside(available, input.goods(), input.count());
            }
        }
        if (source == null && reservations != null) {
            for (var output : reservations.carried()) {
                if (output.resident().equals(resident)) {
                    setAside(available, output.spec(), output.count());
                }
            }
        }
        return available;
    }

    private static void setAside(List<ItemStack> available, ItemSpec spec, long count) {
        long left = count;
        for (ItemStack stack : available) {
            if (left > 0 && Goods.matches(stack, spec)) {
                int reserved = (int) Math.min(left, stack.getCount());
                stack.shrink(reserved);
                left -= reserved;
            }
        }
    }

    private RefusalKind move(ServerLevel level, Worker worker, ReservationPool reservations, List<ReservedInput> reserved,
            Port<ItemStack> source, Port<ItemStack> destination) {
        Container pack = worker.pack();
        var available = available(level, source, worker, reservations, reserved);
        List<ItemStack> selected = new ArrayList<>();
        if (all) {
            available.stream().filter(stack -> !stack.isEmpty()).forEach(selected::add);
        }
        for (Amount amount : wanted) {
            long left = amount.count();
            for (ItemStack stack : available) {
                if (left > 0 && !stack.isEmpty() && Goods.matches(stack, amount.spec())) {
                    selected.add(stack.split((int) Math.min(left, stack.getCount())));
                    left -= selected.getLast().getCount();
                }
            }
            if (left > 0) {
                return source == null ? FacilityRefusal.POST_GOODS_MISSING : FacilityRefusal.REFUSED;
            }
        }
        if (destination == null && !fits(pack, selected)) {
            return FacilityRefusal.BUSY;
        }
        for (ItemStack stack : selected) {
            if (source != null && source.draw(level, worker.body(), stack, true).getCount() != stack.getCount()) {
                return FacilityRefusal.REFUSED;
            }
            if (destination != null) {
                destination.prepareDeposit(level, worker.body(), stack);
                if (destination.deposit(level, worker.body(), stack, true).getCount() != stack.getCount()) {
                    return FacilityRefusal.BUSY;
                }
            }
        }
        try {
            for (ItemStack stack : selected) {
                ItemStack drawn = source == null
                    ? Containers.extract(pack, candidate -> ItemStack.isSameItemSameComponents(stack, candidate),
                        stack.getCount())
                    : source.draw(level, worker.body(), stack, false);
                ItemStack left;
                if (destination == null) {
                    left = Containers.insert(pack, drawn);
                    if (reservations != null && left.getCount() < drawn.getCount()) {
                        reservations.carried(worker.resident().id(),
                            drawn.copyWithCount(drawn.getCount() - left.getCount()));
                    }
                } else {
                    var inserted = drawn.isEmpty() ? ItemStack.EMPTY
                        : destination.deposit(level, worker.body(), drawn, false);
                    left = drawn.copyWithCount(drawn.getCount() - inserted.getCount());
                }
                if (!left.isEmpty()) {
                    if (source != null) {
                        int restored = source.deposit(level, worker.body(), left, false).getCount();
                        left = left.copyWithCount(left.getCount() - restored);
                    }
                    worker.spill(Containers.insert(pack, left));
                }
                if (drawn.getCount() != stack.getCount() || !left.isEmpty()) {
                    return FacilityRefusal.BUSY;
                }
            }
            return null;
        } finally {
            InventoryFeedback.resident(worker.body());
        }
    }

    static boolean fits(Container pack, List<ItemStack> goods) {
        var contents = new ArrayList<ItemStack>();
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            contents.add(pack.getItem(slot).copy());
        }
        for (ItemStack stack : goods) {
            int left = stack.getCount();
            for (int slot = 0; slot < contents.size() && left > 0; slot++) {
                ItemStack there = contents.get(slot);
                if (!there.isEmpty() && !ItemStack.isSameItemSameComponents(there, stack)) {
                    continue;
                }
                int limit = pack instanceof Pack residentPack ? residentPack.slotLimit(slot, stack)
                    : Containers.accepts(pack, slot, stack) ? pack.getMaxStackSize(stack) : 0;
                int count = Math.min(left, Math.max(0, Math.min(limit, stack.getMaxStackSize()) - there.getCount()));
                if (count > 0) {
                    contents.set(slot, stack.copyWithCount(there.getCount() + count));
                    left -= count;
                }
            }
            if (left > 0) {
                return false;
            }
        }
        return true;
    }
}
