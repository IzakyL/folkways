package io.github.izakyl.folkways.epicfight.mixin;

import io.github.izakyl.folkways.epicfight.AnimationPairingEvidence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.network.EpicFightNetworkManager;
import yesman.epicfight.network.client.CPPairingAnimationRegistry;

@Mixin(value = EpicFightNetworkManager.class, remap = false)
public abstract class EpicFightNetworkManagerEvidenceMixin {
    @Inject(method = "sendToServer(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;"
        + "[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",
        at = @At("HEAD"), remap = false, require = 1)
    private static void folkways$beforePairingSend(CustomPacketPayload first, CustomPacketPayload[] others,
            CallbackInfo callback) {
        if (first instanceof CPPairingAnimationRegistry pairing) {
            try {
                ClientPacketListener listener = Minecraft.getInstance().getConnection();
                AnimationPairingEvidence.clientSend(AnimationManager.getInstance(), pairing,
                    listener == null ? null : listener.getConnection());
            } catch (RuntimeException | LinkageError failed) {
                AnimationPairingEvidence.failed(failed);
            }
        }
    }
}
