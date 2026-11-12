package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageListener;
import com.refinedmods.refinedstorage.api.resource.list.MutableResourceList;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

final class NetworkEvents {

    private static final Map<MinecraftServer, Map<RootStorage, Watch>> SERVERS = new WeakHashMap<>();

    private NetworkEvents() {
    }

    static long revision(ServerLevel level, BlockPos pos, RootStorage storage) {
        var watches = SERVERS.computeIfAbsent(level.getServer(), ignored -> new IdentityHashMap<>());
        Watch watch = watches.computeIfAbsent(storage, key -> new Watch(level.getServer(), key));
        watch.devices.put(WorldPos.of(level, pos), level.getServer().overworld().getGameTime());
        return watch.revision;
    }

    static void tick(MinecraftServer server) {
        var watches = SERVERS.get(server);
        if (watches == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        watches.values().removeIf(watch -> {
            watch.devices.values().removeIf(seen -> now - seen > 100);
            if (!watch.devices.isEmpty()) {
                return false;
            }
            watch.storage.removeListener(watch);
            return true;
        });
    }

    static void stopped(MinecraftServer server) {
        var watches = SERVERS.remove(server);
        if (watches != null) {
            watches.values().forEach(watch -> watch.storage.removeListener(watch));
        }
    }

    private static final class Watch implements RootStorageListener {
        private final MinecraftServer server;
        private final RootStorage storage;
        private final Map<WorldPos, Long> devices = new HashMap<>();
        private long revision;

        Watch(MinecraftServer server, RootStorage storage) {
            this.server = server;
            this.storage = storage;
            storage.addListener(this);
        }

        @Override
        public void changed(MutableResourceList.OperationResult change) {
            revision++;
            // The native storage mutates on the server thread. Notify every currently observed access point.
            for (var at : devices.keySet()) {
                var level = at.level(server).orElse(null);
                if (level != null && level.isLoaded(at.block(level))) {
                    StorePorts.changed(level, at.block(level));
                }
            }
        }
    }
}
