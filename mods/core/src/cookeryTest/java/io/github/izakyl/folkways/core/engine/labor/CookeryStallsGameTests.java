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

    // Only a lasting refusal met twice at one actual station is told, as that station and what was cooked there;
    // a passing one never is, nor does it count towards a lasting one.
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void kitchenRefusalsAreToldOnlyWhenLastingAndOnlyAtTheirActualStation(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)));
        WorldPos other = at.at(at.cell().east(4));
        for (CookeryRefusal refusal : List.of(CookeryRefusal.NO_HEAT_SOURCE, CookeryRefusal.NOT_A_STATION,
                CookeryRefusal.STATION_BUSY, CookeryRefusal.NOTHING_TO_WORK_WITH, CookeryRefusal.STATION_CHANGED,
                CookeryRefusal.RECIPE_CHANGED)) {
            boolean lasting = refusal == CookeryRefusal.NO_HEAT_SOURCE || refusal == CookeryRefusal.NOT_A_STATION;
            CookeryRefusal first = lasting ? refusal : CookeryRefusal.NO_HEAT_SOURCE;
            Stalls stalls = new Stalls();
            stalls.met(cooking(at), first, 10);
            stalls.met(cooking(other), first, 11);
            helper.assertTrue(stalls.changed(12).isEmpty(), "single failures at two actual stations are not conflated");
            NodeSpec replacement = cooking(at);
            if (!lasting) {
                for (int attempt = 0; attempt < 3; attempt++) stalls.met(cooking(at), refusal, 20 + attempt);
                helper.assertTrue(stalls.changed(30).isEmpty(),
                    "repeated " + refusal + " neither persists nor supplies a second missing-heat observation");
                helper.assertTrue(stalls.changed(500).isEmpty(),
                    "waiting does not promote a temporary kitchen refusal");
                continue;
            }
            stalls.met(replacement, refusal, 20);
            Stall expected = new Stall(at, refusal, Optional.empty(), replacement.doing(), replacement.about());
            helper.assertTrue(stalls.changed(21).equals(Optional.of(List.of(expected))),
                "a replacement plan reports repeated " + refusal + " at the actual station, retaining its cooking "
                    + "action and subject");
            helper.assertTrue(stalls.changed(22).isEmpty(), "an unchanged kitchen stall is not repeatedly published");
            stalls.done(cooking(other));
            helper.assertTrue(stalls.changed(23).isEmpty(),
                "work at another station cannot clear this station's warning");
            stalls.done(cooking(at));
            helper.assertTrue(stalls.changed(24).equals(Optional.of(List.of())),
                "successful replacement work at the same actual station clears the published refusal");
            stalls.met(cooking(at), refusal, 30);
            helper.assertTrue(stalls.changed(31).isEmpty(), "successful work also resets the old repetition count");
        }
        helper.succeed();
    }

    private static NodeSpec cooking(WorldPos at) {
        return NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at), Stances.WHEREVER,
            Workload.Once.of(1)).focus(at.cell().west(3)).doing(CookeryContent.COOKING, ModBlocks.STOCKPOT.get()).done();
    }
}
