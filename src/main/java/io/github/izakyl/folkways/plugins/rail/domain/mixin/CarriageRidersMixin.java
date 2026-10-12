package io.github.izakyl.folkways.plugins.rail.domain.mixin;

import io.github.izakyl.folkways.plugins.rail.passage.Ridership;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Where a carriage runs over ground no one has loaded, Create saves its riders into the carriage and discards them,
// to put them back from that save once it is loaded again. A resident so carried off is told to its colony first, so
// the discard does not read as the resident being gone.
@Mixin(targets = "com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity")
public abstract class CarriageRidersMixin {

    @Redirect(
        method = "removeAndSaveEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;discard()V"),
        require = 1)
    private void folkways$carryOffWithTheCarriage(Entity rider) {
        Ridership.carriedOff(rider);
        rider.discard();
    }

    @Redirect(
        method = "updatePassengerLoadout",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;discard()V"),
        require = 1)
    private void folkways$carryOffPastTheLoadedEnd(Entity rider) {
        Ridership.carriedOff(rider);
        rider.discard();
    }
}
