package io.github.izakyl.folkways.core.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.base.task.ColonyLog;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.ThreadContext;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerLevel.class)
public abstract class ResidentTickMixin {

    @WrapMethod(method = "tickNonPassenger")
    private void folkways$colonyTick(Entity entity, Operation<Void> original) {
        UUID colony = Bodies.colonyOf(entity).orElse(null);
        if (colony == null && !ThreadContext.containsKey(ColonyLog.KEY)) {
            original.call(entity);
            return;
        }
        try (var ignored = ColonyLog.open(colony)) {
            original.call(entity);
        }
    }

    @WrapMethod(method = "tickPassenger")
    private void folkways$colonyPassengerTick(Entity vehicle, Entity passenger, Operation<Void> original) {
        UUID colony = Bodies.colonyOf(passenger).orElse(null);
        if (colony == null && !ThreadContext.containsKey(ColonyLog.KEY)) {
            original.call(vehicle, passenger);
            return;
        }
        try (var ignored = ColonyLog.open(colony)) {
            original.call(vehicle, passenger);
        }
    }
}
