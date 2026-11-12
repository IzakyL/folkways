package io.github.izakyl.folkways.vanilla.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.Step;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Patrol loadouts become Core equipment profiles. Weapons are inventory entries, armour fills body slots,
 * the saddle becomes the riding flag, and horse armour stays optional; supplies keep their target and threshold.
 * Profile and entry ids, names and assignments are kept, so patrol staffing continues to refer to the same profiles.
 */
public final class V4_EquipmentProfiles implements Step {
    private static final String LOADOUTS = "folkways_vanilla:patrol_loadouts";
    private static final String EQUIPMENT = "folkways_core:equipment";

    @Override
    public int target() {
        return 4;
    }

    @Override
    public Surface surface() {
        return Surface.COLONY_REGISTRY;
    }

    @Override
    public void apply(CompoundTag raw) {
        for (Tag value : raw.getList("colonies", Tag.TAG_COMPOUND)) {
            CompoundTag keeps = ((CompoundTag) value).getCompound("keeps");
            if (keeps.contains(LOADOUTS, Tag.TAG_COMPOUND)) {
                migrate(keeps, keeps.getCompound(LOADOUTS));
                keeps.remove(LOADOUTS);
            }
        }
    }

    private static void migrate(CompoundTag keeps, CompoundTag loadouts) {
        CompoundTag equipment = new CompoundTag();
        ListTag profiles = new ListTag();
        for (Tag row : loadouts.getList("profiles", Tag.TAG_COMPOUND)) {
            profiles.add(convert((CompoundTag) row));
        }
        equipment.put("profiles", profiles);
        equipment.put("assignments", loadouts.getList("assignments", Tag.TAG_COMPOUND).copy());
        keeps.put(EQUIPMENT, equipment);
    }

    private static CompoundTag convert(CompoundTag loadout) {
        CompoundTag profile = new CompoundTag();
        profile.put("id", loadout.get("id").copy());
        profile.putString("name", loadout.getString("name"));
        ListTag entries = new ListTag();
        for (Tag item : loadout.getList("entries", Tag.TAG_COMPOUND)) {
            CompoundTag old = (CompoundTag) item;
            if (old.getString("kind").equals("MOUNT")) {
                profile.putBoolean("riding", true);
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.put("id", old.get("id").copy());
            entry.put("items", old.getCompound("items").copy());
            String slot = switch (old.getString("kind")) {
                case "WEAPON" -> null;
                case "ARMOR" -> "folkways_core:" + old.getString("slot");
                case "HORSE_ARMOR" -> "folkways_vanilla:horse_armor";
                default -> null;
            };
            if (slot != null) {
                entry.putString("slot", slot);
            }
            boolean supply = old.getString("kind").equals("SUPPLY");
            entry.putInt("target", supply ? old.getInt("target") : 1);
            entry.putInt("threshold", supply ? old.getInt("threshold") : 1);
            entries.add(entry);
        }
        profile.put("entries", entries);
        return profile;
    }
}
