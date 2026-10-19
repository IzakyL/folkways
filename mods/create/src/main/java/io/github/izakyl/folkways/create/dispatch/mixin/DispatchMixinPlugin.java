package io.github.izakyl.folkways.create.dispatch.mixin;

import io.github.izakyl.folkways.base.FolkwaysMixinPlugin;
import java.util.List;
import java.util.Set;

public final class DispatchMixinPlugin extends FolkwaysMixinPlugin {
    @Override
    protected Set<String> createOnly() {
        return Set.of("StockTickerBlockEntityMixin", "PackagePortBlockEntityMixin", "StationBlockEntityMixin",
            "GlobalStationMixin");
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of(Degradable.common("StockTickerBlockEntityMixin",
            "com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity",
            "colonies cannot discover stock tickers to staff their logistics networks"),
            Degradable.essential("PackagePortBlockEntityMixin",
                "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity",
                "colonies cannot send anything to their pick-up points"),
            Degradable.common("StationBlockEntityMixin",
                "com.simibubi.create.content.trains.station.StationBlockEntity",
                "a train never unloads what the colony sends by train at the postbox it was sent to"),
            Degradable.common("GlobalStationMixin",
                "com.simibubi.create.content.trains.station.GlobalStation",
                "a parcel the colony sends by train leaves aboard whichever train calls first, not the one booked "
                    + "for it, and may be carried the long way round"));
    }
}
