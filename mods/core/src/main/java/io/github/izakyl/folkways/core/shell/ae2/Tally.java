package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.networking.crafting.ICraftingCPU;
import io.github.izakyl.folkways.core.shell.crafting.CraftRefusals;
import io.github.izakyl.folkways.core.shell.crafting.NetworkTally;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;

// Shared across colonies as well as devices: one physical CPU can only be promised once.
final class Tally extends NetworkTally<Ae2Devices.NetworkKey, Tally.Cpu, ICraftingCPU> {

    private static final WeakHashMap<MinecraftServer, Tally> SERVERS = new WeakHashMap<>();

    static synchronized Tally of(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, ignored -> new Tally());
    }

    Tally() {
        super(CraftRefusals.AE2);
    }

    static synchronized void stopped(MinecraftServer server) {
        Tally tally = SERVERS.remove(server);
        if (tally != null) {
            tally.cancelAll();
        }
    }

    record Cpu(UUID key, long bytes) {
    }

    private final Map<UUID, ICraftingCPU> nativeCpus = new HashMap<>();

    Cpu snapshot(ICraftingCPU cpu) {
        UUID key = nativeCpus.entrySet().stream().filter(entry -> entry.getValue() == cpu)
            .map(Map.Entry::getKey).findFirst().orElseGet(UUID::randomUUID);
        nativeCpus.put(key, cpu);
        return new Cpu(key, cpu.getAvailableStorage());
    }

    ICraftingCPU nativeCpu(Cpu cpu) {
        return nativeCpus.get(cpu.key());
    }

    synchronized boolean free(Cpu cpu) {
        return !reserved(ticket -> ticket.executor() != null && ticket.executor().key().equals(cpu.key()));
    }

    @Override
    protected Cpu select(Ae2Devices.NetworkKey network, List<Cpu> candidates) {
        return candidates.stream().filter(this::free).sorted(Comparator.comparingLong(Cpu::bytes).reversed())
            .findFirst().orElse(null);
    }

    @Override
    protected ICraftingCPU nativeExecutor(Cpu cpu) {
        return nativeCpu(cpu);
    }
}
