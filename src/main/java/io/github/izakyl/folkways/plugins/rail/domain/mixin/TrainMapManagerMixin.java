package io.github.izakyl.folkways.plugins.rail.domain.mixin;

import com.simibubi.create.compat.trainmap.TrainMapManager;
import com.simibubi.create.content.trains.entity.Train;
import io.github.izakyl.folkways.plugins.rail.domain.RailTooltip;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TrainMapManager.class)
public class TrainMapManagerMixin {

    @Inject(
        method = "listTrainDetails(Lcom/simibubi/create/content/trains/entity/Train;)Ljava/util/List;",
        at = @At("RETURN"),
        cancellable = true,
        require = 1)
    private static void folkways$nameTheCrew(Train train, CallbackInfoReturnable<List<FormattedText>> cir) {
        List<FormattedText> shown = cir.getReturnValue();
        if (shown == null || shown.isEmpty()) {
            return;
        }
        List<Component> crew = RailTooltip.lines(train.id);
        if (crew.isEmpty()) {
            return;
        }
        List<FormattedText> merged = new ArrayList<>(shown);
        merged.addAll(crew);
        cir.setReturnValue(merged);
    }
}
