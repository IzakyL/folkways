package io.github.izakyl.folkways.core.resident.internal;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.resident.api.Balk;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.resident.api.BodyStateAccess;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Pack;
import io.github.izakyl.folkways.core.resident.api.ResidentKind;
import io.github.izakyl.folkways.core.effect.perk.Perks;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.common.util.INBTSerializable;

public final class BodyState implements BodyStateAccess, INBTSerializable<CompoundTag> {

    private static final Map<ResidentKind, Perks> BUILT = new ConcurrentHashMap<>();

    private static final String TAG_COLONY = "Colony";
    private static final String TAG_PACK = "Pack";
    private static final String TAG_PERKS = "Perks";

    private final Pack pack = new ResidentPack();
    private final BodyPerks perks = new BodyPerks();
    private UUID colony;

    private Doing doing;

    private Balk balk;

    public Pack pack() {
        return pack;
    }

    public Perks perks(ResidentKind kind) {
        if (kind.growth() instanceof ResidentKind.Growth.Built(Map<String, Integer> ranks)) {
            return BUILT.computeIfAbsent(kind, sort -> Perks.fixed(ranks));
        }
        return perks;
    }

    public void credit(ResourceLocation vocation, int amount, RandomSource random) {
        perks.addXp(vocation, amount, random);
    }

    public void worked(ResourceLocation vocation, int ticks, RandomSource random) {
        perks.worked(vocation, ticks, random);
    }

    public Optional<UUID> colony() {
        return Optional.ofNullable(colony);
    }

    public void bindTo(UUID colony) {
        this.colony = colony;
    }

    public void leaveColony(Body body) {
        Equipment.release(body);
        TaskToolLoans.INSTANCE.forget(body.id());
        this.colony = null;
    }

    public Optional<Doing> doing() {
        return Optional.ofNullable(doing);
    }

    public void nowDoing(Doing doing) {
        this.doing = doing;
    }

    public Optional<Balk> balk() {
        return Optional.ofNullable(balk);
    }

    public void balked(Balk balk) {
        this.balk = balk;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        if (colony != null) {
            tag.putUUID(TAG_COLONY, colony);
        }
        tag.put(TAG_PACK, pack.save(provider));
        tag.put(TAG_PERKS, perks.save());
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        colony = tag.hasUUID(TAG_COLONY) ? tag.getUUID(TAG_COLONY) : null;
        Reader reader = Reader.of(tag);
        pack.load(reader.blob(TAG_PACK).orElseThrow(), provider);
        perks.load(reader.blob(TAG_PERKS).orElseThrow());
    }
}
