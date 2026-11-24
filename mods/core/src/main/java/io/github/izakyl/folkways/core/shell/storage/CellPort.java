package io.github.izakyl.folkways.core.shell.storage;

import io.github.izakyl.folkways.core.facility.api.control.TransformHint;
import io.github.izakyl.folkways.core.facility.api.control.SlotWindow;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.SlotSnapshot;
import io.github.izakyl.folkways.core.facility.api.control.PortIdentity;
import io.github.izakyl.folkways.core.facility.api.control.PortReader;
import io.github.izakyl.folkways.core.facility.api.control.Pull;
import io.github.izakyl.folkways.core.facility.api.control.Room;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.TransferResult;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Native cells with audited setters: plan the entire batch on copies before touching any live cell. */
public class CellPort implements ItemPort {

    public interface Cell {

        ItemStack prototype();

        int count();

        int limit(ItemStack stack);

        boolean accepts(ItemStack stack);

        default boolean extracts(ItemStack stack) {
            return true;
        }

        default boolean locked() {
            return false;
        }

        default boolean accessible() {
            return true;
        }

        void write(ItemStack prototype, int count);
    }

    private record State(ItemStack prototype, int count) {

        private State {
            prototype = prototype.copy();
        }
    }

    private final PortIdentity identity;
    private final List<Cell> cells;
    private final Supplier<StorageTraits> traits;
    private final boolean slots;
    private final LongSupplier settingsRevision;

    public CellPort(PortIdentity identity, List<Cell> cells, Supplier<StorageTraits> traits, boolean slots) {
        this(identity, cells, traits, slots, () -> 0L);
    }

    public CellPort(PortIdentity identity, List<Cell> cells, Supplier<StorageTraits> traits, boolean slots,
                    LongSupplier settingsRevision) {
        this.identity = identity;
        this.cells = List.copyOf(cells);
        this.traits = traits;
        this.slots = slots;
        this.settingsRevision = settingsRevision;
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
    public Optional<Component> unavailable(ServerLevel level) {
        return traits.get().unavailable();
    }

    @Override
    public List<TransformHint> transforms() {
        return traits.get().transforms();
    }

    @Override
    public Set<Operation> operations() {
        return Set.of(Operation.TAKE, Operation.PUT);
    }

    @Override
    public OptionalLong revision() {
        StorageTraits current = traits.get();
        long signature = 31L * current.hashCode() + settingsRevision.getAsLong();
        if (current.unavailable().isPresent()) {
            return OptionalLong.of(signature);
        }
        // Native IO can change an unregistered network member, away from this access point's change events.
        // Hash live cell data without serializing NBT so controller-only registrations also notice those moves.
        for (Cell cell : cells) {
            ItemStack prototype = cell.prototype();
            signature = 31L * signature + ItemStack.hashItemAndComponents(prototype);
            signature = 31L * signature + cell.count();
            signature = 31L * signature + cell.limit(prototype.isEmpty() ? new ItemStack(Items.STONE) : prototype);
            signature = 31L * signature + (cell.locked() ? 1 : 0);
            signature = 31L * signature + (cell.accessible() ? 1 : 0);
        }
        return OptionalLong.of(signature);
    }

    private boolean accessible(int index, Optional<SlotWindow> window) {
        return cells.get(index).accessible() && window.map(range -> range.contains(index)).orElse(true);
    }

    @Override
    public PortReader reader(ServerLevel level, Optional<SlotWindow> window) {
        return new PortReader() {
            private final List<Entry> entries = new ArrayList<>();
            private final List<SlotSnapshot> snapshots = new ArrayList<>();
            private final List<Room.Cell> bound = new ArrayList<>();
            private final Map<Item, Long> limits = new HashMap<>();
            private final List<Cell> available = new ArrayList<>();
            private final Iterator<Item> samples = BuiltInRegistries.ITEM.iterator();
            private int cursor;
            private long open;
            private long multiplier = Long.MAX_VALUE;
            private boolean finished;
            private PortReading reading;

            @Override
            public boolean step(int budget) {
                if (finished) {
                    return true;
                }
                if (!valid(level)) {
                    reading = new PortReading(List.of(), Optional.empty(), Room.NONE, Set.of(), false);
                    finished = true;
                    return true;
                }
                while (cursor < cells.size() && budget-- > 0) {
                    int index = cursor++;
                    Cell cell = cells.get(index);
                    if (!accessible(index, window)) {
                        continue;
                    }
                    available.add(cell);
                    ItemStack prototype = cell.prototype();
                    int count = cell.count();
                    int limit = cell.limit(prototype.isEmpty() ? new ItemStack(Items.STONE) : prototype);
                    if (count > 0 && !prototype.isEmpty()) {
                        add(entries, prototype, count);
                    }
                    multiplier = Math.min(multiplier, cell.limit(new ItemStack(Items.DIAMOND_PICKAXE)));
                    if (slots) {
                        snapshots.add(new SlotSnapshot(index, snapshots.size(), prototype.copyWithCount(count), limit));
                    }
                    if (!prototype.isEmpty()) {
                        bound.add(new Room.Cell(prototype, Math.max(0L, (long) limit - count)));
                    } else if (!cell.locked()) {
                        open++;
                    }
                }
                while (cursor == cells.size() && samples.hasNext() && budget-- > 0) {
                    Item item = samples.next();
                    ItemStack stack = item.getDefaultInstance();
                    long cap = available.stream().mapToLong(cell -> cell.limit(stack)).min().orElse(0);
                    if (cap != new Room.Cap(Long.MAX_VALUE, multiplier).limit(stack)) {
                        limits.put(item, cap);
                    }
                }
                if (cursor == cells.size() && !samples.hasNext()) {
                    Room room = new Room.Slots(bound, open,
                        new Room.Cap(Long.MAX_VALUE, available.isEmpty() ? 0 : multiplier, limits));
                    reading = new PortReading(entries, slots ? Optional.of(snapshots) : Optional.empty(), room,
                        Set.of(), true);
                    finished = true;
                }
                return finished;
            }

            @Override
            public PortReading result() {
                if (!finished) {
                    throw new IllegalStateException("storage read is unfinished");
                }
                return reading;
            }
        };
    }

    private static void add(List<PortReader.Entry> entries, ItemStack stack, long count) {
        for (int i = 0; i < entries.size(); i++) {
            PortReader.Entry entry = entries.get(i);
            if (ItemStack.isSameItemSameComponents(entry.prototype(), stack)) {
                entries.set(i, new PortReader.Entry(stack, Math.addExact(entry.count(), count)));
                return;
            }
        }
        entries.add(new PortReader.Entry(stack, count));
    }

    private List<State> snapshot() {
        return cells.stream().map(cell -> new State(cell.prototype(), cell.count())).toList();
    }

    @Override
    public TransferResult take(ServerLevel level, List<Pull> requested, Optional<SlotWindow> window, boolean simulate) {
        if (!valid(level)) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }
        List<State> before = snapshot();
        List<State> after = new ArrayList<>(before);
        List<ItemStack> moved = new ArrayList<>();
        for (Pull pull : requested) {
            long remaining = pull instanceof Pull.Exact exact ? exact.stack().getCount()
                : ((Pull.Matching) pull).count();
            for (int i = 0; i < cells.size() && remaining > 0; i++) {
                State state = after.get(i);
                if (!accessible(i, window) || state.count == 0 || !cells.get(i).extracts(state.prototype)) {
                    continue;
                }
                boolean matches = pull instanceof Pull.Exact exact
                    ? ItemStack.isSameItemSameComponents(state.prototype, exact.stack())
                    : Goods.matches(state.prototype, ((Pull.Matching) pull).spec());
                if (!matches) {
                    continue;
                }
                int amount = (int) Math.min(remaining, state.count);
                int left = state.count - amount;
                after.set(i, new State(left == 0 && !cells.get(i).locked() ? ItemStack.EMPTY : state.prototype, left));
                remaining -= amount;
                int cargo = amount;
                while (cargo > 0) {
                    int size = Math.min(cargo, state.prototype.getMaxStackSize());
                    moved.add(state.prototype.copyWithCount(size));
                    cargo -= size;
                }
            }
            if (remaining > 0) {
                return new TransferResult.Refused(TransferResult.Why.NOTHING_TO_TAKE);
            }
        }
        return commit(level, before, after, moved, simulate);
    }

