package io.github.izakyl.folkways.core.api.work.batch;

import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import net.minecraft.server.level.ServerLevel;

public interface BatchAction extends Node {
    BatchCommit execute(ServerLevel level, Worker who);

    @Override
    default Outcome commit(ServerLevel level, Worker who) { return execute(level, who).outcome(); }
}
