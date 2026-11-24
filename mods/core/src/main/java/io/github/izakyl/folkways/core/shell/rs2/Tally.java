package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.crafting.CraftRefusals;
import io.github.izakyl.folkways.core.shell.crafting.NetworkTally;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;

// Shared across colonies and devices. Serialize native jobs per network so previews cannot double-promise inputs.
final class Tally extends NetworkTally<Rs2Devices.NetworkKey, Tally.Executor, PatternProvider> {

    private static final WeakHashMap<MinecraftServer, Tally> SERVERS = new WeakHashMap<>();

    static synchronized Tally of(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, ignored -> new Tally());
    }

    Tally() {
        super(CraftRefusals.RS2);
    }

    static synchronized void stopped(MinecraftServer server) {
        Tally tally = SERVERS.remove(server);
        if (tally != null) {
            tally.cancelAll();
        }
    }

    record Executor(UUID key, int steps, boolean busy, Set<ItemSpec> outputs) {

        Executor {
            outputs = Set.copyOf(outputs);
        }
    }

    private final Map<UUID, PatternProvider> nativeProviders = new HashMap<>();

    Executor snapshot(PatternProvider provider, int steps, boolean busy, Set<ItemSpec> outputs) {
        UUID key = nativeProviders.entrySet().stream().filter(entry -> entry.getValue() == provider)
            .map(Map.Entry::getKey).findFirst().orElseGet(UUID::randomUUID);
        nativeProviders.put(key, provider);
        return new Executor(key, steps, busy, outputs);
    }

    PatternProvider nativeProvider(Executor executor) {
        return nativeProviders.get(executor.key());
    }

    synchronized boolean free(Rs2Devices.NetworkKey network, Executor executor) {
        return !executor.busy() && !reserved(ticket -> (ticket.network().equals(network) || ticket.executor() != null
                && ticket.executor().key().equals(executor.key())));
    }

    @Override
    protected Executor select(Rs2Devices.NetworkKey network, List<Executor> candidates) {
        return candidates.stream().filter(candidate -> free(network, candidate)).findFirst().orElse(null);
    }

    @Override
    protected PatternProvider nativeExecutor(Executor executor) {
        return nativeProvider(executor);
    }
}
