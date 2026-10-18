package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Stall;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// What the player is told stands still: only what stays so, never a gap the next plan fills or a refusal met once.
@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StallsGameTests {

    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "stalls");

    private StallsGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StallsGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aPlannedStallIsToldOnlyOnceItHasStayed(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        Stall stall = new Stall(at, LaborRefusal.NO_SOURCE,
            Optional.of(ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"))), OWNER, Optional.empty());
        Stalls stalls = new Stalls();
        stalls.planned(List.of(stall), 0);
        helper.assertTrue(stalls.changed(50).isEmpty(), "a gap only just found is not told");
        helper.assertTrue(stalls.changed(100).equals(Optional.of(List.of(stall))), "a gap that stayed is told");
        helper.assertTrue(stalls.changed(150).isEmpty(), "what was told is not told again");
        stalls.planned(List.of(), 160);
        helper.assertTrue(stalls.changed(160).equals(Optional.of(List.of())), "a gap filled is no longer told");
        stalls.planned(List.of(stall), 200);
        helper.assertTrue(stalls.changed(250).isEmpty(), "a gap found again waits its while afresh");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aLastingRefusalIsToldOnceMetTwiceAndForgottenWhenWorkThereIsDone(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(at), Stances.WHEREVER,
            Workload.Once.of(1)).done();
        Stall stall = new Stall(at, LaborRefusal.NO_WAY_THERE, Optional.empty(), spec.doing(), Optional.empty());
        Stalls stalls = new Stalls();
        stalls.met(spec, LaborRefusal.GOODS_GONE, 0);
        stalls.met(spec, LaborRefusal.GOODS_GONE, 1);
        helper.assertTrue(stalls.changed(2).isEmpty(), "a refusal the next plan answers is never told");
        stalls.met(spec, LaborRefusal.NO_WAY_THERE, 10);
        helper.assertTrue(stalls.changed(11).isEmpty(), "a lasting refusal met once is not told");
        stalls.met(spec, LaborRefusal.NO_WAY_THERE, 20);
        helper.assertTrue(stalls.changed(21).equals(Optional.of(List.of(stall))), "met twice, it is told");
        stalls.done(spec);
        helper.assertTrue(stalls.changed(22).equals(Optional.of(List.of())), "work done there clears it");
        stalls.met(spec, LaborRefusal.NO_WAY_THERE, 30);
        stalls.met(spec, LaborRefusal.NO_WAY_THERE, 40);
        stalls.changed(41);
        helper.assertTrue(stalls.changed(40 + 1_200).equals(Optional.of(List.of())),
            "a refusal not met again for long enough is forgotten");
        helper.succeed();
    }
}
