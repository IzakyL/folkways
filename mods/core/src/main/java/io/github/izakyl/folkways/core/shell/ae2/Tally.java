package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.networking.crafting.ICraftingCPU;
import io.github.izakyl.folkways.core.inventory.api.port.PortReader;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.shell.crafting.NetworkRefusal;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;

// Shared across colonies as well as devices: one physical CPU can only be promised once.
final class Tally {

    private static final WeakHashMap<MinecraftServer, Tally> SERVERS = new WeakHashMap<>();
    private final List<Ticket> tickets = new ArrayList<>();

    static synchronized Tally of(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, ignored -> new Tally());
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

    synchronized long available(Devices.NetworkKey network, List<PortReader.Entry> stock, long readAt, ItemSpec want) {
        long present = stock.stream().filter(entry -> Goods.matches(entry.prototype(), want))
            .mapToLong(PortReader.Entry::count).sum();
        long held = tickets.stream().filter(ticket -> ticket.network.equals(network) && !ticket.released
                && (ticket.takenAt < 0 || ticket.takenAt >= readAt) && Goods.overlap(ticket.want, want))
            .mapToLong(ticket -> ticket.count).sum();
        return Math.max(0, present - held);
    }

    synchronized boolean free(Cpu cpu) {
        return tickets.stream().noneMatch(ticket -> ticket.cpu != null
            && ticket.cpu.key().equals(cpu.key()) && !ticket.cleaned);
    }

    // Crafting orders this colony holds on the network now, against its share of the network's CPUs.
    synchronized int crafting(UUID owner, Devices.NetworkKey network) {
        return (int) tickets.stream().filter(ticket -> ticket.crafting && !ticket.released && !ticket.cleaned
            && ticket.owner.equals(owner) && ticket.network.equals(network)).count();
    }

    Ticket ticket(UUID owner, Devices.NetworkKey network, List<PortReader.Entry> stock, long readAt,
                  ItemSpec want, long count, List<Cpu> cpus, boolean crafting) {
        return new Ticket(owner, network, stock, readAt, want, count, cpus, crafting);
    }

    synchronized void close(UUID owner) {
        tickets.stream().filter(ticket -> ticket.owner.equals(owner)).forEach(ticket -> ticket.released = true);
        clean();
    }

    static synchronized void stopped(MinecraftServer server) {
        Tally tally = SERVERS.remove(server);
        if (tally != null) {
            synchronized (tally) {
                tally.tickets.forEach(ticket -> ticket.released = true);
                tally.clean();
            }
        }
    }

    synchronized void clean() {
        for (Ticket ticket : tickets) {
            if (ticket.released && !ticket.cleaned) {
                if (ticket.order != null) {
                    ticket.order.cancel();
                }
                ticket.cleaned = true;
            }
        }
        tickets.removeIf(ticket -> ticket.cleaned);
    }

    final class Ticket implements Hold {
        final UUID owner;
        final Devices.NetworkKey network;
        final ItemSpec want;
        final long count;
        private final List<PortReader.Entry> stock;
        private final long readAt;
        private final List<Cpu> candidates;
        private final boolean crafting;
        volatile Cpu cpu;
        volatile CraftOrder order;
        int estimate;
        int limit = Integer.MAX_VALUE;
        Map<String, Long> patterns = Map.of();
        volatile long takenAt = -1;
        private boolean taken;
        private boolean released;
        private boolean cleaned;

        Ticket(UUID owner, Devices.NetworkKey network, List<PortReader.Entry> stock, long readAt,
               ItemSpec want, long count, List<Cpu> candidates, boolean crafting) {
            this.owner = owner;
            this.network = network;
            this.stock = List.copyOf(stock);
            this.readAt = readAt;
            this.want = want;
            this.count = count;
            this.candidates = List.copyOf(candidates);
            this.crafting = crafting;
        }

        @Override
        public Optional<RefusalKind> take() {
            synchronized (Tally.this) {
                if (taken) {
                    return Optional.empty();
                }
                if (released) {
                    return Optional.of(Ae2Refusal.CANCELED);
                }
                if (crafting && crafting(owner, network) >= limit) {
                    return Optional.of(NetworkRefusal.LIMIT);
                }
                if (crafting) {
                    cpu = candidates.stream().filter(Tally.this::free)
                        .sorted(Comparator.comparingLong(Cpu::bytes).reversed())
                        .findFirst().orElse(null);
                    if (cpu == null) {
                        return Optional.of(Ae2Refusal.CPU_BUSY);
                    }
                } else if (available(network, stock, readAt, want) < count) {
                    return Optional.of(Ae2Refusal.NO_STOCK);
                }
                tickets.add(this);
                taken = true;
                return Optional.empty();
            }
        }

        ICraftingCPU nativeCpu() {
            return Tally.this.nativeCpu(cpu);
        }

        @Override
        public long estimatedWaitTicks() {
            return crafting ? estimate : 0;
        }

        @Override
        public void release(Ending how) {
            synchronized (Tally.this) {
                // A completed take stays debited until the next server snapshot excludes its goods.
                if (takenAt < 0 || !(how instanceof Ending.Done)) {
                    released = true;
                }
            }
        }

        void observed(long now) {
            synchronized (Tally.this) {
                if (takenAt >= 0 && takenAt < now) {
                    released = true;
                }
            }
        }
    }

    synchronized void observed(long now) {
        tickets.forEach(ticket -> ticket.observed(now));
    }
}