    @Override
    public TransferResult put(ServerLevel level, List<ItemStack> goods, Optional<SlotWindow> window, boolean simulate) {
        if (!valid(level)) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }
        List<State> before = snapshot();
        List<State> after = new ArrayList<>(before);
        for (ItemStack stack : goods) {
            int remaining = stack.getCount();
            // Fill existing bindings first, leaving open cells available for other goods in this batch.
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int i = 0; i < cells.size() && remaining > 0; i++) {
                    Cell cell = cells.get(i);
                    State state = after.get(i);
                    if (!accessible(i, window) || !cell.accepts(stack) || (pass == 0) == state.prototype.isEmpty()
                        || state.prototype.isEmpty() && cell.locked()
                        || !state.prototype.isEmpty() && !ItemStack.isSameItemSameComponents(state.prototype, stack)) {
                        continue;
                    }
                    int amount = (int) Math.min(remaining, Math.max(0L, (long) cell.limit(stack) - state.count));
                    if (amount > 0) {
                        after.set(i, new State(stack.copyWithCount(1), state.count + amount));
                        remaining -= amount;
                    }
                }
            }
            if (remaining > 0) {
                return new TransferResult.Refused(TransferResult.Why.NO_ROOM);
            }
        }
        return commit(level, before, after, goods, simulate);
    }

    private TransferResult commit(ServerLevel level, List<State> before, List<State> after,
                                  List<ItemStack> moved, boolean simulate) {
        if (simulate) {
            return new TransferResult.Done(moved);
        }
        for (int i = 0; i < cells.size(); i++) {
            if (!same(before.get(i), after.get(i))) {
                State state = after.get(i);
                cells.get(i).write(state.prototype, state.count);
            }
        }
        if (!same(snapshot(), after)) {
            for (int i = 0; i < cells.size(); i++) {
                State state = before.get(i);
                cells.get(i).write(state.prototype, state.count);
            }
            Ports.observe(level, identity.home(), Component.translatable("folkways.storage.unusable.changed"));
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }
        Ports.changed(level, identity.home().block(level));
        return new TransferResult.Done(moved);
    }

    private static boolean same(List<State> first, List<State> second) {
        for (int i = 0; i < first.size(); i++) {
            if (!same(first.get(i), second.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(State first, State second) {
        return first.count == second.count && (first.prototype.isEmpty() && second.prototype.isEmpty()
            || ItemStack.isSameItemSameComponents(first.prototype, second.prototype));
    }
}
