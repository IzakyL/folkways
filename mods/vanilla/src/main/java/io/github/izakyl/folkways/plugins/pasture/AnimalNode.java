package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.PastDay;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;

abstract class AnimalNode extends PenNode {

    AnimalNode(NodeSpec spec, Chore chore) {
        super(spec, chore);
    }

    AnimalNode(NodeSpec spec, UUID animal, PastDay made) {
        super(spec, animal, made);
    }

    final UUID animal() {
        return subject();
    }

    @Override
    public final boolean ready(ServerLevel level) {
        return standing(level) != null;
    }

    @Override
    public final Outcome commit(ServerLevel level, Worker who) {
        Animal beast = standing(level);
        return beast == null ? refuse(PastureRefusal.BEAST_GONE) : counted(level, work(level, who, beast));
    }

    private Animal standing(ServerLevel level) {
        Entity found = level.getEntity(animal());
        return found instanceof Animal beast && beast.isAlive() && !beast.isRemoved() ? beast : null;
    }

    abstract Outcome work(ServerLevel level, Worker who, Animal beast);
}
