package io.github.izakyl.folkways.chiselsandbits;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class CandBConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue DECODE_BYTES;
    public static final ModConfigSpec.IntValue MATERIALS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        DECODE_BYTES = builder.comment("Maximum decompressed bytes in one schematic cell.")
            .defineInRange("decodeBytes", 1048576, 16384, 8388608);
        MATERIALS = builder.comment("Maximum distinct bit materials in one schematic cell.")
            .defineInRange("materials", 256, 1, 4096);
        SPEC = builder.build();
    }

    private CandBConfig() {
    }
}
