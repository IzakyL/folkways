package io.github.izakyl.folkways.core.mixin;

import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.facility.api.ExternalAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class AttachmentHostsMixin {

    @Inject(method = "setBlockEntity", at = @At("TAIL"))
    private void folkways$trackAttachment(BlockEntity entity, CallbackInfo callback) {
        if (entity.getLevel() instanceof ServerLevel level) {
            ExternalAttachments.track(level, entity);
            Facilities.ownershipBlockChanged(level, entity.getBlockPos());
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("TAIL"))
    private void folkways$attachmentRemoved(BlockPos pos, CallbackInfo callback) {
        if (((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level) {
            Facilities.ownershipBlockChanged(level, pos);
        }
    }
}
