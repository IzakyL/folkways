package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.resident.api.Doing;
import net.minecraft.server.level.ServerLevel;

// One executable action, either a planned node or a private step inside a holding.
public interface WorkAction {

    ActionSpec spec();

    default Doing.Wares wares() {
        return spec().wares();
    }

    // Short local actions can use locomotion directly instead of planning a multimodal journey.
    default boolean directTravel() {
        return false;
    }

    default boolean staysAtWorkSite() {
        return false;
    }

    // Actions that can resume a finite wind-up keep the ticks already performed when suspended.
    default boolean resumesWindup() {
        return false;
    }

    Outcome commit(ServerLevel level, Worker who);
}
