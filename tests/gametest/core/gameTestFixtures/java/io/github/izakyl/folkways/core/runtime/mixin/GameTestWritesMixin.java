package io.github.izakyl.folkways.core.runtime.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * GameTest worlds are thrown away, yet {@code MinecraftServer} forces synchronous (O_DSYNC) region writes and
 * {@code GameTestServer} does not override it, so suites spread over hundreds of regions spent many minutes in
 * the shutdown save.
 */
@Mixin(GameTestServer.class)
public abstract class GameTestWritesMixin {
    public boolean forceSynchronousWrites() {
        return false;
    }
}
