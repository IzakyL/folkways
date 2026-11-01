package io.github.izakyl.folkways.efficientconstruction;

import net.neoforged.neoforge.common.ModConfigSpec;

// What the efficient site is tuned by. The defaults are the ones the extension was designed around.
public final class EfficientConstructionConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static final ModConfigSpec.IntValue TICKS_PER_BLOCK;
    static final ModConfigSpec.IntValue WORKERS_PER_SITE;
    static final ModConfigSpec.IntValue STACKS_PER_TRIP;
    static final ModConfigSpec SPEC;

    static {
        BUILDER.comment("How a build site raises itself while residents stand on it.").push("efficientConstruction");

        TICKS_PER_BLOCK = BUILDER
            .comment("Ticks each resident at the site adds per block placed. 16 is a quarter of a player's own pace;"
                + " one resident is one unit, so two residents at 16 place a block every 8 ticks.")
            .defineInRange("ticksPerBlock", 16, 1, 400);

        WORKERS_PER_SITE = BUILDER
            .comment("How many residents one site asks for at a time. Standing room on the site can ask for fewer.")
            .defineInRange("workersPerSite", 8, 1, 64);

        STACKS_PER_TRIP = BUILDER
            .comment("The most stacks of material one resident is asked for on a trip, and the point the site climbs"
                + " back to once its asks are being met. A site starts at four stacks, halves that whenever an asking"
                + " brings nobody, and grows again while askings are filled. Keep it under what a resident's pack can"
                + " hold next to the blocks the work breaks: a pack is eleven cells at its smallest, so a wide trip is"
                + " refused outright as PACK_BUSY and no hand is sent at all.")
            .defineInRange("stacksPerTrip", 6, 1, 36);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private EfficientConstructionConfig() {
    }
}
