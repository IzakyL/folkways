package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.terms.ItemDrops;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EatAction implements Node {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-living");

    private static final int CHEW_TICKS = 32;

    private final LivingPresence works;
    private final NodeSpec spec;

    EatAction(ServerLevel level, LivingPresence works, UUID eater, BlockPos at) {
        this.works = works;
        this.spec = NodeSpec.of(UUID.randomUUID(), LivingContent.ID,
                WorkSite.on(level, eater, at), Stances.WHEREVER, Workload.Once.of(CHEW_TICKS))
            .effort(WorkEffort.NONE)
            .doing(LivingContent.EATING)
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        Container pack = who.pack();
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(who.body());
            if (!works.wouldEat(who.resident().id(), stack, food)) {
                continue;
            }
            return consume(level, who, works, pack, slot);
        }
        return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
    }

    // Table displays and pocket rations use the same native food interaction and remainder handling.
    static Outcome consume(ServerLevel level, Worker who, LivingPresence works, Container pack, int slot) {
        ItemStack stack = pack.getItem(slot);
        if (PersonalItems.marked(stack)) return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
        FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(who.body());
        if (food == null || !works.wouldEat(who.resident().id(), food)) {
            return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
        }
        ItemStack eaten = stack.copyWithCount(1);
        ItemStack remainder = food.usingConvertsTo().map(ItemStack::copy)
            .orElseGet(stack::getCraftingRemainingItem);
        ItemStack bite = stack.split(1);
        pack.setChanged();
        ItemDrops.Captured<ItemStack> used;
        try {
            used = ItemDrops.capture(level, who.body().getBoundingBox().inflate(1),
                () -> bite.finishUsingItem(level, who.body()));
        } catch (ItemDrops.CaptureFailure broken) {
            LOGGER.warn("resident {}'s food interaction failed", who.resident().id(), broken.getCause());
            if (!bite.isEmpty()) {
                // The food never reached consumption; discard canceled byproducts and return that one bite.
                ItemStack back = Containers.insert(pack, bite);
                if (!back.isEmpty()) {
                    who.spill(back);
                }
                return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
            }
            // Eating already consumed the bite. Keep its goods and nourishment without replaying the use.
            used = new ItemDrops.Captured<>(ItemStack.EMPTY, broken.items());
        }
        List<ItemStack> left = new ArrayList<>(used.items());
        if (!used.result().isEmpty()) {
            left.add(used.result());
        } else if (!remainder.isEmpty() && left.stream().noneMatch(
                item -> ItemStack.isSameItemSameComponents(item, remainder))) {
            // Players receive food containers in Player.eat; a resident has its own pantry and belly.
            left.add(remainder);
        }
        works.ate(who.resident().id(), food, eaten, level.getGameTime());
        return new Outcome.Done(List.of(), left, Optional.empty());
    }
}
