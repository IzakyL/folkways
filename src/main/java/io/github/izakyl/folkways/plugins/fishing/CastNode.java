package io.github.izakyl.folkways.plugins.fishing;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
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
        List<ItemStack> caught = level.getServer().reloadableRegistries()
            .getLootTable(BuiltInLootTables.FISHING)
            .getRandomItems(params);
        Outcome done = new Outcome.Done(List.of(WorkNoise.stowed()), List.copyOf(caught),
            Optional.of(new Xp(FishingContent.trade(), 1)));
        made.record(spec, done, level.getGameTime());
        return done;
    }
}
