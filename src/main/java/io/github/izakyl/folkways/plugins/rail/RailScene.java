package io.github.izakyl.folkways.plugins.rail;

import io.github.izakyl.folkways.core.api.terms.Gait;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.plugins.CreateMod;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.rail.domain.RailContent;
import java.util.List;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

// Registered only with Create loaded; its blocks are looked up by id so this class never loads Create's own.
// The carriage is shown as the blocks it is built from; residents ride it as the section moves.
@OnlyIn(Dist.CLIENT)
public final class RailScene {

    private static final int PLATE = 11;
    private static final int TRACK_Z = 5;
    private static final int RUN = 5;
    private static final float FACING_SOUTH = 0.0F;
    private static final float FACING_NORTH = 180.0F;
    private static final float FACING_EAST = 270.0F;

    private RailScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(RailContent.ID, "folkways.page.rail",
            ResourceLocation.withDefaultNamespace("minecart"),
            Scenes.scene("rail", RailScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("rail", "Rail: give the colony a train to run");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos firstStation = util.grid().at(2, 1, TRACK_Z - 1);
        BlockPos secondStation = util.grid().at(2 + RUN, 1, TRACK_Z - 1);
        BlockPos driverSeat = util.grid().at(3, 4, TRACK_Z);
        BlockPos passengerSeat = util.grid().at(2, 4, TRACK_Z);

        BlockState track = create("track", "shape", "xo");
        for (int x = 0; x < PLATE; x++) {
            scene.world().setBlock(util.grid().at(x, 1, TRACK_Z), track, false);
        }
        scene.world().setBlock(firstStation, create("track_station"), false);
        scene.world().setBlock(secondStation, create("track_station"), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 1, PLATE - 1), Direction.DOWN);
        scene.idle(10);

        scene.world().setBlock(util.grid().at(3, 2, TRACK_Z), create("small_bogey", "axis", "x"), false);
        for (int x = 2; x <= 4; x++) {
            scene.world().setBlock(util.grid().at(x, 3, TRACK_Z), create("railway_casing"), false);
        }
        scene.world().setBlock(util.grid().at(4, 4, TRACK_Z), create("controls", "facing", "east"), false);
        scene.world().setBlock(driverSeat, create("red_seat"), false);
        scene.world().setBlock(passengerSeat, create("white_seat"), false);
        Selection carriage = util.select().fromTo(2, 2, TRACK_Z, 4, 4, TRACK_Z);
        ElementLink<WorldSectionElement> train = scene.world().showIndependentSection(carriage, Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(80)
            .text("Build and assemble a train with Create as usual. The colony can run it for you.")
            .pointAt(util.vector().topOf(3, 3, TRACK_Z))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        ItemStack schedule = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("create", "schedule")));
        scene.overlay().showControls(util.vector().topOf(driverSeat), Pointing.DOWN, 50).rightClick()
            .withItem(schedule);
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.GREEN, "seat", util.select().position(driverSeat), 90);
        scene.overlay().showText(100)
            .text("With the colony book in your off hand, use a Train Schedule on the driver's seat of a train that has none. The train takes the schedule, and the colony takes the train on.")
            .pointAt(util.vector().topOf(driverSeat))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        scene.overlay().showControls(util.vector().topOf(driverSeat), Pointing.DOWN, 40).rightClick();
        scene.idle(10);
        scene.overlay().showText(70)
            .text("An empty hand on the same seat, book still in the off hand, takes the schedule out and gives the train back.")
            .pointAt(util.vector().topOf(driverSeat))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);

        Rider driver = rider(scene, util.vector().topOf(3, 0, 2), FACING_SOUTH);
        scene.idle(10);
        driver.walk(scene, util.vector().topOf(3, 0, TRACK_Z - 1));
        driver.walk(scene, seatTop(driverSeat));
        driver.face(scene, FACING_EAST);
        scene.idle(10);
        scene.overlay().showText(90)
            .text("While the train stands at a station with its driver's seat empty, a resident with the Drive trade walks over and sits in it.")
            .pointAt(util.vector().topOf(driverSeat))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(80)
            .text("Add Driver Seated to the stop's wait conditions, and the train waits there until every driver sent for has sat down.")
            .pointAt(util.vector().topOf(firstStation))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        Rider passenger = rider(scene, util.vector().topOf(2, 0, TRACK_Z + 2), FACING_NORTH);
        scene.idle(10);
        passenger.walk(scene, util.vector().topOf(2, 0, TRACK_Z + 1));
        passenger.walk(scene, seatTop(passengerSeat));
        passenger.face(scene, FACING_EAST);
        scene.idle(10);

        Vec3 run = new Vec3(RUN, 0, 0);
        int ticks = Math.max(1, Gait.ticksToWalk(RUN));
        scene.world().moveSection(train, run, ticks);
        glide(scene, List.of(driver, passenger), run, ticks);
        scene.idle(10);
        scene.overlay().showText(100)
            .text("With a driver aboard, a train that calls at two or more stations and has a free seat is a way to get about. Residents on their way somewhere ride it from one of its stations to another.")
            .pointAt(util.vector().topOf(2 + RUN, 4, TRACK_Z))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        passenger.walk(scene, util.vector().topOf(2 + RUN, 0, TRACK_Z + 1));
        passenger.walk(scene, util.vector().topOf(2 + RUN, 0, TRACK_Z + 2));
        scene.overlay().showText(80)
            .text("Add All Residents Alighted to a stop, and the train holds there until everyone getting off at that station is off.")
            .pointAt(util.vector().topOf(secondStation))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay().showText(80)
            .text("The Rail page lists each train taken on, its stations, whether it is running or at a stop, and when it has no driver.")
            .pointAt(util.vector().topOf(2 + RUN, 3, TRACK_Z))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);
    }

    private static Vec3 seatTop(BlockPos seat) {
        return new Vec3(seat.getX() + 0.5D, seat.getY() + 0.5D, seat.getZ() + 0.5D);
    }

    private static Rider rider(SceneBuilder scene, Vec3 at, float yaw) {
        ElementLink<EntityElement> link = scene.world().createEntity(level -> {
            Entity body = PersonBody.RESIDENT.get().create(level);
            if (body != null) {
                PonderScenery.stand(body, at, yaw);
            }
            return body;
        });
        return new Rider(link, at, yaw);
    }

    // Moves every rider by the same offset in step with the carriage.
    private static void glide(SceneBuilder scene, List<Rider> riders, Vec3 offset, int ticks) {
        for (int step = 1; step <= ticks; step++) {
            double before = (step - 1) / (double) ticks;
            double after = step / (double) ticks;
            for (Rider rider : riders) {
                rider.step(scene, rider.at.add(offset.scale(before)), rider.at.add(offset.scale(after)));
            }
            scene.idle(1);
        }
        for (Rider rider : riders) {
            rider.at = rider.at.add(offset);
            rider.settle(scene);
        }
    }

    private static final class Rider {

        private final ElementLink<EntityElement> link;
        private Vec3 at;
        private float yaw;

        private Rider(ElementLink<EntityElement> link, Vec3 at, float yaw) {
            this.link = link;
            this.at = at;
            this.yaw = yaw;
        }

        void walk(SceneBuilder scene, Vec3 target) {
            Vec3 from = at;
            double distance = target.subtract(from).length();
            if (distance > 1.0E-4D) {
                yaw = (float) (Mth.atan2(target.z - from.z, target.x - from.x) * (180.0D / Math.PI)) - 90.0F;
            }
            int steps = Math.max(1, Gait.ticksToWalk(distance));
            for (int step = 1; step <= steps; step++) {
                step(scene, from.lerp(target, (step - 1) / (double) steps), from.lerp(target, step / (double) steps));
                scene.idle(1);
            }
            at = target;
            settle(scene);
            scene.idle(5);
        }

        void face(SceneBuilder scene, float facing) {
            yaw = facing;
            settle(scene);
        }

        private void step(SceneBuilder scene, Vec3 before, Vec3 after) {
            float facing = yaw;
            scene.world().modifyEntity(link, entity -> {
                entity.setPos(after);
                entity.xo = before.x;
                entity.yo = before.y;
                entity.zo = before.z;
                entity.xOld = before.x;
                entity.yOld = before.y;
                entity.zOld = before.z;
                entity.setYRot(facing);
                entity.setYHeadRot(facing);
            });
        }

        private void settle(SceneBuilder scene) {
            Vec3 here = at;
            float facing = yaw;
            scene.world().modifyEntity(link, entity -> PonderScenery.stand(entity, here, facing));
        }
    }

    private static BlockState create(String path, String... properties) {
        return PonderScenery.foreign(ResourceLocation.fromNamespaceAndPath(CreateMod.ID, path), properties);
    }
}
