package io.github.izakyl.folkways.vanilla.farming;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.front.api.PastDay;
import io.github.izakyl.folkways.core.front.api.ZoneView;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class FacilityProtectionGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FacilityProtectionGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void farmDiagnosesAndSkipsProtectedGateCells(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos door = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos lever = door.east();
        level.setBlockAndUpdate(door.below(), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(lever.below(), Blocks.DIRT.defaultBlockState());
        var lower = Blocks.IRON_DOOR.defaultBlockState();
        level.setBlock(door, lower, Block.UPDATE_CLIENTS);
        level.setBlockAndUpdate(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));

        WorldPos anchor = WorldPos.of(level, door);
        var colony = Colonies.mint(level.getServer());
        Cleanup.on(helper, colony::raze);
        FacilityGateFixture.install(colony, anchor, lever, Direction.Axis.Z);
        Crop crop = Crop.of(level, door, ResourceLocation.withDefaultNamespace("wheat")).orElseThrow();
        var zone = Proxies.of(ZoneView.class, (proxy, method, args) -> switch (method.getName()) {
            case "cells" -> Set.of(door, lever.below());
            default -> throw new AssertionError(method.getName());
        });
        PastDay made = new PastDay();
        var field = new Field(crop, Tillage.Clearing.ALL, anchor, () -> { }, made);
        var tally = field.scan(level, zone, crop, level.getGameTime());
        helper.assertValueEqual(tally.protectedCells(), 2, "the farm diagnoses both marked columns");
        helper.assertValueEqual(tally.till(), 0, "the farm publishes no tilling for these columns");

        Job stale = new Job(anchor, List.of(door), Stances.WHEREVER, door);
        FieldChores chores = new FieldChores("test", chore -> new TillNode(chore, Tillage.Clearing.ALL),
            () -> { }, made);
        chores.settle(crop, List.of(stale), Set.of(door));
        var task = new TillNode(new FarmNode.Chore(UUID.randomUUID(), crop, stale, chores), Tillage.Clearing.ALL);
        var body = PersonBody.RESIDENT.get().create(level);
        try {
            var result = task.commit(level, TestWorker.of(Bodies.of(body).orElseThrow()));
            helper.assertTrue(result instanceof Outcome.Failed failed && failed.why() == LaborRefusal.PROTECTED_FACILITY,
                "a resident with a stale job receives the protected facility refusal: " + result);
            helper.assertTrue(level.getBlockState(door).is(Blocks.IRON_DOOR), "the door remains");
            helper.assertTrue(level.getBlockState(lever).is(Blocks.LEVER), "the lever remains");
        } finally {
            body.discard();
        }
        helper.succeed();
    }
}
