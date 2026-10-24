package io.github.izakyl.folkways.chiselsandbits;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class CandBConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue DECODE_BYTES;
    public static final ModConfigSpec.IntValue MATERIALS;
    public static final ModConfigSpec.IntValue PROCESSING_MILLIS;
    public static final ModConfigSpec.IntValue BLUEPRINT_READS;
    public static final ModConfigSpec.IntValue BLUEPRINT_BYTES;
    public static final ModConfigSpec.IntValue BLUEPRINT_MILLIS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        DECODE_BYTES = builder.comment("Maximum decompressed bytes in one schematic cell.")
            .defineInRange("decodeBytes", 1048576, 16384, 8388608);
        MATERIALS = builder.comment("Maximum distinct bit materials in one schematic cell.")
            .defineInRange("materials", 256, 1, 4096);
        PROCESSING_MILLIS = builder.comment("Maximum time spent decoding and counting one schematic cell, in milliseconds.")
            .defineInRange("processingMillis", 1000, 10, 5000);
        BLUEPRINT_READS = builder.comment("Maximum voxel payload reads in one blueprint operation, including cache hits.")
            .defineInRange("blueprintReads", 4096, 1, 65536);
        BLUEPRINT_BYTES = builder.comment("Maximum cumulative decoded bytes in one blueprint operation, including cache hits.")
            .defineInRange("blueprintBytes", 16777216, 16384, 67108864);
        BLUEPRINT_MILLIS = builder.comment("Maximum synchronous voxel processing time in one blueprint operation, in milliseconds.")
            .defineInRange("blueprintMillis", 500, 10, 5000);
        SPEC = builder.build();
    }

    private CandBConfig() {
    }

    static int value(ModConfigSpec.IntValue value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
