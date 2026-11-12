package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.shell.crafting.InsertionReceipts;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class Transfers {

    private Transfers() {
    }

    static TransferResult take(ServerLevel level, BlockPos pos, Storage storage, Actor actor,
                               List<Pull> requested, Predicate<ItemResource> allowed, boolean simulate) {
        Map<ResourceKey, Long> available = snapshot(storage);
        Map<ItemResource, Long> wanted = new LinkedHashMap<>();
        for (Pull pull : requested) {
            long left = pull instanceof Pull.Exact exact ? exact.stack().getCount() : ((Pull.Matching) pull).count();
            for (var entry : available.entrySet()) {
                if (!(entry.getKey() instanceof ItemResource item) || !allowed.test(item)) {
                    continue;
                }
                boolean matches = pull instanceof Pull.Exact exact
                    ? item.equals(ItemResource.ofItemStack(exact.stack()))
                    : Goods.matches(item.toItemStack(), ((Pull.Matching) pull).spec());
                if (!matches) {
                    continue;
                }
                long count = Math.min(left, Math.max(0, entry.getValue() - wanted.getOrDefault(item, 0L)));
                if (count > 0) {
                    wanted.merge(item, count, Math::addExact);
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
            if (storage.extract(entry.getKey(), entry.getValue(), Action.SIMULATE, actor) != entry.getValue()) {
                return refused(TransferResult.Why.NOTHING_TO_TAKE);
            }
        }
        return transfer(level, pos, storage, actor, wanted, false, simulate);
    }

    static TransferResult put(ServerLevel level, BlockPos pos, Storage storage, Actor actor,
                              List<ItemStack> goods, Room.Pool room, boolean simulate) {
        Map<ItemResource, Long> wanted = new LinkedHashMap<>();
        for (ItemStack stack : goods) {
            if (!stack.isEmpty()) {
                wanted.merge(ItemResource.ofItemStack(stack), (long) stack.getCount(), Math::addExact);
            }
        }
        long spent = 0;
        for (var entry : wanted.entrySet()) {
            long capacity = storage.insert(entry.getKey(), Long.MAX_VALUE, Action.SIMULATE, actor);
            // Every earlier key may contend for this same pool. Unknown providers can accept a single key,
            // but cannot promise shared room for a multi-key batch.
            if (entry.getValue() > Math.max(0, capacity - spent)
                    || wanted.size() > 1 && entry.getValue() > room.items() - spent) {
                return refused(TransferResult.Why.NO_ROOM);
            }
            spent = Math.addExact(spent, entry.getValue());
        }
        return transfer(level, pos, storage, actor, wanted, true, simulate);
    }

    private static TransferResult transfer(ServerLevel level, BlockPos pos, Storage storage, Actor actor,
                                           Map<ItemResource, Long> wanted, boolean insert, boolean simulate) {
        if (simulate) {
            return new TransferResult.Done(stacks(wanted));
        }
        try (var receipts = new InsertionReceipts()) {
            var before = snapshot(storage);
            var expected = new LinkedHashMap<>(before);
            wanted.forEach((key, count) -> expected.merge(key, insert ? count : -count, Math::addExact));
            expected.values().removeIf(count -> count == 0);
            boolean acknowledged = true;
            try {
                for (var entry : wanted.entrySet()) {
                    long count = insert ? storage.insert(entry.getKey(), entry.getValue(), Action.EXECUTE, actor)
                        : storage.extract(entry.getKey(), entry.getValue(), Action.EXECUTE, actor);
                    if (count != entry.getValue()) {
                        acknowledged = false;
                        break;
                    }
                }
            } catch (RuntimeException failure) {
                acknowledged = false;
            }
            if (insert) {
                wanted.forEach((key, count) -> expected.merge(key, -receipts.count(key), Math::addExact));
                expected.values().removeIf(count -> count == 0);
            }
            if (!acknowledged || !expected.equals(snapshot(storage))) {
                boolean restored = compensate(storage, actor, before);
                StorePorts.condemn(level, WorldPos.of(level, pos), Devices.reason(
                    restored ? "changed_transfer" : "failed_compensation"));
                StorePorts.changed(level, pos);
                return refused(TransferResult.Why.UNUSABLE);
            }
            StorePorts.changed(level, pos);
            return new TransferResult.Done(stacks(wanted));
        }
    }

    private static boolean compensate(Storage storage, Actor actor, Map<ResourceKey, Long> before) {
        var after = snapshot(storage);
        var keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (var key : new ArrayList<>(keys).reversed()) {
            long delta = after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
            try {
                if (delta > 0) {
                    storage.extract(key, delta, Action.EXECUTE, actor);
                } else if (delta < 0) {
                    storage.insert(key, -delta, Action.EXECUTE, actor);
                }
            } catch (RuntimeException failure) {
                return false;
            }
        }
        return before.equals(snapshot(storage));
    }

    private static Map<ResourceKey, Long> snapshot(Storage storage) {
        Map<ResourceKey, Long> contents = new LinkedHashMap<>();
        for (var entry : storage.getAll()) {
            if (entry.amount() > 0) {
                contents.merge(entry.resource(), entry.amount(), Math::addExact);
            }
        }
        return contents;
    }

    private static List<ItemStack> stacks(Map<ItemResource, Long> goods) {
        List<ItemStack> stacks = new ArrayList<>();
        goods.forEach((key, amount) -> {
            long left = amount;
            while (left > 0) {
                int count = (int) Math.min(left, key.toItemStack().getMaxStackSize());
                stacks.add(key.toItemStack(count));
                left -= count;
            }
        });
        return List.copyOf(stacks);
    }

    private static TransferResult refused(TransferResult.Why why) {
        return new TransferResult.Refused(why);
    }
}
