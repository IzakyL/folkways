package io.github.izakyl.folkways.core.api.work;

import net.minecraft.server.level.ServerLevel;

public interface Intent extends Node {
    @Override
    default Outcome commit(ServerLevel level, Worker who) {
        throw new IllegalStateException("unrefined intent reached execution: " + id());
    }
}
