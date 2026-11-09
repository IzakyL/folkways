package io.github.izakyl.folkways.epicfight.mixin;

import io.github.izakyl.folkways.epicfight.AnimationPairingEvidence;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.network.client.CPPairingAnimationRegistry;

@Mixin(value = AnimationManager.class, remap = false)
public abstract class AnimationManagerEvidenceMixin {
    private static final String PREPARE = "prepare(Lnet/minecraft/server/packs/resources/ResourceManager;"
        + "Lnet/minecraft/util/profiling/ProfilerFiller;)Ljava/util/List;";
    private static final String APPLY = "apply(Ljava/util/List;Lnet/minecraft/server/packs/resources/ResourceManager;"
        + "Lnet/minecraft/util/profiling/ProfilerFiller;)V";

    @Inject(method = PREPARE, at = @At("HEAD"), remap = false, require = 1)
    private void folkways$prepareHead(ResourceManager resources, ProfilerFiller profiler,
            CallbackInfoReturnable<List<ResourceLocation>> callback) {
        AnimationPairingEvidence.reloadPhase((AnimationManager) (Object) this, "prepare-head", side());
    }

    @Inject(method = PREPARE, at = @At("TAIL"), remap = false, require = 1)
    private void folkways$prepareTail(ResourceManager resources, ProfilerFiller profiler,
            CallbackInfoReturnable<List<ResourceLocation>> callback) {
        AnimationPairingEvidence.reloadPhase((AnimationManager) (Object) this, "prepare-tail", side());
    }

    @Inject(method = APPLY, at = @At("HEAD"), remap = false, require = 1)
    private void folkways$applyHead(List<ResourceLocation> files, ResourceManager resources,
            ProfilerFiller profiler, CallbackInfo callback) {
        AnimationPairingEvidence.reloadPhase((AnimationManager) (Object) this, "apply-head", side());
    }

    @Inject(method = APPLY, at = @At("TAIL"), remap = false, require = 1)
    private void folkways$applyTail(List<ResourceLocation> files, ResourceManager resources,
            ProfilerFiller profiler, CallbackInfo callback) {
        AnimationPairingEvidence.reloadPhase((AnimationManager) (Object) this, "apply-tail", side());
    }

    @Inject(method = "validateClientAnimationRegistry(Lyesman/epicfight/network/client/CPPairingAnimationRegistry;"
        + "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)V", at = @At("HEAD"), remap = false, require = 1)
    private void folkways$beforeServerValidation(CPPairingAnimationRegistry packet,
            ServerGamePacketListenerImpl connection, CallbackInfo callback) {
        AnimationPairingEvidence.serverValidate((AnimationManager) (Object) this, packet, connection);
    }

    private static String side() {
        return FMLEnvironment.dist == Dist.CLIENT ? "client" : "server";
    }
}
