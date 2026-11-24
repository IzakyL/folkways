package io.github.izakyl.folkways.core.shell.create.actuator.mixin;

import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Create's own rule for which neighbours a kinetic block reaches and at what ratio, read rather than re-derived. */
@Mixin(value = RotationPropagator.class, remap = false)
public interface RotationPropagatorAccess {

    @Invoker("getRotationSpeedModifier")
    static float folkways$modifier(KineticBlockEntity from, KineticBlockEntity to) {
        throw new AssertionError();
    }

    @Invoker("getPotentialNeighbourLocations")
    static List<BlockPos> folkways$neighbours(KineticBlockEntity block) {
        throw new AssertionError();
    }
}
