package io.github.izakyl.folkways.core.plan.api;

import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import net.minecraft.server.level.ServerLevel;

public interface Intent extends Node {
    @Override
    default Outcome commit(ServerLevel level, Worker who) {
        throw new IllegalStateException("unrefined intent reached execution: " + id());
    }
}
