package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItemHandler;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.front.engine.colony.ZoneActions;
import io.github.izakyl.folkways.front.engine.colony.ZoneDrafts;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysFirefightingMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecoveryNavigationGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RecoveryNavigationGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 2400)
    public static void aResponderWalksThroughTheCourtyardOpeningToSuppressAndRepair(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 3, 2));
        for (BlockPos cell : BlockPos.betweenClosed(origin.offset(0, -1, 0), origin.offset(18, 4, 18))) {
            boolean floor = cell.getY() < origin.getY();
            int x = cell.getX() - origin.getX();
            int z = cell.getZ() - origin.getZ();
            boolean wall = (x == 3 || x == 17) && z >= 2 && z <= 14 || z == 2 && x >= 3 && x <= 17;
            level.setBlock(cell, (floor || wall ? Blocks.STONE_BRICKS : Blocks.AIR).defaultBlockState(), 2);
        }
        List<BlockPos> fires = List.of(origin.offset(6, 0, 5), origin.offset(10, 0, 5),
            origin.offset(6, 0, 9), origin.offset(10, 0, 9));
        for (BlockPos fire : fires) {
            level.setBlock(fire.below(), Blocks.NETHERRACK.defaultBlockState(), 2);
            level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
        }
        var colony = Colonies.mint(level.getServer());
        var body = PersonBody.RESIDENT.get().create(level);
        body.setPos(Vec3.atBottomCenterOf(origin.offset(1, 0, 7)));
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        ItemStack bottle = new ItemStack(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get());
        new FireExtinguisherItemHandler(CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, bottle)
            .setStoredFluid(new FluidStack(Fluids.WATER, 1000));
        body.pack().setItem(0, bottle);
        body.pack().setItem(1, new ItemStack(Items.STONE_BRICKS, 64));
        var zone = ZoneDrafts.zone(level, colony, DamageControlContent.ID, origin, origin.offset(18, 4, 18),
            ColonySettings.empty().with("repair", new ColonySettings.Value.Flag(true))).orElseThrow();
        var recorded = ZoneActions.act(level, colony, zone.id(), "record").orElseThrow();
        helper.assertTrue(recorded.refusal().isEmpty(), "the intact courtyard baseline is recorded normally");
        BlockPos missing = origin.offset(17, 1, 6);
        level.removeBlock(missing, false);
        var simulation = new LaborFixture(colony, level);
        boolean[] entered = {false};
        helper.onEachTick(() -> {
            Vec3 before = body.position();
            simulation.tick();
            helper.assertTrue(before.distanceToSqr(body.position()) < 1, "the response never relocates the body");
            if (body.getX() > origin.getX() + 4 && body.getZ() < origin.getZ() + 14) {
                entered[0] = true;
            }
        });
        helper.startSequence().thenWaitUntil(() -> {
            var path = body.getNavigation().getPath();
            helper.assertTrue(fires.stream().allMatch(at -> level.getBlockState(at).isAir())
                    && level.getBlockState(missing).is(Blocks.STONE_BRICKS),
                "ordinary work suppresses and repairs the courtyard: at=" + body.position() + " doing=" + body.doing()
                    + " refusal=" + body.balk() + " target=" + body.getNavigation().getTargetPos()
                    + " path=" + (path == null ? null : path.canReach() + "/" + path.getNodeCount()));
        }).thenExecute(() -> {
            helper.assertTrue(entered[0], "the resident enters through the open south side");
            helper.assertTrue(FireExtinguisherItem.getFluid(bottle).getAmount() < 1000,
                "native suppression consumes finite physical water");
            helper.assertTrue(body.pack().countItem(Items.STONE_BRICKS) < 64,
                "ordinary repair consumes the carried physical material");
            simulation.close();
            body.discard();
            colony.raze();
        }).thenSucceed();
    }
}
