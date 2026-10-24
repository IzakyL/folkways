package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlRefusal;
import io.github.izakyl.folkways.plugins.damagecontrol.SuppressionMethod;
import io.github.izakyl.folkways.plugins.damagecontrol.SuppressionSession;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

final class ExtinguisherAdapter implements SuppressionMethod {
    private final Map<UUID, Set<WorldPos>> sources = new LinkedHashMap<>();

    @Override
    public void inspect(Colony colony, ZoneView zone, ServerLevel level, BlockPos pos, BlockState state) {
        var water = sources.computeIfAbsent(colony.id(), id -> new LinkedHashSet<>());
        WorldPos at = zone.at(pos);
        if (state.is(Blocks.WATER) && state.getFluidState().isSource()) {
            if (zone.settings().flag("refill") && water.size() < 256) {
                water.add(at);
            }
        } else {
            water.remove(at);
        }
    }

    List<WorldPos> sources(UUID colony) {
        return List.copyOf(sources.getOrDefault(colony, Set.of()));
    }

    void retain(UUID colony, Set<WorldPos> allowed) {
        var water = sources.get(colony);
        if (water != null) {
            water.retainAll(allowed);
        }
    }

    void forget(UUID colony) {
        sources.remove(colony);
    }

    static ItemStack find(Container pack) {
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            if (stack.is(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get())) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean available(Worker who) {
        var fluid = FireExtinguisherItem.getFluid(find(who.pack()));
        return fluid.is(Fluids.WATER) && fluid.getAmount() >= 5;
    }

    @Override
    public double range() { return 4; }

    @Override
    public int fluid(Container pack) {
        return FireExtinguisherItem.getFluid(find(pack)).getAmount();
    }

    @Override
    public Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target) {
        ItemStack stack = find(who.pack());
        var water = FireExtinguisherItem.getFluid(stack);
        if (stack.isEmpty() || !water.is(Fluids.WATER) || water.getAmount() < 5) {
            return Optional.empty();
        }
        return Optional.of(new SuppressionSession() {
            private boolean closed;

            @Override
            public Outcome suppress(ServerLevel world, BlockPos at) {
                if (closed || !who.body().isAlive() || who.body().isRemoved() || find(who.pack()) != stack) {
                    return Outcome.failed(DamageControlRefusal.NO_WATER);
                }
                Vec3 origin = who.body().getEyePosition();
                Vec3 direction = Vec3.atCenterOf(at).subtract(origin).normalize();
                boolean sprayed = NativeSprayBridge.spray(world, who.body(), origin, direction, at, stack);
                who.pack().setChanged();
                return sprayed ? Outcome.done() : Outcome.failed(DamageControlRefusal.NO_REACH);
            }

            @Override
            public void close() { closed = true; }
        });
    }
}
