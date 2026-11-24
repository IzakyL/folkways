package io.github.izakyl.folkways.core.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * The book settings for drawn holdings and the colony (`folkways_core:front`: `drawn`, `settings`)
 * become facility parameters (`folkways_core:facilities`: `holdings`, `colony`). Each setting is saved
 * as a facility value: a flag as a bool, a count as a real, a choice as text, an item list as item rules.
 */
public final class V5_Facilities implements Step {
    private static final String FRONT = "folkways_core:front";
    private static final String FACILITIES = "folkways_core:facilities";

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
            if (!keeps.contains(FRONT, Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag front = keeps.getCompound(FRONT);
            keeps.remove(FRONT);
            if (front.isEmpty()) {
                continue;
            }
            CompoundTag facilities = new CompoundTag();
            facilities.put("holdings", converted(front.getCompound("drawn")));
            facilities.put("colony", converted(front.getCompound("settings")));
            keeps.put(FACILITIES, facilities);
        }
    }

    // Each scope or holding: setting key -> {flag|count|choice|items} becomes key -> {type, value}.
    private static CompoundTag converted(CompoundTag scopes) {
        CompoundTag out = new CompoundTag();
        for (String scope : scopes.getAllKeys()) {
            CompoundTag settings = scopes.getCompound(scope);
            CompoundTag parameters = new CompoundTag();
            for (String key : settings.getAllKeys()) {
                CompoundTag setting = settings.getCompound(key);
                CompoundTag parameter = new CompoundTag();
                if (setting.contains("flag")) {
                    parameter.putString("type", "bool");
                    parameter.putBoolean("value", setting.getBoolean("flag"));
                } else if (setting.contains("count")) {
                    parameter.putString("type", "real");
                    parameter.putDouble("value", setting.getInt("count"));
                } else if (setting.contains("choice")) {
                    parameter.putString("type", "text");
                    parameter.putString("value", setting.getString("choice"));
                } else if (setting.contains("items")) {
                    ListTag rules = new ListTag();
                    for (Tag entry : setting.getList("items", Tag.TAG_COMPOUND)) {
                        CompoundTag rule = (CompoundTag) entry;
                        if (rule.contains("item")) {
                            rules.add(StringTag.valueOf("i" + rule.getString("item")));
                        } else if (rule.contains("tag")) {
                            rules.add(StringTag.valueOf("t" + rule.getString("tag")));
                        } else if (rule.contains("query")) {
                            rules.add(StringTag.valueOf("q" + rule.getString("query")));
                        }
                    }
                    parameter.putString("type", "items");
                    parameter.put("value", rules);
                } else {
                    continue;
                }
                parameters.put(key, parameter);
            }
            out.put(scope, parameters);
        }
        return out;
    }
}
