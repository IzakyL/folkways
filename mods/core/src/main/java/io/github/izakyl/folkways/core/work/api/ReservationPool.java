package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** A run's material budget. The planner owns its live claims; the checkpoint holds only reservation metadata. */
public final class ReservationPool {

    public record Stored(Stash where, ItemSpec spec, long count) {
    }

    public record Carried(UUID id, UUID resident, ItemSpec spec, int count) {
    }

    private final UUID owner = UUID.randomUUID();
    private final List<Need> inputs;
    private final List<Amount> outputs;
    private final boolean stowAll;
    private final long[] remaining;
    private final long[] owed;
    private final List<Stored> stored = new ArrayList<>();
    private final Map<UUID, Carried> carried = new LinkedHashMap<>();
    private long revision;

    public ReservationPool(List<Need> inputs, List<Amount> outputs) {
        this(inputs, outputs, false);
    }

    public static ReservationPool stowing() {
        return new ReservationPool(List.of(), List.of(), true);
    }

    private ReservationPool(List<Need> inputs, List<Amount> outputs, boolean stowAll) {
        this.stowAll = stowAll;
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        remaining = inputs.stream().mapToLong(Need::count).toArray();
        owed = outputs.stream().mapToLong(Amount::count).toArray();
    }

    public UUID owner() {
        return owner;
    }

    public synchronized List<Need> remaining() {
        List<Need> needs = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            needs.add(new Need(inputs.get(index).spec(), remaining[index]));
        }
        return List.copyOf(needs);
    }

    public synchronized void drawn(int input, long count) {
        remaining[input] -= count;
        revision++;
    }

    public synchronized void stored(Stash where, ItemStack stack) {
        long left = stack.getCount();
        for (int index = 0; index < outputs.size() && left > 0; index++) {
            if (owed[index] > 0 && Goods.matches(stack, outputs.get(index).spec())) {
                long count = Math.min(left, owed[index]);
                owed[index] -= count;
                left -= count;
                stored.add(new Stored(where, Goods.specOf(stack), count));
                revision++;
            }
        }
    }

    public synchronized void carried(UUID resident, ItemStack stack) {
        ItemSpec concrete = Goods.specOf(stack);
        if (stowAll) {
            carry(resident, concrete, stack.getCount());
            return;
        }
        long needed = 0;
        for (int index = 0; index < outputs.size(); index++) {
            if (Goods.matches(stack, outputs.get(index).spec())) {
                needed += owed[index];
            }
        }
        for (Carried cargo : carried.values()) {
            if (cargo.spec().equals(concrete)) {
                needed -= cargo.count();
            }
        }
        if (needed > 0) {
            carry(resident, concrete, (int) Math.min(needed, stack.getCount()));
        }
    }

    private void carry(UUID resident, ItemSpec concrete, int count) {
        UUID id = UUID.randomUUID();
        carried.put(id, new Carried(id, resident, concrete, count));
        revision++;
    }

    public synchronized List<Carried> carried() {
        return List.copyOf(carried.values());
    }

    public synchronized boolean hasCarried() {
        return !carried.isEmpty();
    }

    public synchronized void landed(UUID id, Stash where, List<ItemStack> goods) {
        if (carried.remove(id) != null) {
            revision++;
        }
        goods.forEach(stack -> stored(where, stack));
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized List<Stored> storedSince(int mark) {
        return List.copyOf(stored.subList(mark, stored.size()));
    }

    public synchronized boolean fulfilled() {
        for (long count : owed) {
            if (count > 0) {
                return false;
            }
        }
        return true;
    }

    public synchronized CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLongArray("remaining", remaining.clone());
        tag.putLongArray("owed", owed.clone());
        ListTag receipts = new ListTag();
        for (Stored put : stored) {
            CompoundTag receipt = new CompoundTag();
            receipt.put("where", put.where().pos().save());
            receipt.put("item", put.spec().save(registries));
            receipt.putLong("count", put.count());
            receipts.add(receipt);
        }
        tag.put("stored", receipts);
        ListTag packs = new ListTag();
        for (Carried cargo : carried.values()) {
            CompoundTag pack = new CompoundTag();
            pack.putUUID("id", cargo.id());
            pack.putUUID("resident", cargo.resident());
            pack.put("item", cargo.spec().save(registries));
            pack.putInt("count", cargo.count());
            packs.add(pack);
        }
        tag.put("carried", packs);
        return tag;
    }

    public synchronized void restore(CompoundTag tag, HolderLookup.Provider registries) {
        long[] savedInputs = tag.getLongArray("remaining");
        long[] savedOutputs = tag.getLongArray("owed");
        if (savedInputs.length == remaining.length && savedOutputs.length == owed.length) {
            System.arraycopy(savedInputs, 0, remaining, 0, remaining.length);
            for (Tag entry : tag.getList("carried", Tag.TAG_COMPOUND)) {
                CompoundTag pack = (CompoundTag) entry;
                var item = ItemSpec.load(Reader.of(pack.getCompound("item")), registries);
                if (pack.hasUUID("id") && pack.hasUUID("resident") && item.isPresent() && pack.getInt("count") > 0) {
                    UUID id = pack.getUUID("id");
                    carried.put(id, new Carried(id, pack.getUUID("resident"), item.get(), pack.getInt("count")));
                }
            }
            System.arraycopy(savedOutputs, 0, owed, 0, owed.length);
            for (Tag entry : tag.getList("stored", Tag.TAG_COMPOUND)) {
                CompoundTag receipt = (CompoundTag) entry;
                var where = WorldPos.load(Reader.of(receipt.getCompound("where")));
                var item = ItemSpec.load(Reader.of(receipt.getCompound("item")), registries);
                if (where.isPresent() && item.isPresent() && receipt.getLong("count") > 0) {
                    stored.add(new Stored(Stash.at(where.get()), item.get(), receipt.getLong("count")));
                }
            }
        }
    }
}
