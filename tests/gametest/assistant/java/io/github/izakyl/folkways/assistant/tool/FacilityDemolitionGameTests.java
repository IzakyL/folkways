package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.build.BlueprintBuildOrder;
import io.github.izakyl.folkways.vanilla.build.BuildContent;
import io.github.izakyl.folkways.vanilla.build.FiledOrderRig;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import io.github.izakyl.folkways.vanilla.groundwork.GroundworkContent;
import io.github.izakyl.folkways.vanilla.groundwork.GroundworkFixture;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = AssistantMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class FacilityDemolitionGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FacilityDemolitionGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void clearPreviewNamesGateAndConfirmedWorkRemovesItsLever(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos door = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos lever = door.east();
        for (BlockPos floor : BlockPos.betweenClosed(door.offset(-2, -1, -2), door.offset(3, -1, 3))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        BlockState lower = Blocks.IRON_DOOR.defaultBlockState();
        level.setBlock(door, lower, Block.UPDATE_CLIENTS);
        level.setBlockAndUpdate(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        ServerPlayer player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "clear-test"),
            ClientInformation.createDefault());
        Colony colony = Charters.found(player, "Clear test");
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        BlockPos chest = door.south(3);
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        colony.hold(BuildContent.ID, new Claimed.Block(WorldPos.of(level, chest))).orElseThrow();
        Worker worker = worker(level, lever.south());
        Cleanup.on(helper, () -> {
            worker.body().discard();
            colony.raze();
        });
        FacilityGateFixture.install(colony, WorldPos.of(level, door), lever, Direction.Axis.Z);
        JsonObject cell = new JsonObject();
        cell.addProperty("x", lever.getX());
        cell.addProperty("y", lever.getY());
        cell.addProperty("z", lever.getZ());
        JsonObject args = new JsonObject();
        args.addProperty("kind", GroundworkContent.ZONE.toString());
        args.addProperty("dimension", level.dimension().location().toString());
        args.add("from", cell);
        args.add("to", cell);
        var actions = ActionRegistry.ACTIONS;
        var preview = actions.preview(player, "create_zone", args).data();
        helper.assertTrue(preview.has("affected_facilities"), "preview names affected facilities: " + preview);
        var facilities = preview.getAsJsonArray("affected_facilities");
        helper.assertValueEqual(facilities.size(), 1, "the lever belongs to one gate");
        helper.assertValueEqual(facilities.get(0).getAsJsonObject().get("block").getAsString(),
            "minecraft:iron_door", "preview points to the gate's door");
        helper.assertTrue(level.getBlockState(lever).is(Blocks.LEVER), "preview leaves the gate intact");
        var result = actions.apply(player, "create_zone", args, preview.get("revision").getAsString()).join();
        helper.assertTrue(result.data().has("changed"), "the preview can be confirmed: " + result.data());
        GroundworkFixture.refresh(colony, level.getServer());
        var saved = colony.kept(BuildContent.ID).getCompound("book").getList("buildOrders", Tag.TAG_COMPOUND);
        helper.assertValueEqual(saved.size(), 1, "confirmed Clear files one demolition order");
        var order = BlueprintBuildOrder.load(Reader.of(saved.getCompound(0)), level.registryAccess()).orElseThrow();
        helper.assertTrue(level.getBlockState(lever).is(Blocks.LEVER), "the filed order still has a lever to remove");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(FiledOrderRig.ready(colony, level, order.id()),
            "the confirmed demolition is still opening")).thenExecute(() -> {
            FiledOrderRig.work(helper, colony, order.id(), worker);
            helper.assertTrue(level.getBlockState(lever).isAir(), "confirmed Clear removes the protected lever");
            helper.assertTrue(level.getBlockState(door).is(Blocks.IRON_DOOR), "the unselected door remains standing");
        }).thenSucceed();
    }

    private static Worker worker(ServerLevel level, BlockPos feet) {
        var body = EntityType.PIG.create(level);
        body.setPos(Vec3.atBottomCenterOf(feet));
        var pack = new SimpleContainer(new ItemStack(Items.DIRT, 16));
        var resident = new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig"));
        return TestWorker.of(body, resident, pack).spill(stack -> { });
    }
}
