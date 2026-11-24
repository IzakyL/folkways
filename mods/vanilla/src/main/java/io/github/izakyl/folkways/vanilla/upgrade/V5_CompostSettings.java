package io.github.izakyl.folkways.vanilla.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Composting's switch and default seed reserve (`folkways_vanilla:compost_settings`: `enabled`, `default_reserve`)
 * become the composting scope's settings among the colony's facility parameters (`folkways_core:facilities`,
 * `colony`, `folkways_vanilla:composting`), a flag as a bool and the reserve as a real. Reserves set per seed stay
 * where they were. Runs after Core's step at this version, which writes the facility parameters.
 */
public final class V5_CompostSettings implements Step {
    private static final String COMPOST = "folkways_vanilla:compost_settings";
    private static final String FACILITIES = "folkways_core:facilities";
    private static final String SCOPE = "folkways_vanilla:composting";

    @Override
    public int target() {
        return 5;
    }

    @Override
    public Surface surface() {
        return Surface.COLONY_REGISTRY;
    }

    @Override
    public void apply(CompoundTag raw) {
        for (Tag value : raw.getList("colonies", Tag.TAG_COMPOUND)) {
            CompoundTag keeps = ((CompoundTag) value).getCompound("keeps");
            if (!keeps.contains(COMPOST, Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag compost = keeps.getCompound(COMPOST);
            CompoundTag parameters = new CompoundTag();
            if (compost.contains("enabled", Tag.TAG_BYTE)) {
                CompoundTag enabled = new CompoundTag();
                enabled.putString("type", "bool");
                enabled.putBoolean("value", compost.getBoolean("enabled"));
                parameters.put("enabled", enabled);
            }
            if (compost.contains("default_reserve", Tag.TAG_INT)) {
                CompoundTag reserve = new CompoundTag();
                reserve.putString("type", "real");
                reserve.putDouble("value", compost.getInt("default_reserve"));
                parameters.put("default_reserve", reserve);
            }
            compost.remove("enabled");
            compost.remove("default_reserve");
            if (compost.isEmpty()) {
                keeps.remove(COMPOST);
            }
            if (parameters.isEmpty()) {
                continue;
            }
            CompoundTag facilities = keeps.getCompound(FACILITIES);
            CompoundTag colony = facilities.getCompound("colony");
            CompoundTag scope = colony.getCompound(SCOPE);
            parameters.getAllKeys().forEach(key -> scope.put(key, parameters.get(key)));
            colony.put(SCOPE, scope);
            facilities.put("colony", colony);
            keeps.put(FACILITIES, facilities);
        }
    }
}
