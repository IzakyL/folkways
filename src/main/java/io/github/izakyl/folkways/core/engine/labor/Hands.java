package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.plan.Stands;
import io.github.izakyl.folkways.core.engine.plan.Stores;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

final class Hands implements Worker {

    @FunctionalInterface
    interface Spill {
        void fell(ItemEntity drop);
    }

    private final Body body;
    private final Stands stands;
    private final Placement placement;
    private final Spill spill;
    private final Supplier<List<Node>> route;

    Hands(Body body, Stands stands, Placement placement, Spill spill, Supplier<List<Node>> route) {
        this.body = body;
        this.stands = stands;
        this.placement = placement;
        this.spill = spill;
        this.route = route;
    }

    @Override
    public Resident resident() {
        return body.resident();
    }

    @Override
    public Mob body() {
        return body.mob();
    }

    @Override
    public Container pack() {
        return body.pack();
    }

    @Override
    public Placement placement() {
        return placement;
    }

    @Override
    public List<Store> within() {
        if (!(body.mob().level() instanceof ServerLevel level)) {
            return List.of();
        }
        Set<Stash> reachable = new LinkedHashSet<>(placement.from());
        reachable.addAll(placement.into());
        if (stands != null) {
            reachable.addAll(stands.reach(Stand.at(WorldSpaces.at(body.mob()))));
        }
        List<Store> found = new ArrayList<>();
        for (Stash stash : reachable) {
            if (!stash.pos().in(level)) {
                continue;
            }
            Stores.at(level, stash.pos())
                .ifPresent(held -> found.add(new Store(stash.pos(), held)));
        }
        return List.copyOf(found);
    }

    @Override
    public ItemStack held(ToolNeed need) {
        return Tools.from(body.pack().contents(), need).orElse(ItemStack.EMPTY);
    }

    @Override
    public void spill(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemEntity dropped = body.mob().spawnAtLocation(stack);
        if (dropped != null) {
            spill.fell(dropped);
        }
    }

    @Override
    public int rankOf(String perk) {
        return body.perks().rank(perk);
    }

    @Override
    public List<Node> route() {
        return route.get();
    }
}
