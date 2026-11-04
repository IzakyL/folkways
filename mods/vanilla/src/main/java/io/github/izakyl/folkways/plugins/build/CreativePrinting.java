package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Creative raises spend no materials and share a small placement budget per server tick. */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.GAME)
public final class CreativePrinting {
    private static final int MAX_PENDING = 8;
    private static final long SLICE_NANOS = 8_000_000L;
    private static final Map<MinecraftServer, ArrayDeque<SchematicPlacement.Printing>> PENDING =
        new IdentityHashMap<>();

    private CreativePrinting() {
    }

    static CompletableFuture<Void> enqueue(ServerLevel level, Blueprint resolved, BlockPos anchor) {
        var pending = PENDING.computeIfAbsent(level.getServer(), server -> new ArrayDeque<>());
        if (pending.size() >= MAX_PENDING) {
            throw new StructureCompat.Failure("folkways.refusal.build.structure_budget");
        }
        var printing = new SchematicPlacement.Printing(level, resolved, anchor);
        pending.addLast(printing);
        return printing.done;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        var pending = PENDING.get(event.getServer());
        if (pending == null) {
            return;
        }
        long deadline = System.nanoTime() + SLICE_NANOS;
        while (!pending.isEmpty()) {
            var printing = pending.getFirst();
            try {
                if (!printing.advance(deadline)) {
                    break;
                }
                pending.removeFirst();
                printing.complete();
            } catch (RuntimeException failed) {
                pending.removeFirst();
                printing.done.completeExceptionally(failed);
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        if (pending.isEmpty()) {
            PENDING.remove(event.getServer());
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        var pending = PENDING.remove(event.getServer());
        if (pending != null) {
            for (var printing : pending) {
                printing.done.completeExceptionally(
                    new CancellationException("Server stopped during creative printing"));
            }
        }
    }
}
