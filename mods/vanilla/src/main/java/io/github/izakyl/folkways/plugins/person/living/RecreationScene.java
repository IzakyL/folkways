package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class RecreationScene {
    private RecreationScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("recreation", "Recreation: time to unwind");
        PonderScenery.openWorld(scene, util, 7);
        PonderScenery.grassGround(scene, util, 7);
        scene.world().setBlock(util.grid().at(1, 1, 1), Blocks.POPPY.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(5, 1, 2), Blocks.DANDELION.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(1, 1, 4), Blocks.OXEYE_DAISY.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 6, 1, 6), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.GREEN, "garden", util.select().fromTo(1, 0, 1, 5, 1, 5), 105);
        var first = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 5), 180);
        first.walkTo(scene, util.vector().topOf(3, 0, 3));
        scene.overlay().showText(105)
            .text("In quiet moments residents stroll around nearby. Where flowers grow they stop and relax a while, which restores more leisure than a plain walk.")
            .pointAt(util.vector().topOf(2, 0, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(115);
        var second = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(5, 0, 4), 90);
        second.walkTo(scene, util.vector().topOf(4, 0, 3));
        first.lookAt(scene, second.at());
        second.lookAt(scene, first.at());
        scene.world().modifyEntities(ResidentEntity.class, resident ->
            resident.leisureMotion("chatting", resident.getX() > 4 ? 1 : 0));
        scene.overlay().showText(110)
            .text("Chatting with a neighbour and spending time with pets restore leisure too. Residents choose these activities themselves, and may take a short break when leisure runs very low.")
            .pointAt(first.at().add(0, 1.5, 0)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        scene.overlay().showText(120)
            .text("Pets may visit once at least three residents have beds in rooms graded suitable dormitory or better. Every half-day brings a chance, with mob spawning enabled and a safe place nearby; a visit is not guaranteed.")
            .pointAt(util.vector().topOf(2, 0, 4)).placeNearTarget().attachKeyFrame();
        scene.idle(130);
        PonderScenery.body(scene, EntityType.CAT, util.vector().topOf(2, 0, 4), 270);
        scene.world().modifyEntities(ResidentEntity.class, resident -> resident.leisureMotion(""));
        first.walkTo(scene, util.vector().topOf(3, 0, 4));
        first.lookAt(scene, util.vector().topOf(2, 0, 4));
        scene.world().modifyEntities(ResidentEntity.class, resident -> {
            if (resident.getX() < 4) { resident.leisureMotion("petting"); }
        });
        scene.overlay().showText(120)
            .text("The colony can attract one pet per three well-housed residents: cats at three, wolves join the pool at six, parrots at ten. They arrive untamed. Residents also enjoy healthy pets tamed by colony members.")
            .pointAt(util.vector().topOf(2, 0, 4)).placeNearTarget().attachKeyFrame();
        scene.idle(130);
    }
}
