package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarCabinetBlockEntity;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class TavernScene {
    private TavernScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("tavern", "Tavern: from the vineyard to a glass of wine");
        PonderScenery.openWorld(scene, util, 8);
        PonderScenery.grassGround(scene, util, 8);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 7, 5, 7), Direction.DOWN);
        scene.idle(15);
        for (int y = 1; y <= 4; y++) {
            for (int x : new int[] {2, 4}) for (int z : new int[] {2, 4}) {
                scene.world().setBlock(util.grid().at(x, y, z), tavern("trellis", "type", "single"), false);
            }
            scene.idle(8);
        }
        for (int x = 2; x <= 4; x++) for (int z = 2; z <= 4; z++) {
            scene.world().setBlock(util.grid().at(x, 5, z), tavern("trellis", "type", "cross_up_down"), false);
        }
        scene.overlay().showText(130)
            .text("Mark a rectangular grape plot and choose grapevines. Supply trellises and grapevine plants: residents build four corner posts, each four blocks high, then a pergola over the plot five blocks above the soil.")
            .pointAt(util.vector().topOf(4, 1, 4)).placeNearTarget().attachKeyFrame();
        scene.idle(140);
        for (int x : new int[] {2, 4}) for (int z : new int[] {2, 4}) {
            scene.world().setBlock(util.grid().at(x, 1, z), tavern("grapevine_trellis", "type", "single"), false);
        }
        BlockPos grapes = util.grid().at(3, 4, 4);
        scene.world().setBlock(grapes, tavern("grape_crop", "age", "5"), false);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 6), 180);
        resident.walkTo(scene, util.vector().topOf(3, 0, 5));
        scene.overlay().showText(110)
            .text("Residents plant the vines at the feet of the finished posts. The vines grow over the pergola and hang grapes underneath; residents pick the ripe grapes. Leave room around the plot to work.")
            .pointAt(util.vector().centerOf(grapes)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        scene.world().destroyBlock(grapes);
        resident.hold(scene, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("kaleidoscope_tavern", "grape"))));
        scene.idle(20);
        scene.world().setBlocks(util.select().fromTo(2, 1, 2, 4, 5, 4), Blocks.AIR.defaultBlockState(), false);
        BlockPos tub = util.grid().at(1, 1, 2);
        BlockPos barrel = util.grid().at(4, 1, 2);
        scene.world().setBlock(tub, tavern("pressing_tub"), false);
        for (int y = 0; y < 3; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            scene.world().setBlock(barrel.offset(x, y, z), tavern("barrel", "facing", "south",
                "layer", y == 0 ? "floor" : y == 1 ? "wall" : "ceiling", "index", Integer.toString((z + 1) * 3 + x + 1)), false);
        }
        BlockPos tap = barrel.offset(0, 1, 2);
        scene.world().setBlock(tap, tavern("tap", "facing", "south"), false);
        resident.walkTo(scene, util.vector().topOf(1, 0, 3));
        scene.overlay().showText(120)
            .text("Register a pressing tub and a Tavern barrel as kitchen stations, allow the crafting trade and order wine. Residents press eight grapes into a juice bucket. Supply buckets and empty wine bottles as well.")
            .pointAt(util.vector().topOf(tub)).placeNearTarget().attachKeyFrame();
        scene.idle(130);
        scene.world().setBlock(util.grid().at(6, 1, 2), tavern("stepladder", "facing", "west", "half", "bottom"), false);
        scene.world().setBlock(util.grid().at(6, 2, 2), tavern("stepladder", "facing", "west", "half", "top"), false);
        resident.hold(scene, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("kaleidoscope_tavern", "grape_bucket"))));
        resident.walkTo(scene, util.vector().topOf(1, 0, 5));
        resident.walkTo(scene, util.vector().topOf(6, 0, 5));
        resident.walkTo(scene, util.vector().topOf(6, 0, 2));
        for (int step = 1; step <= 40; step++) {
            resident.moveTo(scene, util.vector().topOf(6, 0, 2).add(0, step * 3.0 / 40, 0), 90);
            scene.idle(1);
        }
        resident.walkTo(scene, util.vector().topOf(5, 3, 2));
        resident.lookAt(scene, util.vector().topOf(barrel.above(2)));
        scene.overlay().showText(135)
            .text("The barrel takes four juice buckets through its top. Build a route onto it, such as a stepladder. Keep a closed tap on its side and solid ground beneath the bottle spot; after fermentation residents set out, fill and collect the bottles one at a time.")
            .pointAt(util.vector().topOf(barrel.above(2))).placeNearTarget().attachKeyFrame();
        scene.idle(145);
        resident.hold(scene, ItemStack.EMPTY);
        resident.moveTo(scene, util.vector().topOf(6, 0, 4), 180);
        scene.world().setBlocks(util.select().fromTo(1, 1, 1, 6, 3, 4), Blocks.AIR.defaultBlockState(), false);
        BlockPos counter = util.grid().at(3, 1, 2);
        BlockPos cabinet = util.grid().at(1, 1, 2);
        scene.world().setBlock(counter, tavern("bar_counter", "facing", "south"), false);
        scene.world().setBlock(cabinet, tavern("bar_cabinet", "facing", "south"), false);
        scene.world().setBlock(util.grid().at(3, 1, 3), Blocks.OAK_STAIRS.defaultBlockState(), false);
        ItemStack wine = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("kaleidoscope_tavern", "wine")));
        scene.world().modifyBlockEntity(cabinet, BarCabinetBlockEntity.class, bottles -> bottles.setLeftItem(wine.copy()));
        scene.world().modifyBlockEntity(counter, TavernCounterBlockEntity.class, bottles -> bottles.setItem(0, wine.copy()));
        scene.overlay().showText(125)
            .text("Bar cabinets hold the bottles themselves and can be stocked through delivery orders when registered as storage. Bar counters in a dining area are stocked automatically from its drink list. Choose wine there and keep the counter reachable.")
            .pointAt(util.vector().topOf(counter)).placeNearTarget().attachKeyFrame();
        scene.idle(135);
        resident.walkTo(scene, util.vector().topOf(4, 0, 3));
        resident.lookAt(scene, util.vector().topOf(counter));
        scene.world().modifyBlockEntity(counter, TavernCounterBlockEntity.class, bottles -> bottles.setItem(0, ItemStack.EMPTY));
        resident.hold(scene, wine);
        scene.overlay().showText(115)
            .text("After a table meal, a diner can fetch a bottle from the counter. A chair beside the counter also lets a resident sit for a drink. Drinking gives the wine's own effects and leaves the empty bottle behind.")
            .pointAt(resident.at().add(0, 1.5, 0)).placeNearTarget().attachKeyFrame();
        scene.idle(125);
    }
    private static BlockState tavern(String path, String... properties) {
        return PonderScenery.foreign(ResourceLocation.fromNamespaceAndPath("kaleidoscope_tavern", path), properties);
    }
}
