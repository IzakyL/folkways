package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.front.api.PastDay;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ItemAbilities;

/** Harvesting is shared by both products: one mature hive can only be taken once. */
final class ApiaryHarvestNode implements Node {
    private static final ToolUse SHEARS =
        new ToolUse(new ToolNeed.Ability(ItemAbilities.SHEARS_HARVEST), 1);
    private final WorldPos at;
    private final boolean bottles;
    private final PastDay made;
    private final Runnable changed;
    private final NodeSpec spec;
    private boolean claimed;
    private volatile boolean retired;

    ApiaryHarvestNode(WorldPos at, Stances stances, boolean bottles, PastDay made, Runnable changed) {
        this.at = at;
        this.bottles = bottles;
        this.made = made;
        this.changed = changed;
        var builder = NodeSpec.of(UUID.nameUUIDFromBytes(("apiary:" + at).getBytes(StandardCharsets.UTF_8)),
                PastureContent.ID, new WorkSite.AtBlock(at), stances,
                Workload.Once.of(40, PastureContent::haste))
            .vocation(PastureContent.trade()).gesture(WorkGesture.SWING).focus(at.cell())
            .doing(PastureContent.HARVESTING_HONEY)
            .gives(new Amount(ItemSpec.of(BuiltInRegistries.ITEM.getKey(bottles ? Items.HONEY_BOTTLE : Items.HONEYCOMB)), bottles ? 1 : 3));
        if (bottles) builder.needs(new Need(ItemSpec.of(BuiltInRegistries.ITEM.getKey(Items.GLASS_BOTTLE)), 1));
        else builder.tools(SHEARS);
        spec = builder.done();
    }

    void retire() { retired = true; }

    synchronized boolean claimed() { return claimed; }

    @Override public NodeSpec spec() { return spec; }

    @Override public synchronized Optional<RefusalKind> planned(ServerLevel level) {
        if (retired) return Optional.of(PastureRefusal.HIVE_GONE);
        if (claimed) return Optional.of(PastureRefusal.HIVE_TAKEN);
        claimed = true;
        return Optional.empty();
    }

    @Override public synchronized void ended(ServerLevel level, Ending how) {
        claimed = false;
        changed.run();
    }

    @Override public boolean ready(ServerLevel level) {
        return refusal(level).isEmpty();
    }

    @Override public Optional<RefusalKind> refusal(ServerLevel level) {
        if (retired || !at.in(level)) return Optional.of(PastureRefusal.HIVE_GONE);
        BlockPos pos = at.block(level);
        if (!level.isLoaded(pos)) return Optional.of(PastureRefusal.HIVE_GONE);
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BeehiveBlock)) return Optional.of(PastureRefusal.HIVE_GONE);
        if (state.getValue(BeehiveBlock.HONEY_LEVEL) != 5) return Optional.of(PastureRefusal.HIVE_NOT_READY);
        if (!CampfireBlock.isSmokeyPos(level, pos)) return Optional.of(PastureRefusal.HIVE_NOT_SMOKED);
        return Optional.empty();
    }

    @Override public Outcome commit(ServerLevel level, Worker who) {
        // The player, smoke or another harvest may have changed since the plan was made.
        Optional<RefusalKind> refusal = refusal(level);
        if (refusal.isPresent()) return Outcome.failed(refusal.get());
        BlockPos pos = at.block(level);
        BlockState state = level.getBlockState(pos);
        ((BeehiveBlock) state.getBlock()).resetHoneyLevel(level, state, pos);
        Outcome done = new Outcome.Done(List.of(),
            List.of(new ItemStack(bottles ? Items.HONEY_BOTTLE : Items.HONEYCOMB, bottles ? 1 : 3)),
            Optional.of(new Xp(PastureContent.trade(), 1)));
        made.record(spec, done, level.getGameTime());
        if (bottles) made.spent(spec, who.supplied(), level.getGameTime());
        return done;
    }
}
