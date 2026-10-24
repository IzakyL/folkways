package io.github.izakyl.folkways.plugins.farming;

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

// One non-stacking treatment for the life of a planted crop, saved in its dimension.
final class CropCare extends SavedData {
    private record Treatment(ResourceLocation block, double bonus) { }
    private final Map<Long, Treatment> crops = new HashMap<>();

    static CropCare of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(CropCare::new,
            (tag, registries) -> load(tag)), "folkways_crop_care");
    }

    void tend(BlockPos pos, BlockState state, double bonus) {
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
        CropCare care = new CropCare();
        for (Tag value : tag.getList("crops", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            ResourceLocation block = ResourceLocation.tryParse(entry.getString("block"));
            if (block != null) { care.crops.put(entry.getLong("pos"),
                new Treatment(block, Math.max(0, Math.min(.6, entry.getDouble("bonus"))))); }
        }
        return care;
    }
}
