package io.github.izakyl.folkways.war;

import net.neoforged.fml.common.Mod;

/** Optional client-side RTS input layer for Folkways Vanilla's existing combat tools. */
@Mod(FolkwaysWarMod.MOD_ID)
public final class FolkwaysWarMod {
    public static final String MOD_ID = "folkways_war";

    public FolkwaysWarMod() {
        // Keep the common entry point free of client class references so dedicated servers can load the mod safely.
        // Client input handlers are installed by the client-only integration.
    }
}
