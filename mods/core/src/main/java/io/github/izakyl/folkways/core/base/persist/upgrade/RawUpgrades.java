package io.github.izakyl.folkways.core.base.persist.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Platform hooks for raw entity and block-entity NBT, never business deserializers. */
public final class RawUpgrades {
    public static final String VERSION = "folkways_core:data_version";
    private static final String LEGACY_VERSION = "folkways:data_version";
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-persist");

    private RawUpgrades() {
    }

    private static boolean ownedKey(String key) {
        return ownId(key) || key.startsWith("folkways.") || key.startsWith("folkways_");
    }

    public static boolean owns(CompoundTag raw, boolean ownType) {
        return ownType || raw.getCompound("NeoForgeData").getAllKeys().stream().anyMatch(RawUpgrades::ownedKey)
            || raw.getCompound("neoforge:attachments").getAllKeys().stream().anyMatch(RawUpgrades::ownId)
            || raw.getAllKeys().stream().anyMatch(key -> ownedKey(key) || key.startsWith("FolkwaysFurnace"));
    }

    public static boolean ownId(String id) {
        return id.startsWith("folkways:") || ownNamespace(id.split(":", 2)[0]);
    }

    public static boolean ownNamespace(String namespace) {
        return java.util.Set.of("folkways_core", "folkways_vanilla", "folkways_create", "folkways_cookery",
            "folkways_firearms", "folkways_carts", "folkways_carving", "folkways_chiselsandbits", "folkways_golems",
            "folkways_epicfight").contains(namespace);
    }

    public static int storedVersion(CompoundTag raw, int fallback) {
        String key = raw.contains(VERSION) ? VERSION : LEGACY_VERSION;
        if (!raw.contains(key)) {
            return fallback;
        }
        if (!raw.contains(key, Tag.TAG_INT)) {
            throw new IllegalArgumentException("Malformed Folkways data version " + raw.get(key));
        }
        return raw.getInt(key);
    }

    static boolean supported(CompoundTag raw) {
        try {
            int version = storedVersion(raw.getCompound("NeoForgeData"), DataVersion.BASELINE);
            return version >= DataVersion.BASELINE && version <= DataVersion.CURRENT;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static CompoundTag data(CompoundTag raw) {
        if (!raw.contains("NeoForgeData", Tag.TAG_COMPOUND)) {
            raw.put("NeoForgeData", new CompoundTag());
        }
        return raw.getCompound("NeoForgeData");
    }

    /**
     * Unstamped input is current, so command and sync payloads stay untouched, unless it carries a released
     * {@code folkways:} key: only data version 1 wrote those without a stamp. Registry aliases resolve type ids.
     */
    public static void load(Surface surface, CompoundTag raw) {
        load(surface, raw, Upgrades.GLOBAL);
    }

    // Isolated engines exercise the raw boundary before the next real format change.
    static void load(Surface surface, CompoundTag raw, Upgrades upgrades) {
        CompoundTag persistent = raw.getCompound("NeoForgeData");
        String versionKey = persistent.contains(VERSION) ? VERSION : LEGACY_VERSION;
        if (!persistent.contains(versionKey) && !unstampedRelease(raw)) {
            return;
        }
        int stored = persistent.contains(versionKey) ? persistent.getInt(versionKey) : upgrades.baseline();
        if (persistent.contains(versionKey) && !persistent.contains(versionKey, Tag.TAG_INT)
                || stored < upgrades.baseline() || stored > upgrades.current()) {
            LOGGER.error("Unsupported Folkways {} data version {} (current {}); leaving raw data unchanged",
                surface, persistent.get(versionKey), upgrades.current());
            return;
        }
        upgrades.run(surface, raw, stored);
        data(raw).remove(LEGACY_VERSION);
        data(raw).putInt(VERSION, upgrades.current());
    }

    // Version 2 stamped everything it owned and later versions write no released keys.
    private static boolean unstampedRelease(CompoundTag raw) {
        return raw.getCompound("NeoForgeData").getAllKeys().stream().anyMatch(key -> key.startsWith("folkways:"))
            || raw.getCompound("neoforge:attachments").getAllKeys().stream().anyMatch(key -> key.startsWith("folkways:"));
    }

    public static void stamp(CompoundTag raw, boolean ownType) {
        if (owns(raw, ownType)) {
            CompoundTag persistent = data(raw);
            // Never disguise future or malformed data as successfully upgraded on a later save.
            String key = persistent.contains(VERSION) ? VERSION : LEGACY_VERSION;
            if (!persistent.contains(key) || (persistent.contains(key, Tag.TAG_INT)
                    && persistent.getInt(key) >= DataVersion.BASELINE
                    && persistent.getInt(key) <= DataVersion.CURRENT)) {
                persistent.remove(LEGACY_VERSION);
                persistent.putInt(VERSION, DataVersion.CURRENT);
            }
        }
    }
}
