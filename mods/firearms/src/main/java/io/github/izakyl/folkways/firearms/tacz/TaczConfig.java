package io.github.izakyl.folkways.firearms.tacz;

import io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class TaczConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED =
        BUILDER.comment(
        "Gun IDs residents may use. Empty allows compatible built-in guns;"
        + " special ammunition and unknown scripts stay unsupported.")
        .defineListAllowEmpty(
        "allowedGuns",
        List.<String>of(),
        () -> "tacz:m1911",
        value -> value instanceof String text && ResourceLocation.tryParse(text) != null);
    private static final ModConfigSpec.DoubleValue RANGE =
        BUILDER.comment("Maximum firing distance. This does not change guard engagement"
        + " radius.")
        .defineInRange("rangeCap", 24D, 1D, 128D);
    private static final ModConfigSpec.ConfigValue<List<? extends String>> GUN_RANGES =
        BUILDER.comment("Optional per-gun range caps, formatted as gun_id=blocks; bounded by"
        + " rangeCap.")
        .defineListAllowEmpty(
        "gunRangeCaps",
        List.of("tacz:m1911=16", "tacz:m870=12"),
        () -> "tacz:m1911=16",
        TaczConfig::rangeEntry);
    private static final ModConfigSpec.BooleanValue PROTECT =
        BUILDER.comment(
        "Protect same-colony residents and colony pets from resident gunfire,"
        + " including bullets already in flight.")
        .define("protectColonyAllies", true);
    public static final ModConfigSpec SPEC = BUILDER.build();

    private TaczConfig() {
    }

    private static <T> T read(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    private static boolean rangeEntry(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String[] parts = text.split("=", -1);
        if (parts.length != 2 || ResourceLocation.tryParse(parts[0]) == null) {
            return false;
        }
        try {
            double range = Double.parseDouble(parts[1]);
            return Double.isFinite(range) && range >= 1 && range <= 128;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    public static boolean allowed(ResourceLocation gun) {
        return read(ALLOWED).isEmpty() ? gun.getNamespace().equals("tacz") : read(ALLOWED).stream().map(io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds::config)
            .anyMatch(gun.toString()::equals);
    }

    public static boolean protectAllies() {
        return read(PROTECT);
    }

    public static double rangeCap() {
        return read(RANGE);
    }

    public static double range(ResourceLocation gun) {
        for (String entry : read(GUN_RANGES)) {
            String[] parts = entry.split("=", -1);
            if (NamespaceIds.config(parts[0]).equals(gun.toString())) {
                return Math.min(rangeCap(), Double.parseDouble(parts[1]));
            }
        }
        return rangeCap();
    }
}
