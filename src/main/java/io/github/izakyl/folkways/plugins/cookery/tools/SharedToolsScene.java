package io.github.izakyl.folkways.plugins.cookery.tools;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.KitchenwareRacksBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class SharedToolsScene {
    private SharedToolsScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_tools", "Shared kitchen tools: borrow for a task and return them");
        PonderScenery.openWorld(scene, util, 9);
        PonderScenery.grassGround(scene, util, 9);
        BlockPos rack = util.grid().at(2, 1, 2);
        BlockPos store = util.grid().at(1, 1, 6);
        BlockPos table = util.grid().at(4, 1, 2);
        BlockPos firstBoard = util.grid().at(6, 1, 2);
        BlockPos secondBoard = util.grid().at(6, 1, 6);
        scene.world().setBlock(rack, ModBlocks.KITCHENWARE_RACKS.get().defaultBlockState(), false);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        scene.world().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), false);
        scene.world().setBlock(firstBoard, ModBlocks.CHOPPING_BOARD.get().defaultBlockState(), false);
        scene.world().setBlock(secondBoard, ModBlocks.CHOPPING_BOARD.get().defaultBlockState(), false);
        scene.world().modifyBlockEntity(rack, KitchenwareRacksBlockEntity.class,
            holder -> holder.setItemLeft(new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get())));
        scene.world().showSection(util.select().fromTo(0, 0, 0, 8, 2, 8), Direction.DOWN);
        scene.idle(15);
        ItemStack book = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        ColonyBookItem.setGesture(book, Shape.Gesture.POINT);
        scene.overlay().showControls(util.vector().topOf(rack), Pointing.DOWN, 65).leftClick().withItem(book);
        scene.overlay().showText(95).text("Mark a kitchenware rack as a store. Its two real slots hold one supported tool each; ordinary stores remain available when the rack is full.")
            .pointAt(util.vector().topOf(rack)).placeNearTarget().attachKeyFrame();
        scene.idle(105);

        var first = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(4, 0, 4), 180);
        var second = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(5, 0, 6), 180);
        scene.overlay().showText(95).text("Idle crafting residents do not each fetch a knife. A task borrows the knife, shovel or sickle it actually needs, and keeps that same physical tool until its work is over.")
            .pointAt(first.at().add(0, 1, 0)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        first.walkTo(scene, util.vector().topOf(2, 0, 3));
        scene.world().modifyBlockEntity(rack, KitchenwareRacksBlockEntity.class, holder -> holder.setItemLeft(ItemStack.EMPTY));
        first.hold(scene, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        first.walkTo(scene, util.vector().topOf(6, 0, 3));
        scene.overlay().showOutline(PonderPalette.GREEN, firstBoard, util.select().position(firstBoard), 90);
        scene.overlay().showText(95).text("One knife means one active borrower. Another task waits while it is busy or being carried; waiting never creates a second knife.")
            .pointAt(util.vector().topOf(secondBoard)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        first.walkTo(scene, util.vector().topOf(2, 0, 3));
        first.hold(scene, ItemStack.EMPTY);
        scene.world().modifyBlockEntity(rack, KitchenwareRacksBlockEntity.class,
            holder -> holder.setItemLeft(new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get())));
        second.walkTo(scene, util.vector().topOf(2, 0, 3));
        scene.world().modifyBlockEntity(rack, KitchenwareRacksBlockEntity.class, holder -> holder.setItemLeft(ItemStack.EMPTY));
        second.hold(scene, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        second.walkTo(scene, util.vector().topOf(6, 0, 5));
        scene.overlay().showText(90).text("The returned tool goes to an allowed rack first, then to an ordinary store. Its name, enchantments and native wear stay with it when the next resident borrows it.")
            .pointAt(util.vector().topOf(rack)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        second.walkTo(scene, util.vector().topOf(2, 0, 3));
        second.hold(scene, ItemStack.EMPTY);
        scene.overlay().showOutline(PonderPalette.BLUE, table, util.select().position(table), 100);
        first.walkTo(scene, util.vector().topOf(4, 0, 3));
        first.hold(scene, new ItemStack(Items.IRON_INGOT));
        scene.overlay().showText(105).text("Only when the colony truly has no suitable tool and no real supply already underway does it ask for one replacement. Tool crafting happens before the food task, so the first crafting resident can make the first knife.")
            .pointAt(util.vector().topOf(table)).placeNearTarget().attachKeyFrame();
        scene.idle(115);
        first.hold(scene, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        first.walkTo(scene, util.vector().topOf(2, 0, 3));
        first.hold(scene, ItemStack.EMPTY);
        scene.world().modifyBlockEntity(rack, KitchenwareRacksBlockEntity.class,
            holder -> holder.setItemLeft(new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get())));
        scene.overlay().showText(95).text("Want two tasks to run at once? Set an explicit maintain order for two knives. Existing supply orders are respected, while an unreachable or unloaded tool is not mistaken for a destroyed one.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.world().modifyBlockEntity(rack, KitchenwareRacksBlockEntity.class,
            holder -> holder.setItemRight(new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get())));
        scene.overlay().showOutline(PonderPalette.GREEN, rack, util.select().position(rack), 90);
        scene.overlay().showText(95).text("The colony's Tools list still controls which tools may be used. An empty allowed list permits none; more residents alone never raises the shared stock target.")
            .pointAt(util.vector().topOf(rack)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.markAsFinished();
    }
}
