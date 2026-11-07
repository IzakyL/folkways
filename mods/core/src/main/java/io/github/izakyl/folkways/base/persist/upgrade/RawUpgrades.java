package io.github.izakyl.folkways.base.persist.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Platform hooks for raw entity and block-entity NBT, never business deserializers. */
public final class RawUpgrades {
    public static final String VERSION = "folkways:data_version";
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-persist");

    private RawUpgrades() {
    }

    private static boolean ownedKey(String key) {
        return key.startsWith("folkways:") || key.startsWith("folkways.") || key.startsWith("folkways_");
    }

    public static boolean owns(CompoundTag raw, boolean ownType) {
        return ownType || raw.getCompound("NeoForgeData").getAllKeys().stream().anyMatch(RawUpgrades::ownedKey)
            || raw.getCompound("neoforge:attachments").getAllKeys().stream().anyMatch(key -> key.startsWith("folkways:"))
            || raw.getAllKeys().stream().anyMatch(key -> ownedKey(key) || key.startsWith("FolkwaysFurnace"));
    }

    public static boolean ownId(String id) {
        return id.startsWith("folkways:");
    }

    private static CompoundTag data(CompoundTag raw) {
        if (!raw.contains("NeoForgeData", Tag.TAG_COMPOUND)) {
            raw.put("NeoForgeData", new CompoundTag());
        }
        return raw.getCompound("NeoForgeData");
    }

    /** Unstamped entity/block-entity input is current-format NBT, left untouched until save (including master v1). */
    public static void load(Surface surface, CompoundTag raw) {
        load(surface, raw, Upgrades.GLOBAL);
    }

    // Isolated engines exercise the raw boundary before the next real format change.
    static void load(Surface surface, CompoundTag raw, Upgrades upgrades) {
        CompoundTag persistent = raw.getCompound("NeoForgeData");
        if (!persistent.contains(VERSION)) {
            return;
        }
        int stored = persistent.getInt(VERSION);
        if (!persistent.contains(VERSION, Tag.TAG_INT)
                || stored < upgrades.baseline() || stored > upgrades.current()) {
            LOGGER.error("Unsupported Folkways {} data version {} (current {}); leaving raw data unchanged",
                surface, persistent.get(VERSION), upgrades.current());
            return;
        }
        upgrades.run(surface, raw, stored);
        data(raw).putInt(VERSION, upgrades.current());
    }

    public static void stamp(CompoundTag raw, boolean ownType) {
        if (owns(raw, ownType)) {
            CompoundTag persistent = data(raw);
            // Never disguise future or malformed data as successfully upgraded on a later save.
            if (!persistent.contains(VERSION) || (persistent.contains(VERSION, Tag.TAG_INT)
                    && persistent.getInt(VERSION) >= DataVersion.BASELINE
                    && persistent.getInt(VERSION) <= DataVersion.CURRENT)) {
                persistent.putInt(VERSION, DataVersion.CURRENT);
            }
        }
    }
}
