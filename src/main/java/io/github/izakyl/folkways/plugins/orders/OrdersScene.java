package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class OrdersScene {

    private static final int PLATE = 7;
    private static final float FACING_NORTH = 180.0F;
    private static final float FACING_SOUTH = 0.0F;

    private OrdersScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(OrdersContent.ID, "folkways.panel.orders",
            ResourceLocation.withDefaultNamespace("writable_book"),
            Scenes.scene("orders", OrdersScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("orders", "Orders: name what you want kept, the colony works out the rest");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos sourceChest = util.grid().at(1, 1, 2);
        BlockPos craftingTable = util.grid().at(3, 1, 2);
        BlockPos targetChest = util.grid().at(5, 1, 2);
        BlockPos furnace = util.grid().at(5, 1, 4);

        scene.world().setBlock(sourceChest, chestFacing(Direction.SOUTH), false);
        scene.world().setBlock(craftingTable, Blocks.CRAFTING_TABLE.defaultBlockState(), false);
        scene.world().setBlock(targetChest, chestFacing(Direction.SOUTH), false);
        scene.world().setBlock(furnace,
            Blocks.FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 1, PLATE - 1), Direction.DOWN);
        scene.idle(10);

        Body resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(2, 0, 4), FACING_NORTH);
        scene.idle(10);

        Vec3 target = util.vector().topOf(targetChest);
        scene.overlay().showOutline(PonderPalette.OUTPUT, targetChest, util.select().position(targetChest), 90);
        scene.overlay().showText(80)
            .text("An order keeps one item stocked in one container. Open one of the colony's chests, and the Orders page beside it files the draft into that chest.")
            .pointAt(target)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay().showText(90)
            .text("The draft is an item, a Refill below mark, a Fill to count, and a Priority from -2, served first, to 2, served last.")
            .pointAt(target)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(100)
            .text("Maintain files it as a standing order: once the chest drops below the refill mark, it is filled back up to the Fill to count. Deliver fills it to that count once, and the order is done.")
            .pointAt(target)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        resident.walkTo(scene, util.vector().topOf(1, 0, 3));
        resident.hold(scene, new ItemStack(Items.WHEAT, 3));
        scene.idle(20);
        resident.walkTo(scene, util.vector().topOf(3, 0, 3));
        scene.idle(20);
        resident.hold(scene, new ItemStack(Items.BREAD, 3));
        scene.idle(20);
        resident.walkTo(scene, util.vector().topOf(5, 0, 3));
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showOutline(PonderPalette.GREEN, craftingTable, util.select().position(craftingTable), 90);
        scene.overlay().showText(90)
            .text("You order the finished goods. Fetching the grain and working it at a crafting table, stonecutter or smithing table of the colony's is the colony's business.")
            .pointAt(util.vector().topOf(craftingTable))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        resident.walkTo(scene, util.vector().topOf(5, 0, 5));
        resident.turnTo(scene, FACING_SOUTH);
        resident.hold(scene, new ItemStack(Items.RAW_IRON, 2));
        scene.idle(15);
        scene.world().modifyBlock(furnace, state -> state.setValue(BlockStateProperties.LIT, true), false);
        scene.overlay().showOutline(PonderPalette.FAST, furnace, util.select().position(furnace), 90);
        scene.overlay().showText(100)
            .text("What must be smelted goes through a furnace, blast furnace or smoker of the colony's, kept fed from the fuel list on the Workshop page: coal and charcoal, to start with.")
            .pointAt(util.vector().topOf(furnace))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(60);
        resident.hold(scene, new ItemStack(Items.IRON_INGOT, 2));
        scene.world().modifyBlock(furnace, state -> state.setValue(BlockStateProperties.LIT, false), false);
        scene.idle(50);
        resident.hold(scene, ItemStack.EMPTY);

        scene.overlay().showOutline(PonderPalette.RED, sourceChest, util.select().position(sourceChest), 90);
        scene.overlay().showText(90)
            .text("Nobody goes out to dig for an order. If the stores hold neither the goods nor what they are made from, it waits.")
            .pointAt(util.vector().topOf(sourceChest))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(80)
            .text("To withdraw an order, open its chest and press Del beside it on the Orders page.")
            .pointAt(target)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);
    }

    private static BlockState chestFacing(Direction facing) {
        return Blocks.CHEST.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
    }
}
