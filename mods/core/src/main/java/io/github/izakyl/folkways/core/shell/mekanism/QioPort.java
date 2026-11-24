package io.github.izakyl.folkways.core.shell.mekanism;

import net.minecraft.network.chat.Component;
import io.github.izakyl.folkways.core.facility.api.control.SlotWindow;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.PortIdentity;
import io.github.izakyl.folkways.core.facility.api.control.PortReader;
import io.github.izakyl.folkways.core.facility.api.control.Pull;
import io.github.izakyl.folkways.core.facility.api.control.Room;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.TransferResult;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import mekanism.api.Action;
import mekanism.api.security.SecurityMode;
import mekanism.common.content.qio.QIODriveData;
import mekanism.common.content.qio.QIODriveData.QIODriveKey;
import mekanism.common.content.qio.QIOFrequency;
import mekanism.common.lib.inventory.HashedItem;
import mekanism.common.tile.qio.TileEntityQIOComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class QioPort implements ItemPort {

    private record NetworkKey(QIOFrequency frequency, Set<QIODriveKey> drives) {
    }

    private final ServerLevel level;
    private final BlockPos pos;
    private final TileEntityQIOComponent component;
    private final Set<Operation> operations;

    QioPort(ServerLevel level, BlockPos pos, TileEntityQIOComponent component, Set<Operation> operations) {
        this.level = level;
        this.pos = pos.immutable();
        this.component = component;
        this.operations = Set.copyOf(operations);
    }

    private QIOFrequency frequency() {
        return component.getQIOFrequency();
    }

    @Override
    public WorldPos anchor() {
        return identity().home();
    }

    @Override
    public PortIdentity identity() {
        WorldPos home = WorldPos.of(level, pos);
        QIOFrequency frequency = frequency();
        return frequency == null ? PortIdentity.block(home) : new PortIdentity(new NetworkKey(frequency,
            frequency.getAllDrives().stream().map(QIODriveData::getKey).collect(Collectors.toSet())), home);
    }

    @Override
    public Optional<Component> unavailable(ServerLevel level) {
        QIOFrequency frequency = frequency();
        if (frequency == null || !frequency.isValid() || frequency.getAllDrives().isEmpty()) {
            return Optional.of(Component.translatable("folkways.storage.unusable.disconnected"));
        }
        // The colony indexes and reserves physical inventories per dimension. Reject cross-dimension
        // access rather than publishing the same goods independently to two reservation ledgers.
        if (frequency.getAllDrives().stream().anyMatch(drive -> drive.getKey().holder().getLevel() != level)) {
            return Optional.of(Component.translatable("folkways.storage.unusable.remote"));
        }
        if (frequency.getSecurity() != SecurityMode.PUBLIC
            || component.hasSecurity() && component.getSecurity().getMode() != SecurityMode.PUBLIC) {
            return Optional.of(Component.translatable("folkways.storage.unusable.protected"));
        }
        return operations.isEmpty() ? Optional.of(Component.translatable("folkways.storage.unusable.hand_access")) : Optional.empty();
    }

    @Override
    public Set<Operation> operations() {
        return operations;
    }

    @Override
    public OptionalLong revision() {
        // Native automation can change a drive anywhere on this frequency, without a local block event.
        return OptionalLong.of(level.getGameTime() / 20);
    }

    @Override
    public PortReader reader(ServerLevel world, Optional<SlotWindow> window) {
        if (window.isPresent()) {
            return PortReader.EMPTY;
        }
        var cursor = (valid(world) ? frequency().getItemDataMap().entrySet().stream()
            .map(item -> new PortReader.Entry(item.getKey().createStack(1), item.getValue().getCount())).toList()
            : List.<PortReader.Entry>of()).iterator();
        return new PortReader() {
            private final List<Entry> entries = new ArrayList<>();
            private boolean finished;

            @Override
            public boolean step(int budget) {
                while (budget-- > 0 && cursor.hasNext()) {
                    entries.add(cursor.next());
                }
                finished = !cursor.hasNext();
                return finished;
            }

            @Override
            public PortReading result() {
                if (!finished) {
                    throw new IllegalStateException("QIO reading is unfinished");
                }
                return new PortReading(entries, Optional.empty(), valid(world) ? room() : Room.NONE,
                    Set.of(), false);
            }
        };
    }

    Room.Pool room() {
        long items = 0;
        long kinds = Long.MAX_VALUE;
        int existingKinds = frequency().getTotalItemTypes(false);
        for (QIODriveData drive : frequency().getAllDrives()) {
            long free = Math.max(0, drive.getCountCapacity() - drive.getTotalCount());
            // Pool cannot express placement-dependent top-ups. Reserve a slot for every existing network
            // kind on EACH contributing drive, even when it is not there yet; then any booking can spill safely.
            long remainingKinds = (long) drive.getTypeCapacity() - existingKinds;
            if (free > 0 && remainingKinds >= 0) {
                items = Math.addExact(items, free);
                kinds = Math.min(kinds, remainingKinds);
            }
        }
        return new Room.Pool(items, items == 0 ? 0 : kinds);
    }

    private boolean permits(ServerLevel world, Operation operation, Optional<SlotWindow> window) {
        return valid(world) && operations.contains(operation)
            && window.isEmpty();
    }

    @Override
    public TransferResult take(ServerLevel world, List<Pull> requested, Optional<SlotWindow> window, boolean simulate) {
        if (!permits(world, Operation.TAKE, window)) {
            return refused(TransferResult.Why.NOT_ALLOWED);
        }
        var available = snapshot();
        Map<HashedItem, Long> wanted = new LinkedHashMap<>();
        for (Pull pull : requested) {
            long left = pull instanceof Pull.Exact exact ? exact.stack().getCount() : ((Pull.Matching) pull).count();
            for (var entry : available.entrySet()) {
                ItemStack stack = entry.getKey().createStack(1);
                boolean matches = pull instanceof Pull.Exact exact
                    ? ItemStack.isSameItemSameComponents(stack, exact.stack())
                    : Goods.matches(stack, ((Pull.Matching) pull).spec());
                if (matches) {
                    long amount = Math.min(left, entry.getValue() - wanted.getOrDefault(entry.getKey(), 0L));
                    if (amount > 0) {
                        wanted.merge(entry.getKey(), amount, Math::addExact);
                        left -= amount;
                    }
                }
                if (left == 0) {
                    break;
                }
            }
            if (left > 0) {
                return refused(TransferResult.Why.NOTHING_TO_TAKE);
            }
        }
        return transfer(wanted, false, simulate);
    }

    @Override
    public TransferResult put(ServerLevel world, List<ItemStack> goods, Optional<SlotWindow> window, boolean simulate) {
        if (!permits(world, Operation.PUT, window)) {
            return refused(TransferResult.Why.NOT_ALLOWED);
        }
        Map<HashedItem, Long> wanted = new LinkedHashMap<>();
        for (ItemStack stack : goods) {
            if (!stack.isEmpty()) {
                wanted.merge(HashedItem.create(stack), (long) stack.getCount(), Math::addExact);
            }
        }
        if (wanted.size() > 1) {
            Room.Pool room = room();
            long amount = wanted.values().stream().mapToLong(Long::longValue).sum();
            long kinds = wanted.keySet().stream().filter(key -> !frequency().isStoring(key)).count();
            if (amount > room.items() || kinds > room.newKinds()) {
                return refused(TransferResult.Why.NO_ROOM);
            }
        }
        for (var entry : wanted.entrySet()) {
            if (frequency().massInsert(entry.getKey().createStack(1), entry.getValue(), Action.SIMULATE)
                != entry.getValue()) {
                return refused(TransferResult.Why.NO_ROOM);
            }
        }
        return transfer(wanted, true, simulate);
    }

    private Map<HashedItem, Long> snapshot() {
        Map<HashedItem, Long> contents = new LinkedHashMap<>();
        frequency().forAllHashedStored((key, count) -> contents.put(HashedItem.create(key.createStack(1)), count));
        return contents;
    }

    private TransferResult transfer(Map<HashedItem, Long> wanted, boolean insert, boolean simulate) {
        List<ItemStack> moved = new ArrayList<>();
        wanted.forEach((key, count) -> {
            long left = count;
            while (left > 0) {
                int amount = (int) Math.min(left, key.createStack(1).getMaxStackSize());
                moved.add(key.createStack(amount));
                left -= amount;
            }
        });
        if (simulate) {
            return new TransferResult.Done(moved);
        }
        var before = snapshot();
        var expected = new LinkedHashMap<>(before);
        wanted.forEach((key, count) -> expected.merge(key, insert ? count : -count, Math::addExact));
        expected.values().removeIf(count -> count == 0);
        boolean acknowledged = true;
        for (var entry : wanted.entrySet()) {
            long amount = insert
                ? frequency().massInsert(entry.getKey().createStack(1), entry.getValue(), Action.EXECUTE)
                : frequency().massExtract(entry.getKey().createStack(1), entry.getValue(), Action.EXECUTE);
            if (amount != entry.getValue()) {
                acknowledged = false;
                break;
            }
        }
        if (!acknowledged || !expected.equals(snapshot())) {
            compensate(before);
            Ports.observe(level, WorldPos.of(level, pos),
                net.minecraft.network.chat.Component.translatable("folkways.storage.unusable.changed"));
            Ports.changed(level, pos);
            return refused(TransferResult.Why.UNUSABLE);
        }
        Ports.changed(level, pos);
        return new TransferResult.Done(moved);
    }

    private void compensate(Map<HashedItem, Long> before) {
        var after = snapshot();
        var keys = new LinkedHashSet<>(after.keySet());
        keys.addAll(before.keySet());
        for (HashedItem key : keys) {
            long excess = after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
            if (excess > 0) {
                frequency().massExtract(key.createStack(1), excess, Action.EXECUTE);
            }
        }
        for (HashedItem key : keys) {
            long missing = before.getOrDefault(key, 0L) - frequency().getStoredByHash(key);
            if (missing > 0) {
                frequency().massInsert(key.createStack(1), missing, Action.EXECUTE);
            }
        }
    }

    private static TransferResult refused(TransferResult.Why why) {
        return new TransferResult.Refused(why);
    }
}
