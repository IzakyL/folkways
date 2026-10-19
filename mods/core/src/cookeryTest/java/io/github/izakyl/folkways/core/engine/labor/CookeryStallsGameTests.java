package io.github.izakyl.folkways.core.engine.labor;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Stall;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryStallsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookeryStallsGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void repeatedMissingHeatIsReportedAtItsActualStationAcrossReplacementPlans(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)));
        WorldPos other = at.at(at.cell().east(4));
        NodeSpec first = cooking(at);
        NodeSpec replacement = cooking(at);
        Stalls stalls = new Stalls();
        stalls.met(first, CookeryRefusal.NO_HEAT_SOURCE, 10);
        stalls.met(cooking(other), CookeryRefusal.NO_HEAT_SOURCE, 11);
        helper.assertTrue(stalls.changed(12).isEmpty(), "single failures at two actual stations are not conflated");
        stalls.met(replacement, CookeryRefusal.NO_HEAT_SOURCE, 20);
        Stall expected = new Stall(at, CookeryRefusal.NO_HEAT_SOURCE, Optional.empty(),
            replacement.doing(), replacement.about());
        helper.assertTrue(stalls.changed(21).equals(Optional.of(List.of(expected))),
            "two replacement plans report the actual unheated station, retaining its cooking action and subject");
        helper.assertTrue(stalls.changed(22).isEmpty(), "an unchanged heat-source stall is not repeatedly published");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void temporaryKitchenRefusalsNeverBecomeLastingStationReports(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)));
        for (CookeryRefusal temporary : List.of(CookeryRefusal.STATION_BUSY, CookeryRefusal.NOTHING_TO_WORK_WITH,
                CookeryRefusal.STATION_CHANGED, CookeryRefusal.RECIPE_CHANGED)) {
            Stalls stalls = new Stalls();
            stalls.met(cooking(at), CookeryRefusal.NO_HEAT_SOURCE, 10);
            for (int attempt = 0; attempt < 3; attempt++) stalls.met(cooking(at), temporary, 20 + attempt);
            helper.assertTrue(stalls.changed(30).isEmpty(),
                "repeated " + temporary + " neither persists nor supplies a second missing-heat observation");
            helper.assertTrue(stalls.changed(500).isEmpty(), "waiting does not promote a temporary kitchen refusal");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void successfulWorkClearsLastingKitchenRefusalsOnlyAtItsActualStation(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)));
        for (CookeryRefusal lasting : List.of(CookeryRefusal.NO_HEAT_SOURCE, CookeryRefusal.NOT_A_STATION)) {
            Stalls stalls = new Stalls();
            NodeSpec work = cooking(at);
            stalls.met(work, lasting, 10);
            stalls.met(cooking(at), lasting, 20);
            Stall expected = new Stall(at, lasting, Optional.empty(), work.doing(), work.about());
            helper.assertTrue(stalls.changed(21).equals(Optional.of(List.of(expected))),
                "the real stalls mechanism publishes repeated " + lasting);
            stalls.done(cooking(at.at(at.cell().east(4))));
            helper.assertTrue(stalls.changed(22).isEmpty(), "work at another station cannot clear this station's warning");
            stalls.done(cooking(at));
            helper.assertTrue(stalls.changed(23).equals(Optional.of(List.of())),
                "successful replacement work at the same actual station clears the published refusal");
            stalls.met(cooking(at), lasting, 30);
            helper.assertTrue(stalls.changed(31).isEmpty(), "successful work also resets the old repetition count");
        }
        helper.succeed();
    }

    private static NodeSpec cooking(WorldPos at) {
        return NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at), Stances.WHEREVER,
            Workload.Once.of(1)).focus(at.cell().west(3)).doing(CookeryContent.COOKING, ModBlocks.STOCKPOT.get()).done();
    }
}
