package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.LinkedHashSet;
import java.util.Set;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class PastureScene {

    private static final int PLATE = 11;

    private static final PonderPalette PASTURE = PonderPalette.OUTPUT;

    private PastureScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(PastureContent.ID, "folkways.page.pasture",
            ResourceLocation.withDefaultNamespace("lead"),
            Scenes.scene("pasture", PastureScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("pasture", "Pastures: say how many head, and the herd holds there");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        Set<BlockPos> pen = new LinkedHashSet<>(PonderScenery.ring(util, 1, 1, 6, 6, 1));
        PonderScenery.fence(scene, Blocks.OAK_FENCE, pen);
        PonderScenery.fence(scene, Blocks.OAK_FENCE, PonderScenery.ring(util, 8, 1, 10, 4, 1));
        PonderScenery.fence(scene, Blocks.OAK_FENCE, PonderScenery.ring(util, 8, 6, 10, 9, 1));
        scene.world().showSection(util.select().fromTo(0, 1, 0, PLATE - 1, 1, PLATE - 1), Direction.DOWN);
        scene.idle(10);

        ElementLink<EntityElement> milker = cow(scene, util.vector().topOf(2, 0, 2), 120.0F, false);
        cow(scene, util.vector().topOf(5, 0, 5), -60.0F, false);
        scene.idle(15);

        scene.overlay().showOutline(PASTURE, "pasture", util.select().fromTo(1, 0, 1, 6, 2, 6), 180);
        scene.overlay().showText(90)
            .text("Frame a pen the way you frame a plot. It has three settings: the animal it keeps (cows, sheep, pigs or chickens), a target head count from 0 to 64, and a shear switch.")
            .pointAt(util.vector().topOf(3, 1, 3))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(90)
            .text("The Pastures page lists every pen: its animal, how many stand in it against the target, and how many of those are grown.")
            .pointAt(util.vector().topOf(3, 1, 1))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        Body herder = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 5), 180.0F);
        scene.idle(10);
        herder.walkTo(scene, util.vector().topOf(3, 0, 4));
        scene.idle(5);
        herder.hold(scene, new ItemStack(Items.WHEAT, 2));
        scene.idle(20);
        cow(scene, util.vector().topOf(4, 0, 3), 0.0F, true);
        herder.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(90)
            .text("Below the target, residents feed the grown animals in pairs, and the pairs breed. Young ones count toward the head as soon as they are born.")
            .pointAt(util.vector().topOf(4, 1, 3))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        ElementLink<EntityElement> extraA = cow(scene, util.vector().topOf(2, 0, 5), 45.0F, false);
        ElementLink<EntityElement> extraB = cow(scene, util.vector().topOf(5, 0, 2), -135.0F, false);
        cow(scene, util.vector().topOf(4, 0, 5), 90.0F, false);
        scene.idle(20);
        herder.walkTo(scene, util.vector().topOf(3, 0, 3));
        scene.idle(5);
        scene.world().modifyEntity(extraA, Entity::discard);
        scene.idle(8);
        scene.world().modifyEntity(extraB, Entity::discard);
        herder.hold(scene, new ItemStack(Items.BEEF, 3));
        scene.overlay().showText(100)
            .text("Above the target, the whole surplus is culled in one go, always from the grown animals. Six head against a target of four: two cows go. A target of 0 culls every grown one.")
            .pointAt(util.vector().topOf(3, 1, 3))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);
        herder.hold(scene, ItemStack.EMPTY);

        herder.walkTo(scene, util.vector().topOf(3, 0, 2));
        herder.turnTo(scene, 90.0F);
        scene.idle(10);
        herder.hold(scene, new ItemStack(Items.MILK_BUCKET));
        scene.overlay().showOutline(PASTURE, "milk", util.select().position(util.grid().at(2, 1, 2)), 90);
        scene.overlay().showText(90)
            .text("Milk is drawn only when something in the colony asked for milk, and each cow gives it once a day.")
            .pointAt(util.vector().topOf(2, 1, 2))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
        herder.hold(scene, ItemStack.EMPTY);

        ElementLink<EntityElement> woolly = sheep(scene, util.vector().topOf(9, 0, 2), 30.0F);
        chicken(scene, util.vector().topOf(9, 0, 7), -30.0F);
        scene.world().createItemEntity(util.vector().centerOf(9, 1, 8), Vec3.ZERO, new ItemStack(Items.EGG));
        scene.idle(10);
        Body shearer = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(7, 0, 6), 180.0F);
        scene.idle(5);
        shearer.walkTo(scene, util.vector().topOf(7, 0, 3));
        shearer.turnTo(scene, 270.0F);
        shearer.hold(scene, new ItemStack(Items.SHEARS));
        scene.idle(15);
        scene.world().modifyEntity(woolly, entity -> ((Sheep) entity).setSheared(true));
        scene.world().createItemEntity(util.vector().centerOf(9, 1, 2), new Vec3(0.0, 0.15, 0.0),
            new ItemStack(Items.WHITE_WOOL, 2));
        scene.overlay().showText(100)
            .text("While the shear switch is on, sheep are shorn once their wool is back, and the shears must be on the Tools list under Settings. In a chicken pen, eggs are picked up where they fall.")
            .pointAt(util.vector().topOf(9, 1, 2))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);
        shearer.hold(scene, ItemStack.EMPTY);

        BlockPos gap = util.grid().at(3, 1, 1);
        scene.world().destroyBlock(gap);
        pen.remove(gap);
        PonderScenery.fence(scene, Blocks.OAK_FENCE, pen);
        scene.world().modifyEntity(milker, entity -> PonderScenery.stand(entity, util.vector().topOf(3, 0, 0), 180.0F));
        scene.overlay().showOutline(PonderPalette.RED, "gap", util.select().position(gap), 80);
        scene.overlay().showText(90)
            .text("Nothing checks the fence for you. A beast that walks off the ground you framed stops being counted, and the pen breeds back up to the target.")
            .colored(PonderPalette.RED)
            .pointAt(util.vector().topOf(3, 0, 1))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
    }

    private static ElementLink<EntityElement> cow(SceneBuilder scene, Vec3 pos, float yRot, boolean baby) {
        return scene.world().createEntity(level -> {
            Cow cow = EntityType.COW.create(level);
            if (cow == null) {
                return null;
            }
            cow.setBaby(baby);
            PonderScenery.stand(cow, pos, yRot);
            return cow;
        });
    }

    private static ElementLink<EntityElement> sheep(SceneBuilder scene, Vec3 pos, float yRot) {
        return scene.world().createEntity(level -> {
            Sheep sheep = EntityType.SHEEP.create(level);
            if (sheep == null) {
                return null;
            }
            PonderScenery.stand(sheep, pos, yRot);
            return sheep;
        });
    }

    private static void chicken(SceneBuilder scene, Vec3 pos, float yRot) {
        scene.world().createEntity(level -> {
            Chicken chicken = EntityType.CHICKEN.create(level);
            if (chicken == null) {
                return null;
            }
            PonderScenery.stand(chicken, pos, yRot);
            return chicken;
        });
    }
}
