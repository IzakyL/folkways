package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/** Native requester outputs belong to their collecting node until it collects or releases the order. */
public final class OrderedOutputs {

    private static final ResourceLocation KEY = ResourceLocation.fromNamespaceAndPath(
        "folkways_core", "ae2_ordered_outputs");

    private final ResourceLocation key;
    private final Colony colony;
    private final MinecraftServer server;
    private final List<Output> pending = new ArrayList<>();

    public OrderedOutputs(Colony colony, MinecraftServer server) {
        this(colony, server, KEY);
    }

    public OrderedOutputs(Colony colony, MinecraftServer server, ResourceLocation key) {
        this.key = key;
        this.colony = colony;
        this.server = server;
        for (Tag raw : colony.kept(key).getList("outputs", Tag.TAG_COMPOUND)) {
            CompoundTag data = (CompoundTag) raw;
            var device = WorldPos.load(Reader.of(data.getCompound("device")));
            var item = ItemStack.parse(server.registryAccess(), data.getCompound("item"));
            long count = data.getLong("count");
            if (device.isPresent() && item.isPresent() && count > 0) {
                // Accepted planner work is transient. A reopened world returns any uncollected goods.
                Output output = new Output(device.get(), item.get());
                output.count = count;
                output.released = true;
                pending.add(output);
            }
        }
    }

    public Output open(WorldPos device, ItemStack item) {
        return new Output(device, item.copyWithCount(1));
    }

    public void returnAt(WorldPos device, ToLongFunction<ItemStack> insert) {
        boolean changed = false;
        for (Output output : List.copyOf(pending)) {
            if (!output.released || !output.device.equals(device)) {
                continue;
            }
            for (ItemStack stack : stacks(output.item, output.count)) {
                long returned = insert.applyAsLong(stack);
                output.count -= returned;
                changed |= returned > 0;
                if (returned < stack.getCount()) {
                    break;
                }
            }
            if (output.count == 0) {
                pending.remove(output);
            }
        }
        if (changed) {
            save();
        }
    }

    private void save() {
        ListTag outputs = new ListTag();
        for (Output output : pending) {
            CompoundTag data = new CompoundTag();
            data.put("device", output.device.save());
            data.put("item", output.item.save(server.registryAccess()));
            data.putLong("count", output.count);
            outputs.add(data);
        }
        CompoundTag saved = new CompoundTag();
        saved.put("outputs", outputs);
        colony.keep(key, saved);
    }

    public final class Output {

        private final WorldPos device;
        private final ItemStack item;
        private long count;
        private boolean released;

        private Output(WorldPos device, ItemStack item) {
            this.device = device;
            this.item = item;
        }

        public boolean ready(long wanted) {
            return !released && count >= wanted;
        }

        public long receive(long amount, boolean simulate) {
            if (released) {
                return 0;
            }
            if (!simulate && amount > 0) {
                if (count == 0) {
                    pending.add(this);
                }
                count += amount;
                save();
            }
            return amount;
        }

        public List<ItemStack> collect(long wanted) {
            if (!ready(wanted)) {
                return List.of();
            }
            count -= wanted;
            if (count == 0) {
                pending.remove(this);
            }
            save();
            return stacks(item, wanted);
        }

        public void release() {
            released = true;
        }
    }

    private static List<ItemStack> stacks(ItemStack item, long count) {
        List<ItemStack> result = new ArrayList<>();
        while (count > 0) {
            int size = (int) Math.min(item.getMaxStackSize(), count);
            result.add(item.copyWithCount(size));
            count -= size;
        }
        return result;
    }
}
