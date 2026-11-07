package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import com.mikoalopex.createfirefightingadd.content.fluids.nozzle.FireExtinguisherSprayEffects;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
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
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

final class ExtinguisherAdapter implements SuppressionMethod {
    private final Function<UUID, Optional<ItemStack>> unbound;
    private final Map<UUID, FireStock> stocks = new LinkedHashMap<>();
    private final Map<UUID, Set<WorldPos>> sources = new LinkedHashMap<>();

    ExtinguisherAdapter() {
        this(id -> Optional.empty());
    }

    ExtinguisherAdapter(Function<UUID, Optional<ItemStack>> unbound) {
        this.unbound = unbound;
    }

    void bind(Colony colony, FireStock stock) {
        stocks.put(colony.id(), stock);
    }

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
        stocks.remove(colony);
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

    private Optional<ItemStack> observed(Worker who) {
        Optional<UUID> colony = Bodies.of(who.body()).flatMap(body -> body.colonyId());
        FireStock stock = colony.map(stocks::get).orElse(null);
        return stock == null ? unbound.apply(who.resident().id()) : stock.resident(who.resident().id());
    }

    @Override
    public boolean available(Worker who) {
        var fluid = FireExtinguisherItem.getFluid(observed(who).orElse(ItemStack.EMPTY));
        return fluid.is(Fluids.WATER) && !fluid.isEmpty();
    }

    @Override
    public DamageControlRefusal openingRefusal(Worker who) {
        if (observed(who).isEmpty()) {
            return DamageControlRefusal.BUSY;
        }
        return available(who) ? DamageControlRefusal.NO_REACH : DamageControlRefusal.NO_WATER;
    }

    @Override
    public double range() {
        return FireExtinguisherSprayEffects.RANGE;
    }

    @Override
    public Set<BlockPos> footings(ServerLevel level, Worker who, WorldPos target) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        if (!available(who)) {
            return Set.of();
        }
        int span = (int) Math.ceil(range());
        for (BlockPos feet : Reach.footingsAround(level, target.block(level), span, span)) {
            if (SprayFootings.serves(level, who.body(), feet, target.block(level), range(), false)) {
                cells.add(feet);
            }
        }
        return Set.copyOf(cells);
    }

    @Override
    public boolean reaches(ServerLevel level, Worker who, WorldPos target) {
        return available(who);
    }

    @Override
    public int fluid(Container pack) {
        return FireExtinguisherItem.getFluid(find(pack)).getAmount();
    }

    @Override
    public Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target) {
        ItemStack stack = find(who.pack());
        var water = FireExtinguisherItem.getFluid(stack);
        if (stack.isEmpty() || !water.is(Fluids.WATER) || water.isEmpty()) {
            Bodies.of(who.body()).flatMap(body -> body.colonyId()).map(stocks::get)
                .ifPresent(stock -> stock.staleResident(who.resident().id()));
            return Optional.empty();
        }
        return Optional.of(new SuppressionSession() {
            private boolean closed;
            private boolean performed;

            @Override
            public Outcome suppress(ServerLevel world, BlockPos at) {
                performed = false;
                if (closed || !who.body().isAlive() || who.body().isRemoved() || find(who.pack()) != stack) {
                    Bodies.of(who.body()).flatMap(body -> body.colonyId()).map(stocks::get)
                        .ifPresent(stock -> stock.staleResident(who.resident().id()));
                    return Outcome.failed(DamageControlRefusal.NO_WATER);
                }
                Vec3 origin = who.body().getEyePosition();
                Vec3 direction = Vec3.atCenterOf(at).subtract(origin).normalize();
                performed = NativeSprayBridge.spray(world, who.body(), origin, direction, at, stack);
                who.pack().setChanged();
                return performed && !world.getBlockState(at).is(Blocks.FIRE)
                    && !world.getBlockState(at).is(Blocks.SOUL_FIRE)
                    ? Outcome.done() : Outcome.failed(DamageControlRefusal.NO_REACH);
            }

            @Override
            public boolean performed() {
                return performed;
            }

            @Override
            public void close() {
                closed = true;
            }
        });
    }
}
