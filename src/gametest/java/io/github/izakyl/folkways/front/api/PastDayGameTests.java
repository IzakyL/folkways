package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PastDayGameTests {
    private static final ResourceLocation WHEAT = ResourceLocation.withDefaultNamespace("wheat");
    private static final ResourceLocation SEEDS = ResourceLocation.withDefaultNamespace("wheat_seeds");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PastDayGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void outputIsSummedWhereItWasMadeAndForgottenAfterADay(GameTestHelper helper) {
        PastDay made = new PastDay();
        WorldPos field = WorldPos.of(Level.OVERWORLD, new BlockPos(10, 64, 10));
        WorldPos elsewhere = WorldPos.of(Level.OVERWORLD, new BlockPos(90, 64, 90));
        helper.assertTrue(!made.changed(), "nothing is counted yet");
        made.record(field, List.of(new ItemStack(Items.WHEAT, 3), new ItemStack(Items.WHEAT_SEEDS, 5)), 1_000L);
        made.record(field, List.of(new ItemStack(Items.WHEAT, 4)), 2_000L);
        made.record(elsewhere, List.of(new ItemStack(Items.WHEAT, 50)), 2_000L);
        helper.assertTrue(made.changed() && !made.changed(), "counting must be reported once, then cleared");

        Map<ResourceLocation, Long> here = made.within(field::equals, 0L);
        helper.assertTrue(here.equals(Map.of(WHEAT, 7L, SEEDS, 5L)),
            "the field must count only what was made on it, got " + here);
        helper.assertTrue(here.keySet().iterator().next().equals(WHEAT),
            "the largest output must come first, got " + here);

        made.record(elsewhere, List.of(new ItemStack(Items.CARROT)), 2_000L + PastDay.TICKS + 2_400L);
        helper.assertTrue(made.within(field::equals, 0L).isEmpty(),
            "output older than a day must be forgotten once more is recorded");

        PastDay reloaded = new PastDay();
        reloaded.load(Reader.of(made.save()));
        helper.assertTrue(reloaded.within(pos -> true, 0L).equals(made.within(pos -> true, 0L)),
            "saved output must load back unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void whatWasSpentIsTakenOffWhatWasMade(GameTestHelper helper) {
        PastDay made = new PastDay();
        WorldPos field = WorldPos.of(Level.OVERWORLD, new BlockPos(10, 64, 10));
        made.spent(field, List.of(new ItemStack(Items.WHEAT_SEEDS)), 1_000L);
        helper.assertTrue(made.within(field::equals, 0L).isEmpty(),
            "a sowing with nothing yet reaped must show no output, got " + made.within(field::equals, 0L));

        made.record(field, List.of(new ItemStack(Items.WHEAT, 1), new ItemStack(Items.WHEAT_SEEDS, 3)), 2_000L);
        Map<ResourceLocation, Long> here = made.within(field::equals, 0L);
        helper.assertTrue(here.equals(Map.of(WHEAT, 1L, SEEDS, 2L)),
            "the seed sown must be taken off the seeds reaped, got " + here);
        helper.succeed();
    }
}
