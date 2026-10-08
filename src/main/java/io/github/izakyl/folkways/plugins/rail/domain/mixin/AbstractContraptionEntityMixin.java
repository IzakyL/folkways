package io.github.izakyl.folkways.plugins.rail.domain.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import io.github.izakyl.folkways.plugins.rail.domain.ConductorSeat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContraptionEntity.class)
public abstract class AbstractContraptionEntityMixin {

    @Inject(
        method = "handlePlayerInteraction(Lnet/minecraft/world/entity/player/Player;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;"
            + "Lnet/minecraft/world/InteractionHand;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1)
    private void folkways$handOverInsteadOfSitting(
        Player player, BlockPos localPos, Direction side, InteractionHand hand,
        CallbackInfoReturnable<Boolean> cir) {

        if (ConductorSeat.handle(player, hand, localPos, (AbstractContraptionEntity) (Object) this)) {
            cir.setReturnValue(true);
        }
    }
}
