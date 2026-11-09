package io.github.izakyl.folkways.core.rts.combat.command;

import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import net.minecraft.server.level.ServerLevel;

final class CommandNode implements Node {
    private final ResidentCommands commands;
    private final ResidentCommands.Order order;
    private final NodeSpec spec;

    CommandNode(ResidentCommands commands, ResidentCommands.Order order) {
        this.commands = commands;
        this.order = order;
        Workload.Continuous hold = (level, who) -> commands.arrived(order);
        spec =
            NodeSpec.of(
            order.id(),
            CommandContent.ID,
            new WorkSite.AtBlock(order.destination()),
            Stances.at(order.destination()),
            hold)
            .pace(order.mode() == ResidentCommands.Mode.RETREAT ? 1.3 : 1.0)
            .done();
    }

    public NodeSpec spec() {
        return spec;
    }

    public boolean mountedWork() {
        return true;
    }

    public boolean directTravel() {
        return true;
    }

    public boolean staysAtWorkSite() {
        return true;
    }

    public boolean ready(ServerLevel level) {
        return commands.active(order);
    }

    public void changed(ServerLevel level, NodeState state) {
        if (state == NodeState.WORKING) {
            commands.taking(order);
        }
    }

    public Outcome commit(ServerLevel level, Worker who) {
        return Outcome.done();
    }

    public void released(ServerLevel level, Worker who, Ending how) {
        commands.ended(order, how);
    }
}
