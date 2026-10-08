package io.github.izakyl.folkways.plugins.rail.domain.mixin;

import com.simibubi.create.content.trains.entity.Train;
import io.github.izakyl.folkways.plugins.rail.domain.Departures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Train.class)
public class TrainMixin {

    @Inject(method = "leaveStation()V", at = @At("HEAD"), require = 1)
    private void folkways$countTheDeparture(CallbackInfo ci) {
        Train train = (Train) (Object) this;
        if (train.getCurrentStation() != null) {
            Departures.left(train.id);
        }
    }
}
