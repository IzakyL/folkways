package io.github.izakyl.folkways.core.resident.internal;

import io.github.izakyl.folkways.core.base.persist.Required;
import io.github.izakyl.folkways.core.resident.api.Licence;
import io.github.izakyl.folkways.core.resident.api.Licences.Trade;
import io.github.izakyl.folkways.core.resident.api.Licences;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
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

public final class ResidentLicences implements Licences {

    private static final String TAG_WITHHELD = "withheld";
    private static final String TAG_KEENNESS = "keenness";

    private static final AtomicLong REVISIONS = new AtomicLong();

    private final ResourceLocation body;
    private final Set<Vocation> withheld = new LinkedHashSet<>();
    private final Map<Vocation, Keenness> keenness = new LinkedHashMap<>();
    private volatile long revision = REVISIONS.incrementAndGet();

    public ResidentLicences(ResourceLocation body) {
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
        return keenness.getOrDefault(trade.vocation(), trade.vocation().defaultPriority());
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
        ListTag off = Required.list(tag, TAG_WITHHELD, StringTag.TAG_STRING);
        for (int i = 0; i < off.size(); i++) {
            declared(off.getString(i)).ifPresent(withheld::add);
        }
        CompoundTag buckets = Required.compound(tag, TAG_KEENNESS);
        for (String key : buckets.getAllKeys()) {
            declared(key).ifPresent(vocation ->
                keenness.put(vocation, Keenness.ofNumber(Required.integer(buckets, key))
                    .orElseThrow(() -> new IllegalArgumentException("Invalid persisted keenness for " + key))));
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
