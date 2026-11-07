package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Worker;
import net.minecraft.server.level.ServerLevel;

public interface KitchenWork extends Node {
    KitchenClosure closure();

    @Override
    default void released(ServerLevel level, Worker who, Ending how) {
        if (!(how instanceof Ending.Done)) {
            ended(level, how);
        }
    }

    @Override
    default void ended(ServerLevel level, Ending how) {
        closure().close(how);
    }
}
