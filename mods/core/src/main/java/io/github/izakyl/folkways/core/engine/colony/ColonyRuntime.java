package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Closing;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

public final class ColonyRuntime {

    private static final Map<MinecraftServer, Run> RUNS = new LinkedHashMap<>();

    private ColonyRuntime() {
    }

    public static void opened(MinecraftServer server) {
        Run run = RUNS.computeIfAbsent(server, Run::new);
        for (ColonyData colony : ColonyRegistry.get(server).colonies()) {
            run.settle(colony.works());
        }
    }

    public static void closeAll(MinecraftServer server) {
        Run run = RUNS.remove(server);
        if (run != null) {
            run.closed(Closing.SHUTDOWN);
        }
        ColonyWays.forgetServer(server);
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
