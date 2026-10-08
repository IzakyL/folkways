package io.github.izakyl.folkways.core.shell.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import io.github.izakyl.folkways.core.shell.structure.SableMoves;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(value = SubLevelAssemblyHelper.class, remap = false)
public abstract class SableAssemblyMixin {
    @WrapMethod(method = "moveBlocks")
    private static void folkways$moveAddresses(ServerLevel level, AssemblyTransform transform,
                                              Iterable<BlockPos> blocks, Operation<Void> original) {
        List<BlockPos> cells = new ArrayList<>();
        blocks.forEach(block -> cells.add(block.immutable()));
        var moved = SableMoves.before(level, transform, cells);
        original.call(level, transform, cells);
        SableMoves.after(level, transform, moved);
    }
}
