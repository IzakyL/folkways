package io.github.izakyl.folkways.core.shell.mixin;

import io.github.izakyl.folkways.core.base.persist.upgrade.ConfigRenames;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// NeoForge reads a world's server config overrides right after naming their folder, before any event we could hear.
@Mixin(value = ServerLifecycleHooks.class, remap = false)
abstract class ServerConfigFolderMixin {
    @Inject(method = "getServerConfigPath", at = @At("RETURN"))
    private static void folkways$renameReleasedConfigs(MinecraftServer server, CallbackInfoReturnable<Path> callback) {
        ConfigRenames.world(callback.getReturnValue());
    }
}
