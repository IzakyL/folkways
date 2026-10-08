package io.github.izakyl.folkways.plugins.dispatch.mixin;

import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import io.github.izakyl.folkways.plugins.dispatch.CreateDesks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StockTickerBlockEntity.class)
public abstract class StockTickerBlockEntityMixin {

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void folkways$rememberDesk(CallbackInfo ci) {
        CreateDesks.track((StockTickerBlockEntity) (Object) this);
    }
}
