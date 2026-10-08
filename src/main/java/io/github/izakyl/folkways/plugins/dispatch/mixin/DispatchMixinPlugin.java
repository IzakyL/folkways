package io.github.izakyl.folkways.plugins.dispatch.mixin;

import io.github.izakyl.folkways.core.api.FolkwaysMixinPlugin;
import java.util.List;
import java.util.Set;

public final class DispatchMixinPlugin extends FolkwaysMixinPlugin {
    @Override
    protected Set<String> createOnly() {
        return Set.of("StockTickerBlockEntityMixin", "PackagePortBlockEntityMixin");
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of(Degradable.common("StockTickerBlockEntityMixin",
            "com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity",
            "colonies cannot discover stock tickers to staff their logistics networks"),
            Degradable.essential("PackagePortBlockEntityMixin",
                "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity",
                "colonies cannot send anything to their pick-up points"));
    }
}
