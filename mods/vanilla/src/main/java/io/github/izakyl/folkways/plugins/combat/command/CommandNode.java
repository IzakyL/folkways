package io.github.izakyl.folkways.plugins.combat.command;

import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import net.minecraft.server.level.ServerLevel;

final class CommandNode implements Node {
    private final ResidentCommands commands;
    private final ResidentCommands.Order order;
    private final NodeSpec spec;

    CommandNode(ResidentCommands commands, ResidentCommands.Order order) {
        this.commands = commands;
        this.order = order;
        Workload.Continuous hold = (level, who) -> commands.arrived(order);
        spec = NodeSpec.of(order.id(), CommandContent.ID, new WorkSite.AtBlock(order.destination()),
                Stances.at(order.destination()), hold)
            .doing(CommandContent.ID)
            .pace(1.3)
            .done();
    }

    public NodeSpec spec() { return spec; }
    public boolean directTravel() { return true; }
    public boolean staysAtWorkSite() { return true; }
    public boolean ready(ServerLevel level) { return commands.active(order); }
    public void changed(ServerLevel level, NodeState state) {
        if (state == NodeState.WORKING) { commands.taking(order); }
    }
    public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
    public void released(ServerLevel level, Worker who, Ending how) { commands.ended(order, how); }
}
