package io.github.izakyl.folkways.create.dispatch;

import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

// Every package port that has been loaded. A frogport that takes no parcels is never listed on its chain, so the
// frogports on a chain net are found here rather than from the chain.
public final class CreatePorts {

    private static final Set<PackagePortBlockEntity> SEEN =
        Collections.newSetFromMap(new WeakHashMap<>());

    private CreatePorts() {
    }

    public static synchronized void track(PackagePortBlockEntity port) {
        SEEN.add(port);
    }

    static synchronized List<BlockPos> frogportsOn(ServerLevel level, Set<BlockPos> chains) {
        List<BlockPos> found = new ArrayList<>();
        for (PackagePortBlockEntity port : SEEN) {
            BlockPos pos = port.getBlockPos();
            if (!(port instanceof FrogportBlockEntity) || port.getLevel() != level || port.isRemoved()
                    || !level.hasChunkAt(pos) || level.getBlockEntity(pos) != port) {
                continue;
            }
            if (CreateLayout.chainOf(port).filter(chains::contains).isPresent()) {
                found.add(pos.immutable());
            }
        }
        return List.copyOf(found);
    }
}
