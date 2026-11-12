package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.shell.crafting.InsertionReceipts;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class Transfers {

    private Transfers() {
    }

    static TransferResult take(ServerLevel level, BlockPos pos, MEStorage storage, IActionSource source,
                               List<Pull> requested, boolean simulate) {
        Map<AEItemKey, Long> available = new LinkedHashMap<>();
        for (var entry : storage.getAvailableStacks()) {
            if (entry.getKey() instanceof AEItemKey item) {
                available.put(item, entry.getLongValue());
            }
        }
        Map<AEItemKey, Long> wanted = new LinkedHashMap<>();
        for (Pull pull : requested) {
            long left = pull instanceof Pull.Exact exact ? exact.stack().getCount() : ((Pull.Matching) pull).count();
            for (var entry : available.entrySet()) {
                boolean matches = pull instanceof Pull.Exact exact
                    ? entry.getKey().matches(exact.stack())
                    : Goods.matches(entry.getKey().toStack(), ((Pull.Matching) pull).spec());
                if (!matches) {
                    continue;
                }
                long count = Math.min(left, Math.max(0, entry.getValue() - wanted.getOrDefault(entry.getKey(), 0L)));
                if (count > 0) {
                    wanted.merge(entry.getKey(), count, Math::addExact);
                    left -= count;
                }
                if (left == 0) {
                    break;
                }
            }
            if (left > 0) {
                return refused(TransferResult.Why.NOTHING_TO_TAKE);
            }
        }
        for (var entry : wanted.entrySet()) {
            if (storage.extract(entry.getKey(), entry.getValue(), Actionable.SIMULATE, source) != entry.getValue()) {
                return refused(TransferResult.Why.NOTHING_TO_TAKE);
            }
        }
        return transfer(level, pos, storage, source, wanted, false, simulate);
    }

    static TransferResult put(ServerLevel level, BlockPos pos, MEStorage storage, IActionSource source,
                              List<ItemStack> goods, Room.Pool room, long typeCost, boolean simulate) {
        Map<AEItemKey, Long> wanted = new LinkedHashMap<>();
        for (ItemStack stack : goods) {
            if (!stack.isEmpty()) {
                wanted.merge(AEItemKey.of(stack), (long) stack.getCount(), Math::addExact);
            }
        }
        long spent = 0;
        int kinds = 0;
        for (var entry : wanted.entrySet()) {
            long capacity = storage.insert(entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE, source);
            // Every earlier key may contend for this same cell, including its type bytes. Single-key requests
            // can also use providers whose capacity we cannot describe as a shared pool.
            if (entry.getValue() > Math.max(0, capacity - spent - kinds * typeCost)
                    || wanted.size() > 1 && spent + entry.getValue() > room.items()) {
                return refused(TransferResult.Why.NO_ROOM);
            }
            spent += entry.getValue();
            kinds++;
        }
        return transfer(level, pos, storage, source, wanted, true, simulate);
    }

    private static TransferResult transfer(ServerLevel level, BlockPos pos, MEStorage storage, IActionSource source,
                                           Map<AEItemKey, Long> wanted, boolean insert, boolean simulate) {
        if (simulate) {
            return new TransferResult.Done(stacks(wanted));
        }
        try (var receipts = new InsertionReceipts()) {
            var before = storage.getAvailableStacks();
            Map<AEItemKey, Long> moved = new LinkedHashMap<>();
            for (var entry : wanted.entrySet()) {
                long count = insert ? storage.insert(entry.getKey(), entry.getValue(), Actionable.MODULATE, source)
                    : storage.extract(entry.getKey(), entry.getValue(), Actionable.MODULATE, source);
                if (count > 0) {
                    moved.put(entry.getKey(), count);
                }
                if (count != entry.getValue()) {
                    boolean restored = compensate(storage, source, moved, insert);
                    StorePorts.condemn(level, WorldPos.of(level, pos), Devices.reason(
                        restored ? "changed_transfer" : "failed_compensation"));
                    StorePorts.changed(level, pos);
                    return refused(TransferResult.Why.UNUSABLE);
                }
            }
            var after = storage.getAvailableStacks();
            var expected = new appeng.api.stacks.KeyCounter();
            expected.addAll(before);
            wanted.forEach((key, amount) -> expected.add(key, insert ? amount - receipts.count(key) : -amount));
            var keys = new java.util.HashSet<>(expected.keySet());
            keys.addAll(after.keySet());
            boolean conserved = keys.stream().allMatch(key -> expected.get(key) == after.get(key));
            if (!conserved) {
                Map<AEItemKey, Long> observed = new LinkedHashMap<>();
                wanted.forEach((key, amount) -> {
                    long delta = insert ? after.get(key) - before.get(key) : before.get(key) - after.get(key);
                    if (delta > 0) {
                        observed.put(key, Math.min(amount, delta));
                    }
                });
                compensate(storage, source, observed, insert);
                StorePorts.condemn(level, WorldPos.of(level, pos), Devices.reason("failed_compensation"));
                StorePorts.changed(level, pos);
                return refused(TransferResult.Why.UNUSABLE);
            }
            StorePorts.changed(level, pos);
            return new TransferResult.Done(stacks(wanted));
        }
    }

    private static boolean compensate(MEStorage storage, IActionSource source, Map<AEItemKey, Long> moved,
                                      boolean inserted) {
        boolean restored = true;
        var entries = new ArrayList<>(moved.entrySet());
        for (var entry : entries.reversed()) {
            long count = inserted
                ? storage.extract(entry.getKey(), entry.getValue(), Actionable.MODULATE, source)
                : storage.insert(entry.getKey(), entry.getValue(), Actionable.MODULATE, source);
            restored &= count == entry.getValue();
        }
        return restored;
    }

    private static List<ItemStack> stacks(Map<AEItemKey, Long> goods) {
        List<ItemStack> stacks = new ArrayList<>();
        goods.forEach((key, amount) -> {
            long left = amount;
            while (left > 0) {
                int count = (int) Math.min(left, key.getMaxStackSize());
                stacks.add(key.toStack(count));
                left -= count;
            }
        });
        return List.copyOf(stacks);
    }

    private static TransferResult refused(TransferResult.Why why) {
        return new TransferResult.Refused(why);
    }
}
