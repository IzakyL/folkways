package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.internal.Refinements;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class FurnaceContinuationGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FurnaceContinuationGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void continuationRestoresOriginalFuelStagesAndBookingIds(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.FURNACE);
        var index = RecipeIndex.over(level, level.getRecipeManager());
        var originalBookings = new Bookings();
        var original = new CookHost(at, Blocks.FURNACE, RecipeType.SMELTING, Stances.at(at), originalBookings,
            block -> Set.of(at), () -> { }).frozen(index, level, Optional.of(ItemSpec.of(Items.COAL)));
        var want = Produce.of(original.id(), original.site(), ItemSpec.of(Items.IRON_INGOT), 1, Produce.UNBOUNDED);
        var prepared = Refinements.expand(original.options(want, Grown.of(want)).getFirst()).orElseThrow();
        helper.assertTrue(prepared.holds().getFirst().take().isEmpty(), "original furnace booking acquired");
        var saved = original.saveWork(prepared.replacement(), prepared.holds(), level.registryAccess()).orElseThrow();
        prepared.holds().forEach(hold -> hold.release(Ending.REVOKED));
        var restoredBookings = new Bookings();
        var restored = new CookHost(at, Blocks.FURNACE, RecipeType.SMELTING, Stances.at(at), restoredBookings,
            block -> Set.of(at), () -> { }).frozen(index, level, Optional.empty());
        var resumed = restored.resumeWork(saved, level.registryAccess()).orElseThrow();
        helper.assertTrue(resumed.replacement().nodes().stream().map(node -> node.id()).toList()
            .equals(prepared.replacement().nodes().stream().map(node -> node.id()).toList()),
            "stable stage and booking identifiers survive a new host");
        helper.assertTrue(resumed.replacement().nodes().stream().anyMatch(node -> node instanceof FuelNode),
            "the original fuel step survives even when no fuel is currently available");
        helper.assertTrue(resumed.holds().getFirst().take().isEmpty() && restoredBookings.taken(at),
            "the original reservation is restored without repricing consumed materials");
        resumed.holds().forEach(hold -> hold.release(Ending.DONE));
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 1200)
    public static void separateContinuationsReturnFuelAlreadyProvidedForTheBookedLoads(GameTestHelper helper) {
        var level = helper.getLevel();
        var cell = new BlockPos(1, 1, 1);
        var at = WorldPos.of(level, helper.absolutePos(cell));
        helper.setBlock(cell, Blocks.FURNACE);
        var index = RecipeIndex.over(level, level.getRecipeManager());
        var original = new CookHost(at, Blocks.FURNACE, RecipeType.SMELTING, Stances.at(at), new Bookings(),
            block -> Set.of(at), () -> { }).frozen(index, level, Optional.of(ItemSpec.of(Items.COAL)));
        List<CompoundTag> saved = new ArrayList<>();
        for (int count : List.of(1, 1, 2)) {
            var want = Produce.of(original.id(), original.site(), ItemSpec.of(Items.IRON_INGOT), count,
                Produce.UNBOUNDED);
            var prepared = Refinements.expand(original.options(want, Grown.of(want)).getFirst()).orElseThrow();
            helper.assertTrue(prepared.holds().getFirst().take().isEmpty(), "separate load booked");
            saved.add(original.saveWork(prepared.replacement(), prepared.holds(), level.registryAccess())
                .orElseThrow());
        }
        var restored = new CookHost(at, Blocks.FURNACE, RecipeType.SMELTING, Stances.at(at), new Bookings(),
            block -> Set.of(at), () -> { }).frozen(index, level, Optional.empty());
        var resumed = saved.stream().map(data -> restored.resumeWork(data, level.registryAccess()).orElseThrow())
            .toList();
        var actor = PersonBody.RESIDENT.get().create(level);
        var worker = TestWorker.of(actor);
        List<Node> fueling = new ArrayList<>();
        List<Node> taking = new ArrayList<>();
        for (var change : resumed) {
            helper.assertTrue(change.holds().getFirst().take().isEmpty(), "restored load booked");
            for (Node node : change.replacement().nodes()) {
                if (node instanceof LoadNode) {
                    int count = Math.toIntExact(node.spec().needs().getFirst().count());
                    helper.assertTrue(node.commit(level, worker.supplied(() -> List.of(new ItemStack(Items.RAW_IRON,
                        count)))) instanceof Outcome.Done, "separate raw iron load inserted");
                } else if (node instanceof FuelNode) {
                    fueling.add(node);
                } else {
                    taking.add(node);
                }
            }
        }
        helper.assertValueEqual(fueling.size(), 3, "all separately priced fuel stages survived continuation");
        worker.supplied(() -> List.of(new ItemStack(Items.COAL)));
        helper.assertTrue(fueling.getFirst().commit(level, worker) instanceof Outcome.Done done
            && done.unused().isEmpty(), "first fuel stage loads one coal");
        helper.assertTrue(fueling.get(1).commit(level, worker) instanceof Outcome.Done done
            && done.unused().size() == 1 && done.unused().getFirst().is(Items.COAL)
            && done.unused().getFirst().getCount() == 1, "coal already in the slot covers the other booked loads");
        Container furnace = (Container) level.getBlockEntity(helper.absolutePos(cell));
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            helper.assertTrue(furnace.getItem(Machines.FUEL_SLOT).isEmpty(), "the first coal is now burning");
            helper.assertTrue(fueling.get(2).commit(level, worker) instanceof Outcome.Done done
                && done.unused().size() == 1 && done.unused().getFirst().is(Items.COAL)
                && done.unused().getFirst().getCount() == 1, "remaining burn time covers the other booked loads");
        }).thenWaitUntil(() -> helper.assertTrue(furnace.getItem(Machines.INPUT_SLOT).isEmpty(),
            "all four raw iron cook with the one coal")).thenExecute(() -> {
                int made = 0;
                for (Node node : taking) {
                    Outcome result = node.commit(level, worker.supplied(List::of));
                    helper.assertTrue(result instanceof Outcome.Done, "each separate booking takes its output");
                    made += ((Outcome.Done) result).made().stream().mapToInt(ItemStack::getCount).sum();
                }
                helper.assertValueEqual(made, 4, "all four ingots delivered");
                helper.assertTrue(furnace.isEmpty(), "no extra coal or recipe material remains in the furnace");
                resumed.forEach(change -> change.holds().forEach(hold -> hold.release(Ending.DONE)));
                actor.discard();
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void separateLoadsKeepFuelNeededForTheWholeBookedQueue(GameTestHelper helper) {
        var level = helper.getLevel();
        var cell = new BlockPos(1, 1, 1);
        var at = WorldPos.of(level, helper.absolutePos(cell));
        helper.setBlock(cell, Blocks.FURNACE);
        var index = RecipeIndex.over(level, level.getRecipeManager());
        var workshop = new CookHost(at, Blocks.FURNACE, RecipeType.SMELTING, Stances.at(at), new Bookings(),
            block -> Set.of(at), () -> { }).frozen(index, level, Optional.of(ItemSpec.of(Items.COAL)));
        var want = Produce.of(workshop.id(), workshop.site(), ItemSpec.of(Items.IRON_INGOT), 5, Produce.UNBOUNDED);
        var first = Refinements.expand(workshop.options(want, Grown.of(want)).getFirst()).orElseThrow();
        helper.assertTrue(first.holds().getFirst().take().isEmpty(), "first five-item load booked");
        var second = Refinements.expand(workshop.options(want, Grown.of(want)).getFirst()).orElseThrow();
        helper.assertTrue(second.holds().getFirst().take().isEmpty(), "second five-item load booked");
        var actor = PersonBody.RESIDENT.get().create(level);
        try {
            var worker = TestWorker.of(actor).supplied(() -> List.of(new ItemStack(Items.COAL)));
            for (var change : List.of(first, second)) {
                var fuel = change.replacement().nodes().stream().filter(node -> node instanceof FuelNode)
                    .findFirst().orElseThrow();
                helper.assertTrue(fuel.commit(level, worker) instanceof Outcome.Done done && done.unused().isEmpty(),
                    "one coal covers either load but both booked loads need two");
            }
            Container furnace = (Container) level.getBlockEntity(helper.absolutePos(cell));
            helper.assertValueEqual(furnace.getItem(Machines.FUEL_SLOT).getCount(), 2,
                "fuel is retained for the entire queue, including input not yet loaded");
        } finally {
            first.holds().forEach(hold -> hold.release(Ending.DONE));
            second.holds().forEach(hold -> hold.release(Ending.DONE));
            actor.discard();
        }
        helper.succeed();
    }
}
