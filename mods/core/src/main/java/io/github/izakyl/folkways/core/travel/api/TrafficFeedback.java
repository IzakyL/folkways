package io.github.izakyl.folkways.core.travel.api;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import net.minecraft.server.level.ServerLevel;

public interface TrafficFeedback {

    TrafficFeedback NONE = new TrafficFeedback() {

        @Override
        public void wrongEdge(ServerLevel level, WorldPos from, WorldPos to) {
        }

        @Override
        public void walkedEdge(ServerLevel level, WorldPos from, WorldPos to, int ticks) {
        }
    };

    void wrongEdge(ServerLevel level, WorldPos from, WorldPos to);

    void walkedEdge(ServerLevel level, WorldPos from, WorldPos to, int ticks);
}
