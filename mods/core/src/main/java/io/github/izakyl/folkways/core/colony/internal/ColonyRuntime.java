package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

public final class ColonyRuntime {

    private static final Map<MinecraftServer, Run> RUNS = new LinkedHashMap<>();

    private ColonyRuntime() {
    }

    public static void opened(MinecraftServer server) {
        RUNS.computeIfAbsent(server, Run::new);
        ColonyRegistry.initialize(server).start(server);
    }

    public static void closeAll(MinecraftServer server) {
        Run run = RUNS.remove(server);
        if (run != null) {
            run.closed(Closing.SHUTDOWN);
        }
        ColonyWays.forgetServer(server);
        ColonyRegistry.forget(server);
    }

    static void refresh(MinecraftServer server, ColonyWorks colony) {
        Run run = RUNS.get(server);
        if (run != null) {
            colony.settleIn(run);
        }
    }

    static void settle(MinecraftServer server, ColonyWorks colony) {
        Run run = RUNS.get(server);
        if (run != null) {
            run.settle(colony);
        }
    }

    static final class Run extends Stretch {

        private final MinecraftServer server;

        private Run(MinecraftServer server) {
            this.server = server;
        }

        MinecraftServer server() {
            return server;
        }

        void settle(ColonyWorks colony) {
            if (colony.settled()) {
                return;
            }
            opened(colony).settleIn(this);
        }

        @Override
        protected void ended(Closing why) {
        }
    }
}
