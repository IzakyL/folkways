package io.github.izakyl.folkways.core.api.colony;

import io.github.izakyl.folkways.core.api.work.Grown;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

public final class Sweep {

    private final Asking asking;
    // When the sweep runs within its period, spread against every other sweep's; see Cadence.
    private final Cadence.Cron cron;
    private final AtomicBoolean offerPending = new AtomicBoolean();
    private boolean swept;

    public Sweep(Colony colony, ResourceLocation owner, int period) {
        this.asking = new Asking(colony, owner, this::nudge);
        this.cron = Cadence.claim(colony, period);
    }

    public void nudge() {
        offerPending.set(true);
    }

    // The first tick sweeps at once; after that, on the cron's own tick of each period.
    public void tick(MinecraftServer server, Consumer<MinecraftServer> refresh,
            Function<ColonyView, List<Grown>> goals) {
        if (!swept || cron.due(server)) {
            swept = true;
            refresh.accept(server);
            offerPending.set(true);
        }
        if (offerPending.getAndSet(false)) {
            asking.offer(server, goals);
        }
    }
}
