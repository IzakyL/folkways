package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlRefusal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

final class RefillNode implements Node {
    private final NodeSpec spec;
    private final UUID worker;
    private final WorldPos source;
    private final BooleanSupplier enabled;
    private final boolean waterSource;

    RefillNode(UUID worker, WorldPos source, Stances stances, BooleanSupplier enabled) {
        this(worker, source, stances, enabled, false);
    }

    RefillNode(UUID worker, WorldPos source, Stances stances, BooleanSupplier enabled, boolean waterSource) {
        this.worker = worker;
        this.source = source;
        this.waterSource = waterSource;
        this.enabled = enabled;
        spec = NodeSpec.of(UUID.randomUUID(), FolkwaysFirefightingMod.OWNER, new WorkSite.AtBlock(source),
            stances, Workload.Once.of(20))
            .needs(waterSource ? List.of(new Need(
                ItemSpec.of(ResourceLocation.withDefaultNamespace("bucket")), 1)) : List.of())
            .leaves(waterSource ? List.of(new Amount(
                ItemSpec.of(ResourceLocation.withDefaultNamespace("bucket")), 1)) : List.of())
            .vocation(DamageControlContent.trade())
            .doing(DamageControlContent.REFILLING).done();
    }

    public NodeSpec spec() { return spec; }
    public Optional<UUID> worker() { return Optional.of(worker); }
    public boolean ready(ServerLevel level) { return enabled.getAsBoolean() && source.in(level) && level.isLoaded(source.block(level)); }

    public Outcome commit(ServerLevel level, Worker who) {
        if (!ready(level) || !Reach.standingIn(who.body(), spec.stances())) {
            return Outcome.failed(DamageControlRefusal.NOT_LOADED);
        }
        ItemStack stack = ExtinguisherAdapter.find(who.pack());
        IFluidHandler target = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (target == null) {
            return Outcome.failed(DamageControlRefusal.NO_WATER);
        }
        BlockPos at = source.block(level);
        if (waterSource) {
            if (!level.getBlockState(at).is(Blocks.WATER)
                    || !level.getFluidState(at).isSource() || who.supplied().isEmpty()
                    || target.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.SIMULATE) != 1000) {
                return Outcome.failed(DamageControlRefusal.NO_WATER);
            }
            var picked = FluidUtil.tryPickUpFluid(who.supplied().getFirst(),
                null, level, at, Direction.UP);
            if (!picked.isSuccess()) {
                return Outcome.failed(DamageControlRefusal.NO_WATER);
            }
            var bucket = picked.getResult().getCapability(Capabilities.FluidHandler.ITEM);
            if (bucket == null) {
                return new Outcome.Done(List.of(), List.of(picked.getResult()), Optional.empty());
            }
            transfer(bucket, target);
            who.pack().setChanged();
            return new Outcome.Done(List.of(), List.of(bucket.getContainer()), Optional.empty());
        }
        IFluidHandler block = level.getCapability(Capabilities.FluidHandler.BLOCK, at, null);
        if (block != null && transfer(block, target) > 0) {
            who.pack().setChanged();
            return Outcome.done();
        }
        for (Worker.Store store : who.within()) {
            if (!store.at().equals(source)) {
                continue;
            }
            Container inventory = store.contents();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack item = inventory.getItem(slot);
                var fluid = item.getCapability(Capabilities.FluidHandler.ITEM);
                if (fluid != null && item.getCount() == 1 && transfer(fluid, target) > 0) {
                    inventory.setItem(slot, fluid.getContainer());
                    inventory.setChanged();
                    who.pack().setChanged();
                    return Outcome.done();
                }
            }
        }
        return Outcome.failed(DamageControlRefusal.NO_WATER);
    }

    static boolean canSupply(ServerLevel level, WorldPos source, @Nullable Container store) {
        var block = level.getCapability(Capabilities.FluidHandler.BLOCK, source.block(level), null);
        if (block != null && !block.drain(new FluidStack(Fluids.WATER, 1), FluidAction.SIMULATE).isEmpty()) {
            return true;
        }
        if (store != null) {
            for (int slot = 0; slot < store.getContainerSize(); slot++) {
                var item = store.getItem(slot).getCapability(Capabilities.FluidHandler.ITEM);
                if (item != null && !item.drain(new FluidStack(Fluids.WATER, 1000), FluidAction.SIMULATE).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    static int transfer(IFluidHandler source, IFluidHandler target) {
        FluidStack offered = source.drain(new FluidStack(Fluids.WATER, FireExtinguisherItem.CAPACITY), FluidAction.SIMULATE);
        int room = target.fill(offered, FluidAction.SIMULATE);
        if (room <= 0) {
            return 0;
        }
        // Some containers (water buckets) can only drain a whole bucket.
        FluidStack proposed = source.drain(new FluidStack(Fluids.WATER, room), FluidAction.SIMULATE);
        if (proposed.isEmpty() || target.fill(proposed, FluidAction.SIMULATE) != proposed.getAmount()) {
            return 0;
        }
        FluidStack drawn = source.drain(proposed, FluidAction.EXECUTE);
        return target.fill(drawn, FluidAction.EXECUTE);
    }
}
