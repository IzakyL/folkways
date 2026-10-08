package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.plugins.CreateMod;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

// Registered only with Create loaded; its blocks are looked up by id so this class never loads Create's own.
@OnlyIn(Dist.CLIENT)
public final class DispatchScene {

    private static final int PLATE = 9;
    private static final float FACING_NORTH = 180.0F;
    private static final float FACING_EAST = 270.0F;
    private static final float FACING_WEST = 90.0F;

    private DispatchScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(DispatchContent.ID, "folkways.page.dispatch",
            ResourceLocation.withDefaultNamespace("chest_minecart"),
            Scenes.scene("dispatch", DispatchScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("dispatch", "Dispatch: order from a Create package network");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos stock = util.grid().at(7, 1, 7);
        BlockPos packager = util.grid().at(6, 1, 7);
        BlockPos link = util.grid().at(6, 2, 7);
        BlockPos desk = util.grid().at(4, 1, 7);
        BlockPos seat = util.grid().at(3, 1, 7);
        BlockPos port = util.grid().at(2, 1, 2);

        scene.world().setBlock(stock, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH),
            false);
        scene.world().setBlock(packager, create("packager", "facing", "west"), false);
        scene.world().setBlock(link, create("stock_link", "face", "floor", "facing", "north"), false);
        scene.world().setBlock(desk, create("stock_ticker", "facing", "north"), false);
        scene.world().setBlock(seat, create("red_seat"), false);
        scene.world().setBlock(port, create("package_frogport"), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 3, PLATE - 1), Direction.DOWN);
        scene.idle(15);

        ItemStack book = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        Selection network = util.select().fromTo(4, 1, 7, 7, 2, 7);

        scene.overlay().showOutline(PonderPalette.BLUE, "network", network, 180);
        scene.overlay().showText(180)
            .text("A package network built with Create: stock behind a packager and a stock link, and a Stock Ticker to order from. The colony only takes on a network where every packager hands its parcels only to frogports beside it, and all those frogports hang on one chain net, turning and in one dimension. Should that stop being so, the colony orders nothing until it is put right. Let nothing else take from those packagers, or orders go astray.")
            .pointAt(util.vector().topOf(link))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(190);

        scene.overlay().showControls(util.vector().topOf(desk), Pointing.DOWN, 50).leftClick().withItem(book);
        scene.idle(10);
        scene.overlay().showText(100)
            .text("With the book pointing, left-click any piece of the network: a linked block, a packager, a chain conveyor or a frogport on its chains. The colony takes the whole network on; the same click gives it back.")
            .pointAt(util.vector().blockSurface(desk, Direction.NORTH))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        scene.overlay().showText(70)
            .text("A network locked in Create can only be handed over by someone allowed to run it.")
            .pointAt(util.vector().blockSurface(desk, Direction.NORTH))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);

        Body keeper = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 4), FACING_NORTH);
        scene.idle(10);
        keeper.walkTo(scene, util.vector().topOf(3, 0, 6));
        keeper.walkTo(scene, new Vec3(seat.getX() + 0.5D, seat.getY() + 0.5D, seat.getZ() + 0.5D));
        keeper.turnTo(scene, FACING_EAST);
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.GREEN, "seat", util.select().position(seat), 90);
        scene.overlay().showText(90)
            .text("A Stock Ticker is a desk. A resident with the Dispatch trade sits on a seat beside it, on the same level or one below, and keeps it.")
            .pointAt(util.vector().topOf(seat))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(80)
            .text("The colony only orders from a network with its own keeper at the desk. With nobody there, the stock is counted but out of reach.")
            .pointAt(util.vector().topOf(desk))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay().showOutline(PonderPalette.GREEN, "port", util.select().position(port), 90);
        scene.overlay().showText(90)
            .text("Every Package Frogport along the network's chains is a pick-up point, except the ones beside its packagers, where parcels set out. The colony sends to whichever point is handiest. A point whose address is exactly * or contains any of { } , \\ is never used by the colony on its own.")
            .pointAt(util.vector().topOf(port))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        Body hauler = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(6, 0, 3), FACING_WEST);
        scene.idle(10);
        hauler.walkTo(scene, util.vector().topOf(3, 0, 2));
        hauler.turnTo(scene, FACING_WEST);
        scene.idle(10);
        hauler.hold(scene, new ItemStack(Items.IRON_INGOT, 16));
        scene.overlay().showText(90)
            .text("When the colony needs goods the network has in stock, it sends for them to a pick-up point. A hauler waits for the parcels and takes out what was ordered.")
            .pointAt(util.vector().topOf(port))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
        hauler.hold(scene, ItemStack.EMPTY);
        scene.idle(10);

        hauler.hold(scene, new ItemStack(Items.COBBLESTONE, 8));
        scene.overlay().showText(70)
            .text("Parcels nobody here is waiting for are emptied as well, so strays do not fill the pick-up point.")
            .pointAt(util.vector().topOf(port))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);
        hauler.hold(scene, ItemStack.EMPTY);

        scene.overlay().showText(80)
            .text("The Dispatch page lists the networks taken on, whether someone is at the desk, each pick-up point, and what is on the way.")
            .pointAt(util.vector().topOf(desk))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);
    }

    private static BlockState create(String path, String... properties) {
        return PonderScenery.foreign(ResourceLocation.fromNamespaceAndPath(CreateMod.ID, path), properties);
    }
}
