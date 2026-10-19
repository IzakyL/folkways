package io.github.izakyl.folkways.core.api.resident.body;

import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.INBTSerializable;

public final class Licences implements INBTSerializable<CompoundTag> {

    private static final String TAG_WITHHELD = "withheld";
    private static final String TAG_KEENNESS = "keenness";

    private static final AtomicLong REVISIONS = new AtomicLong();

    public static final class Trade {

        private final Vocation vocation;

        private Trade(Vocation vocation) {
            this.vocation = vocation;
        }

        public Vocation vocation() {
            return vocation;
        }
    }

    private final ResourceLocation body;
    private final Set<Vocation> withheld = new LinkedHashSet<>();
    private final Map<Vocation, Keenness> keenness = new LinkedHashMap<>();
    private volatile long revision = REVISIONS.incrementAndGet();

    public Licences(ResourceLocation body) {
        this.body = body;
    }

    public Optional<Trade> trade(Vocation vocation) {
        return stands(vocation.id()) == Participation.NONE
            ? Optional.empty()
            : Optional.of(new Trade(vocation));
    }

    public Optional<Licence> of(Vocation vocation) {
        return trade(vocation)
            .filter(this::allows)
            .map(trade -> new Licence(trade.vocation(), keennessOf(trade)));
    }

    public boolean allows(Trade trade) {
        return stands(trade.vocation().id()) == Participation.ALWAYS
            || !withheld.contains(trade.vocation());
    }

    private Participation stands(ResourceLocation vocation) {
        return Participations.between(body, Stake.vocation(vocation));
    }

    public void allow(Trade trade, boolean allowed) {
        if (allowed) {
            withheld.remove(trade.vocation());
        } else {
            withheld.add(trade.vocation());
        }
        touched();
    }

    public Keenness keennessOf(Trade trade) {
        return keenness.getOrDefault(trade.vocation(), Keenness.DEFAULT);
    }

    public void setKeenness(Trade trade, Keenness how) {
        keenness.put(trade.vocation(), how);
        touched();
    }

    public long revision() {
        return revision;
    }

    private void touched() {
        revision = REVISIONS.incrementAndGet();
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        ListTag off = new ListTag();
        withheld.forEach(vocation -> off.add(StringTag.valueOf(vocation.id().toString())));
        tag.put(TAG_WITHHELD, off);
        CompoundTag buckets = new CompoundTag();
        keenness.forEach((vocation, how) -> buckets.putInt(vocation.id().toString(), how.number()));
        tag.put(TAG_KEENNESS, buckets);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        withheld.clear();
        keenness.clear();
        ListTag off = tag.getList(TAG_WITHHELD, StringTag.TAG_STRING);
        for (int i = 0; i < off.size(); i++) {
            declared(off.getString(i)).ifPresent(withheld::add);
        }
        CompoundTag buckets = tag.getCompound(TAG_KEENNESS);
        for (String key : buckets.getAllKeys()) {
            declared(key).ifPresent(vocation ->
                Keenness.ofNumber(buckets.getInt(key))
                    .ifPresent(how -> keenness.put(vocation, how)));
        }
        touched();
    }

    private Optional<Vocation> declared(String key) {
        ResourceLocation id = ResourceLocation.tryParse(key);
        if (id == null || stands(id) == Participation.NONE) {
            return Optional.empty();
        }
        return Vocations.of(id);
    }
}
