package io.github.izakyl.folkways.plugins.person.living;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Urge;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
public final class TavernDiningSupplyGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) {
        if (ModList.get().isLoaded("kaleidoscope_tavern")) { event.register(Native.class); }
    }

    @PrefixGameTestTemplate(false)
    public static final class Native {
        private static final ResourceLocation WINE = ResourceLocation.fromNamespaceAndPath("kaleidoscope_tavern", "wine");
        private static final ResourceLocation COUNTER = ResourceLocation.fromNamespaceAndPath("kaleidoscope_tavern", "bar_counter");

        @GameTest(template = "empty", templateNamespace = "folkways")
        public static void nativeCountersStoreRestockAndServeIndependentSeatedDrinks(GameTestHelper helper) {
            try (var f = new DiningGameTests.Fixture(helper, ModBlocks.TABLE_OAK.get().defaultBlockState())) {
                f.zone(Items.BREAD);
                f.drinks(BuiltInRegistries.ITEM.get(WINE));
                BlockPos counter = f.chair.west(), other = f.at.east(2);
                f.level.setBlockAndUpdate(counter, BuiltInRegistries.BLOCK.get(COUNTER).defaultBlockState());
                f.level.setBlockAndUpdate(other, BuiltInRegistries.BLOCK.get(COUNTER).defaultBlockState());
                var settings = f.front.zone(f.zone).orElseThrow().settings();
                f.front.erase(f.zone);
                f.zone = f.front.addZone(f.level, LivingContent.DINING, Set.of(f.at, f.chair, counter), settings).orElseThrow().id();
                f.front.addZone(f.level, LivingContent.DINING, Set.of(other), settings).orElseThrow();
                f.dining.refresh(f.level.getServer());
                helper.assertTrue(f.goals().stream().filter(node -> node.spec().focus().filter(f.at::equals).isPresent())
                    .noneMatch(node -> node.spec().needs().getFirst().spec().item().filter(WINE::equals).isPresent()),
                    "native tables keep every slot for food");
                var direct = new Stash(WorldPos.of(f.level, f.at.north(2)));
                Node refill = f.goals().stream().filter(node -> node.spec().focus().filter(counter::equals).isPresent()).findFirst().orElseThrow();
                helper.assertTrue(refill.acceptsInput(direct) && refill.permitsProduction(refill.spec().needs().getFirst().spec()),
                    "an empty native counter takes wine from any stock or new production");
                helper.assertTrue(!refill.acceptsInput(new Stash(WorldPos.of(f.level, counter))) && !refill.acceptsInput(new Stash(WorldPos.of(f.level, other))),
                    "counter refills never reshuffle existing counter bottles");
                var foreign = Containers.at(f.level, other).orElseThrow();
                foreign.setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(WINE))); foreign.setChanged();
                f.display.setItem(0, new ItemStack(Items.BREAD)); f.display.setChanged();
                Node meal = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
                helper.assertValueEqual(meal.spec().doing(), LivingContent.DINING_EATING, "another dining area's counter is never raided for this meal");
                meal.ended(f.level, Ending.DROPPED);
                var own = Containers.at(f.level, counter).orElseThrow();
                own.setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(WINE))); own.setChanged();
                Node food = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
                helper.assertValueEqual(food.spec().doing(), LivingContent.DINING_EATING, "a meal no longer fetches wine to the food table");
                food.ended(f.level, Ending.DROPPED);
                f.display.clearContent();
                Node drink = f.dining.drinking(f.worker, f.colony.view(f.level)).orElseThrow().take().get();
                helper.assertTrue(drink.spec().focus().filter(counter::equals).isPresent(), "a bar visit focuses the native counter which holds its actual bottle stock");
                helper.assertTrue(f.dining.drinking(f.worker, f.colony.view(f.level)).orElseThrow().take().get() == drink,
                    "the active drink retains its reserved chair");
                f.dining.refresh(f.level.getServer());
                helper.assertTrue(drink.refusal(f.level).isEmpty(), "a refresh preserves an active bar visit");
                drink.ended(f.level, Ending.DROPPED);
                helper.assertValueEqual(own.getItem(0).getCount(), 1, "an interrupted visit never consumes the bottle");
                Node retry = f.dining.drinking(f.worker, f.colony.view(f.level)).orElseThrow().take().get();
                helper.assertTrue(retry != drink, "interruption releases the seat and serving reservation");
                f.level.removeBlock(counter, false);
                helper.assertTrue(retry.refusal(f.level).isPresent(), "removing the service counter cancels a reserved drink without consuming stock");
                retry.ended(f.level, Ending.DROPPED);
                f.worker.body().getPersistentData().putLong("folkways:last_table_drink", f.level.getGameTime());
                helper.assertTrue(f.dining.drinking(f.worker, f.colony.view(f.level)).isEmpty(), "drinking has a cooldown independent of hunger");
            }
            helper.succeed();
        }
    }
}
