package io.github.izakyl.folkways.core.api.resident.body;

import io.github.izakyl.folkways.core.api.perk.Perks;
import io.github.izakyl.folkways.core.api.work.Balk;
import io.github.izakyl.folkways.core.api.work.Doing;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.common.util.INBTSerializable;

final class BodyState implements INBTSerializable<CompoundTag> {

    private static final String TAG_COLONY = "Colony";
    private static final String TAG_PACK = "Pack";
    private static final String TAG_PERKS = "Perks";

    private final Pack pack = new Pack();
    private final BodyPerks perks = new BodyPerks();
    private UUID colony;

    private Doing doing;

    private Balk balk;

    public Pack pack() {
        return pack;
    }

    public Perks perks() {
        return perks;
    }

    public void credit(ResourceLocation vocation, int amount, RandomSource random) {
        perks.addXp(vocation, amount, random);
    }

    public Optional<UUID> colony() {
        return Optional.ofNullable(colony);
    }

    public void bindTo(UUID colony) {
        this.colony = colony;
    }

    public void unbind() {
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
        pack.load(tag.getCompound(TAG_PACK), provider);
        perks.load(tag.getCompound(TAG_PERKS));
    }
}
