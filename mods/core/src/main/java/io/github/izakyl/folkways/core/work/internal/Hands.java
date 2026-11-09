package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.internal.Stands;
import io.github.izakyl.folkways.core.inventory.internal.Stores;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.support.Tools;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.effect.Effects;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

public final class Hands implements Worker {

    private final Body body;
    private final Stands stands;
    private final Placement placement;
    private final Consumer<ItemEntity> spill;
    private final Supplier<List<Node>> route;

    public Hands(Body body, Stands stands, Placement placement, Consumer<ItemEntity> spill, Supplier<List<Node>> route) {
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
            spill.accept(dropped);
        }
    }

    @Override
    public Effects effects() {
        return body.effects();
    }

    @Override
    public List<Node> route() {
        return route.get();
    }
}
