package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.ItemDrops;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
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
    private final InventorySnapshot.Version observed;

    EatAction(ServerLevel level, LivingPresence works, UUID eater, BlockPos at) {
        this(level, works, eater, at, null);
    }

    EatAction(ServerLevel level, LivingPresence works, UUID eater, BlockPos at, InventorySnapshot.Version observed) {
        this.works = works;
        this.observed = observed;
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
        if (observed != null) { works.staleMeal(who.resident().id(), observed); }
        return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
    }

    // Table displays and pocket rations use the same native food interaction and remainder handling.
    static Outcome consume(ServerLevel level, Worker who, LivingPresence works, Container pack, int slot) {
        return consume(level, who.body(), who.resident().id(), who::spill, works, pack, slot);
    }

    static Outcome consume(ServerLevel level, net.minecraft.world.entity.Mob body, UUID resident,
                           java.util.function.Consumer<ItemStack> spill, LivingPresence works,
                           Container pack, int slot) {
        ItemStack stack = pack.getItem(slot);
        FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(body);
        if (food == null || !works.wouldEat(resident, food)) {
            return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
        }
        ItemStack eaten = stack.copyWithCount(1);
        ItemStack remainder = food.usingConvertsTo().map(ItemStack::copy)
            .orElseGet(stack::getCraftingRemainingItem);
        ItemStack bite = stack.split(1);
        pack.setChanged();
        ItemDrops.Captured<ItemStack> used;
        try {
            used = ItemDrops.capture(level, body.getBoundingBox().inflate(1),
                () -> bite.finishUsingItem(level, body));
        } catch (ItemDrops.CaptureFailure broken) {
            LOGGER.warn("resident {}'s food interaction failed", resident, broken.getCause());
            if (!bite.isEmpty()) {
                // The food never reached consumption; discard canceled byproducts and return that one bite.
                ItemStack back = Containers.insert(pack, bite);
                if (!back.isEmpty()) {
                    spill.accept(back);
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
        works.ate(resident, food, eaten, level.getGameTime());
        return new Outcome.Done(List.of(), left, Optional.empty());
    }
}
