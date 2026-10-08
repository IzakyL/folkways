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
    private final int period;
    private final AtomicBoolean offerPending = new AtomicBoolean();
    private int sinceSweep;

    public Sweep(Colony colony, ResourceLocation owner, int period) {
        this.asking = new Asking(colony, owner, this::nudge);
        this.period = period;
        this.sinceSweep = period;
    }

    public void nudge() {
        offerPending.set(true);
    }

    public void tick(MinecraftServer server, Consumer<MinecraftServer> refresh,
            Function<ColonyView, List<Grown>> goals) {
        if (++sinceSweep >= period) {
            sinceSweep = 0;
            refresh.accept(server);
            offerPending.set(true);
        }
        if (offerPending.getAndSet(false)) {
            asking.offer(server, goals);
        }
    }
}
