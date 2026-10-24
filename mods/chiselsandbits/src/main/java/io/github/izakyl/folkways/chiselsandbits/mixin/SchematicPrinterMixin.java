package io.github.izakyl.folkways.chiselsandbits.mixin;

import com.simibubi.create.content.schematics.SchematicPrinter;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import io.github.izakyl.folkways.chiselsandbits.SchematicBits;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SchematicPrinter.class, remap = false)
abstract class SchematicPrinterMixin {
    @Shadow private SchematicLevel blockReader;
    @Shadow private boolean isErrored;

    @WrapMethod(method = "loadSchematic")
    private void folkways$boundedSchematic(ItemStack schematic, Level level, boolean processNbt, Operation<Void> original) {
        try (var processing = StructureCompat.processing()) {
            original.call(schematic, level, processNbt);
        } catch (StructureCompat.Failure tooComplex) {
            isErrored = true;
        }
    }

    @Inject(method = "loadSchematic", at = @At("RETURN"))
    private void folkways$refuseInvalidBits(ItemStack schematic, Level level, boolean processNbt, CallbackInfo ci) {
        if (blockReader == null) return;
        for (var entity : blockReader.getBlockEntities()) {
            if (entity instanceof SchematicBits bits && !bits.folkways$valid()) {
                // This also stops creative-crate prints, which bypass material matching.
                isErrored = true;
            }
        }
    }
}
