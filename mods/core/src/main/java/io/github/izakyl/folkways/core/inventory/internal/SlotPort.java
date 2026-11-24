package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.facility.api.control.SlotWindow;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.SlotSnapshot;
import io.github.izakyl.folkways.core.facility.api.control.PortIdentity;
import io.github.izakyl.folkways.core.facility.api.control.PortReader;
import io.github.izakyl.folkways.core.facility.api.control.Pull;
import io.github.izakyl.folkways.core.facility.api.control.Room;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.TransferResult;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

// Native slots retain their copy, simulate, and whole-inventory writeback semantics.
public final class SlotPort implements ItemPort {

    private static final ClassValue<Boolean> CUSTOM_LIMIT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> kind) {
            try {
                return kind.getMethod("getMaxStackSize", ItemStack.class).getDeclaringClass() != Container.class;
            } catch (NoSuchMethodException unexpected) {
                return true;
            }
        }
    };

    private final PortIdentity identity;
    private final Container container;
    private SlotObservation observation;

    public SlotPort(PortIdentity identity, Container container) {
        this.identity = identity;
        this.container = container;
    }

    SlotPort observing(SlotObservation observation) {
        SlotPort port = new SlotPort(identity, container);
        port.observation = observation;
        return port;
    }

    public Container container() {
        return container;
    }

    @Override
    public WorldPos anchor() {
        return identity().home();
    }

    @Override
    public PortIdentity identity() {
        return identity;
    }

    @Override
    public Set<Operation> operations() {
        return Set.of(Operation.TAKE, Operation.PUT);
    }

    @Override
    public PortReader reader(ServerLevel level, Optional<SlotWindow> window) {
        return new PortReader() {
            private final List<SlotSnapshot> slots = new ArrayList<>();
            private final List<Entry> entries = new ArrayList<>();
            private final List<Room.Cell> partial = new ArrayList<>();
            private final Map<Item, Long> limits = new HashMap<>();
            private final java.util.Iterator<Item> samples = CUSTOM_LIMIT.get(container.getClass())
                ? BuiltInRegistries.ITEM.iterator() : java.util.Collections.emptyIterator();
            private long free;

            @Override
            public boolean step(int budget) {
                while (slots.size() < container.getContainerSize() && budget-- > 0) {
                    int slot = slots.size();
                    int accessible = window.map(range -> range.accessibleSlot(slot)).orElse(slot);
                    ItemStack stack = container.getItem(slot);
                    int limit = stack.isEmpty() ? container.getMaxStackSize() : container.getMaxStackSize(stack);
                    slots.add(observation == null ? new SlotSnapshot(slot, accessible, stack, limit)
                        : observation.capture(slot, accessible, stack, limit));
                    if (accessible >= 0) {
                        if (stack.isEmpty()) {
                            free++;
                        } else {
                            add(entries, stack);
                            int cap = container.getMaxStackSize(stack);
                            if (cap > stack.getCount()) {
                                partial.add(new Room.Cell(stack, cap - stack.getCount()));
                            }
                        }
                    }
                }
                while (slots.size() == container.getContainerSize() && samples.hasNext() && budget-- > 0) {
                    Item item = samples.next();
                    ItemStack stack = item.getDefaultInstance();
                    int cap = container.getMaxStackSize(stack);
                    if (cap != Math.min(container.getMaxStackSize(), stack.getMaxStackSize())) {
                        limits.put(item, (long) cap);
                    }
                }
                return slots.size() == container.getContainerSize() && !samples.hasNext();
            }

            @Override
            public PortReading result() {
                return new PortReading(entries, Optional.of(slots),
                    new Room.Slots(partial, free, new Room.Cap(container.getMaxStackSize(), 1, limits)),
                    Set.of(), true);
            }
        };
    }

    private static void add(List<PortReader.Entry> entries, ItemStack stack) {
        for (int at = 0; at < entries.size(); at++) {
            PortReader.Entry had = entries.get(at);
            if (ItemStack.isSameItemSameComponents(had.prototype(), stack)) {
                entries.set(at, new PortReader.Entry(stack, had.count() + stack.getCount()));
                return;
            }
        }
        entries.add(new PortReader.Entry(stack, stack.getCount()));
    }

    @Override
    public TransferResult take(ServerLevel level, List<Pull> requested, Optional<SlotWindow> window, boolean simulate) {
        Container accessible = Stores.window(container, window).orElse(null);
        if (accessible == null) {
            return new TransferResult.Refused(TransferResult.Why.NOTHING_TO_TAKE);
        }
        SimpleContainer taken = snapshot(accessible);
        List<ItemStack> moved = new ArrayList<>();
        for (Pull pull : requested) {
            long left = switch (pull) {
                case Pull.Exact exact -> exact.stack().getCount();
                case Pull.Matching matching -> matching.count();
            };
            while (left > 0) {
                ItemStack lifted = Containers.extract(taken, stack -> switch (pull) {
                    case Pull.Exact exact -> ItemStack.isSameItemSameComponents(stack, exact.stack());
                    case Pull.Matching matching -> Goods.matches(stack, matching.spec());
                }, (int) Math.min(left, Integer.MAX_VALUE));
                if (lifted.isEmpty()) {
                    return new TransferResult.Refused(TransferResult.Why.NOTHING_TO_TAKE);
                }
                left -= lifted.getCount();
                moved.add(lifted);
            }
        }
        if (!simulate) {
            copy(taken, accessible);
            Ports.changed(level, identity.home().block(level));
        }
        return new TransferResult.Done(moved);
    }

    @Override
    public TransferResult put(ServerLevel level, List<ItemStack> goods, Optional<SlotWindow> window, boolean simulate) {
        Container accessible = Stores.window(container, window).orElse(null);
        if (accessible == null) {
            return new TransferResult.Refused(TransferResult.Why.NO_ROOM);
        }
        SimpleContainer placed = snapshot(accessible);
        for (ItemStack stack : goods) {
            if (!Containers.insert(placed, stack).isEmpty()) {
                return new TransferResult.Refused(TransferResult.Why.NO_ROOM);
            }
        }
        if (!simulate) {
            copy(placed, accessible);
            Ports.changed(level, identity.home().block(level));
        }
        return new TransferResult.Done(goods);
    }

    public static SimpleContainer snapshot(Container container) {
        SimpleContainer snapshot = new SimpleContainer(container.getContainerSize()) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return Containers.accepts(container, slot, stack);
            }

            @Override
            public int getMaxStackSize() {
                return container.getMaxStackSize();
            }

            @Override
            public int getMaxStackSize(ItemStack stack) {
                return container.getMaxStackSize(stack);
            }
        };
        copy(container, snapshot);
        return snapshot;
    }

    public static void copy(Container from, Container to) {
        for (int slot = 0; slot < from.getContainerSize(); slot++) {
            if (!ItemStack.matches(from.getItem(slot), to.getItem(slot))) {
                to.setItem(slot, from.getItem(slot).copy());
            }
        }
        to.setChanged();
    }
}
