package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProtectionGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ProtectionGameTests.class);
        if (ModList.get().isLoaded("ftbchunks")) {
            try {
                event.register(Class.forName("io.github.izakyl.folkways.core.engine.plan.FtbProtectionGameTests"));
            } catch (ClassNotFoundException missing) {
                throw new IllegalStateException("FTB compatibility tests are missing", missing);
            }
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void automationWithoutAnOwnerCannotBypassInstalledProtection(GameTestHelper helper) {
        var fixture = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(fixture.source.cell(), Blocks.BARREL.defaultBlockState());
        var source = Containers.at(level, fixture.source.cell()).orElseThrow();
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 4));
        var transfer = new TransferNode(UUID.randomUUID(),
            TransferNode.Endpoint.store(new Stash(fixture.source)), TransferNode.Endpoint.pack(),
            new Manifest(List.of(new ItemStack(Items.IRON_INGOT, 4)), List.of()),
            Stances.at(fixture.source), new WorkSite.AtBlock(fixture.source));
        Outcome result = Commit.run(level, transfer, fixture.worker);
        if (ModList.get().isLoaded("ftbchunks")) {
            helper.assertTrue(result instanceof Outcome.Failed failed && failed.why() == LaborRefusal.PROTECTED,
                "an unowned automation actor fails closed with FTB Chunks installed");
            helper.assertTrue(source.getItem(0).getCount() == 4 && fixture.worker.pack().isEmpty(),
                "permission refusal happens before extracting any items");
        } else {
            helper.assertTrue(result instanceof Outcome.Done && source.isEmpty()
                    && fixture.worker.pack().getItem(0).getCount() == 4,
                "the optional bridge leaves ordinary hauling functional without FTB Chunks");
        }
        helper.succeed();
    }
}
