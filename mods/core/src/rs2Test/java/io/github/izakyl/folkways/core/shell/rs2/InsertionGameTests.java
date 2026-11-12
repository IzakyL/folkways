package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.autocrafting.PatternRepositoryImpl;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.calculation.CraftingCalculatorImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.autocrafting.task.StepBehavior;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskImpl;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskListener;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskPlanCraftingCalculatorListener;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class InsertionGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(InsertionGameTests.class);
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core")
    public static void intermediateDepositIsReceivedByNativeTaskWithoutStockGrowth(GameTestHelper helper) {
        var root = new RootStorageImpl();
        root.addSource(new StorageImpl());
        root.insert(new ItemResource(Items.RAW_IRON), 1, Action.EXECUTE, Actor.EMPTY);
        var repository = new PatternRepositoryImpl();
        repository.add(pattern(Items.RAW_IRON, Items.IRON_INGOT), 0);
        repository.add(pattern(Items.IRON_INGOT, Items.DIAMOND), 0);
        var plan = TaskPlanCraftingCalculatorListener.calculatePlan(new CraftingCalculatorImpl(repository, root),
            new ItemResource(Items.DIAMOND), 1, CancellationToken.NONE).orElseThrow();
        var task = new TaskImpl(plan, Actor.EMPTY, false);
        root.addListener(task);
        for (int step = 0; step < 4; step++) {
            task.step(root, layout -> List.of((pattern, inputs, action) -> ExternalPatternSink.Result.ACCEPTED),
                StepBehavior.DEFAULT, TaskListener.EMPTY);
        }
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var result = Transfers.put(helper.getLevel(), pos, root, Actor.EMPTY,
            List.of(new ItemStack(Items.IRON_INGOT)), new Room.Pool(100, 100), false);
        helper.assertTrue(result.done(), "native task interception conserves the deposited intermediate");
        helper.assertTrue(root.get(new ItemResource(Items.IRON_INGOT)) == 0
            && task.createSnapshot().internalStorage().get(new ItemResource(Items.IRON_INGOT)) == 1
            && StorePorts.condemned(WorldPos.of(helper.getLevel(), pos)).isEmpty(),
            "the task owns the one ingot and the network access remains usable");
        helper.succeed();
    }

    private static Pattern pattern(Item input, Item output) {
        return new Pattern(UUID.randomUUID(), PatternLayout.external(
            List.of(new Ingredient(1, List.of(new ItemResource(input)))),
            List.of(new ResourceAmount(new ItemResource(output), 1))));
    }
}
