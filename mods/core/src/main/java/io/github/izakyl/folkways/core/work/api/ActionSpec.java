package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

// Execution describes no planning identity: private actions need neither node IDs nor phases.
public interface ActionSpec {

    WorkSite site();

    Stances stances();

    Workload workload();

    default List<Need> needs() {
        return List.of();
    }

    default List<ToolUse> tools() {
        return List.of();
    }

    default WorkEffort effort() {
        return WorkEffort.standard(workload());
    }

    default double pace() {
        return 1.0D;
    }

    default Optional<Vocation> vocation() {
        return Optional.empty();
    }

    default WorkGesture gesture() {
        return WorkGesture.NONE;
    }

    default Optional<BlockPos> focus() {
        return Optional.empty();
    }

    default ResourceLocation doing() {
        return Doings.WORKING;
    }

    default Optional<String> about() {
        return Optional.empty();
    }

    default Doing.Wares wares() {
        return Doing.Wares.NONE;
    }
}
