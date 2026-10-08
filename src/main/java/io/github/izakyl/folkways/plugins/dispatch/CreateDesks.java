package io.github.izakyl.folkways.plugins.dispatch;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class CreateDesks {

    private static final Set<StockTickerBlockEntity> SEEN =
        Collections.newSetFromMap(new WeakHashMap<>());

    private CreateDesks() {
    }

    public static synchronized void track(StockTickerBlockEntity desk) {
        SEEN.add(desk);
    }

    static synchronized List<BlockPos> in(ServerLevel level, UUID network) {
        List<BlockPos> found = new ArrayList<>();
        for (StockTickerBlockEntity desk : SEEN) {
            BlockPos pos = desk.getBlockPos();
            if (desk.getLevel() != level || desk.isRemoved() || !level.hasChunkAt(pos)
                    || level.getBlockEntity(pos) != desk) {
                continue;
            }
            LogisticallyLinkedBehaviour link = BlockEntityBehaviour.get(desk, LogisticallyLinkedBehaviour.TYPE);
            if (link != null && network.equals(link.freqId)) {
                found.add(pos.immutable());
            }
        }
        return List.copyOf(found);
    }
}
