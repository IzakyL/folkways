package io.github.izakyl.folkways.plugins.fishing;

import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkNoise;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.Xp;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.front.api.PastDay;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.common.ItemAbilities;

final class CastNode implements Node {

    private static final ToolNeed ROD = new ToolNeed.Ability(ItemAbilities.FISHING_ROD_CAST);

    // A fish, or a boot, or a book: what comes up is known only once it does.
    private static final List<Amount> CATCH = Leaves.room(5);

    private final NodeSpec spec;
    private final WorldPos water;
    private final Runnable ended;
    private final PastDay made;

    CastNode(UUID id, Spot spot, Runnable ended, PastDay made) {
        this.water = spot.water();
        this.ended = ended;
        this.made = made;
        this.spec = NodeSpec.of(id, FishingContent.ID, new WorkSite.AtBlock(water), spot.stances(),
                Workload.Once.of(spot.biteTicks(), FishingContent::haste))
            .tools(ToolUse.of(ROD))
            .leaves(CATCH)
            .vocation(FishingContent.trade())
            .gesture(WorkGesture.CAST)
            .doing(FishingContent.FISHING)
            .focus(water.cell())
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        ended.run();
    }

    @Override
    public boolean ready(ServerLevel level) {
        return water.in(level) && level.isLoaded(water.block(level));
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (!level.getFluidState(water.block(level)).is(FluidTags.WATER)) {
            return Outcome.failed(FishingRefusal.NO_WATER);
        }
        ItemStack rod = who.held(ROD);
        LivingEntity body = who.body();
        LootParams params = new LootParams.Builder(level)
            .withParameter(LootContextParams.ORIGIN, WorldSpaces.world(level, water).orElseThrow())
            .withParameter(LootContextParams.TOOL, rod)
            .withParameter(LootContextParams.THIS_ENTITY, body)
            .withLuck(0.0F)
            .create(LootContextParamSets.FISHING);
        List<ItemStack> caught = new java.util.ArrayList<>(level.getServer().reloadableRegistries()
            .getLootTable(BuiltInLootTables.FISHING).getRandomItems(params));
        for (int i = 0; i < caught.size(); i++) {
            ItemStack stack = WaterLoot.uncommon(level, params, caught.get(i),
                who.effects().value(FishingContent.RARE_FISH));
            caught.set(i, stack);
            if (WaterLoot.fish(stack)
                    && level.random.nextDouble() < who.effects().value(FishingContent.EXTRA_FISH)) {
                stack.grow((int) who.effects().value(FishingContent.SCHOOL_SIZE));
            }
        }
        if (level.random.nextDouble() < who.effects().value(FishingContent.TREASURE_CHANCE)) {
            caught.addAll(WaterLoot.discoveries(level, params));
        }
        Outcome done = new Outcome.Done(List.of(WorkNoise.stowed()), List.copyOf(caught),
            Optional.of(new Xp(FishingContent.trade(), 1)));
        made.record(spec, done, level.getGameTime());
        return done;
    }
}
