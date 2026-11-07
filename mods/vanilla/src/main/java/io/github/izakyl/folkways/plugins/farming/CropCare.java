package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.base.persist.Required;
import io.github.izakyl.folkways.base.persist.upgrade.DataVersion;
import io.github.izakyl.folkways.base.persist.upgrade.RawUpgrades;
import io.github.izakyl.folkways.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.base.persist.upgrade.Upgrades;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// One non-stacking treatment for the life of a planted crop, saved in its dimension.
final class CropCare extends SavedData {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-persist");
    private record Treatment(ResourceLocation block, double bonus) { }
    private final Map<Long, Treatment> crops = new HashMap<>();
    // Future payloads stay opaque even if Minecraft later saves this object.
    private CompoundTag preserved;

    static CropCare of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(CropCare::new,
            (tag, registries) -> load(tag)), "folkways_crop_care");
    }

    void tend(BlockPos pos, BlockState state, double bonus) {
        if (preserved != null) {
            return;
        }
        boolean ages = state.getProperties().stream().anyMatch(property ->
            property instanceof IntegerProperty && property.getName().equals("age"));
        if (ages && bonus > 0 && crops.putIfAbsent(pos.asLong(),
                new Treatment(BuiltInRegistries.BLOCK.getKey(state.getBlock()), bonus)) == null) { setDirty(); }
    }

    void forget(BlockPos pos) {
        if (crops.remove(pos.asLong()) != null) { setDirty(); }
    }

    static void broken(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) { of(level).forget(event.getPos()); }
    }

    static void placed(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) { of(level).forget(event.getPos()); }
    }

    static void grown(CropGrowEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) { return; }
        CropCare care = of(level);
        Treatment treatment = care.crops.get(event.getPos().asLong());
        if (treatment == null) { return; }
        BlockState state = level.getBlockState(event.getPos());
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(treatment.block())) {
            care.forget(event.getPos());
            return;
        }
        if (level.random.nextDouble() >= treatment.bonus()) { return; }
        for (var property : state.getProperties()) {
            if (property instanceof IntegerProperty age && age.getName().equals("age")) {
                int max = age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
                int current = state.getValue(age);
                if (current < max) { level.setBlock(event.getPos(), state.setValue(age, current + 1), 3); }
                return;
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) {
            return preserved.copy();
        }
        tag.putInt(RawUpgrades.VERSION, DataVersion.CURRENT);
        ListTag list = new ListTag();
        crops.forEach((position, treatment) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", position);
            entry.putString("block", treatment.block().toString());
            entry.putDouble("bonus", treatment.bonus());
            list.add(entry);
        });
        tag.put("crops", list);
        return tag;
    }

    static CropCare load(CompoundTag tag) {
        int stored = tag.contains(RawUpgrades.VERSION)
            ? Required.integer(tag, RawUpgrades.VERSION) : DataVersion.BASELINE;
        if (stored > DataVersion.CURRENT) {
            CropCare care = new CropCare();
            care.preserved = tag.copy();
            LOGGER.error("Unsupported Folkways CROP_CARE data version {} (current {}); preserving raw data on save",
                stored, DataVersion.CURRENT);
            return care;
        }
        tag = tag.copy();
        Upgrades.upgrade(Surface.CROP_CARE, tag, stored);
        CropCare care = new CropCare();
        if (stored != DataVersion.CURRENT) {
            care.setDirty();
        }
        for (Tag value : Required.list(tag, "crops", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            ResourceLocation block = ResourceLocation.tryParse(Required.string(entry, "block"));
            long position = Required.longValue(entry, "pos");
            double bonus = Required.doubleValue(entry, "bonus");
            if (block == null) {
                throw new IllegalArgumentException("Invalid CropCare block id");
            }
            care.crops.put(position, new Treatment(block, Math.max(0, Math.min(.6, bonus))));
        }
        return care;
    }
}
