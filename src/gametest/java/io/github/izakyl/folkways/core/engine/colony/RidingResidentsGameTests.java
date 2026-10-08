package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RidingResidentsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RidingResidentsGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void boardingTheFirstResidentKeepsWalkingRoutesAvailable(GameTestHelper helper) {
        var driver = PersonBody.RESIDENT.get().create(helper.getLevel());
        var passenger = PersonBody.RESIDENT.get().create(helper.getLevel());
        var vehicle = EntityType.BOAT.create(helper.getLevel());
        helper.assertTrue(driver.startRiding(vehicle, true), "the first resident must be mounted");
        var roster = List.<Body>of(driver, passenger);
        var walking = ColonyWays.roamers(roster);
        helper.assertTrue(walking.size() == 1 && walking.getFirst().body() == passenger,
            "the waiting passenger must provide walking paths instead of the seated driver");

        helper.assertTrue(passenger.startRiding(vehicle, true), "both residents must fit aboard");
        helper.assertTrue(ColonyWays.roamers(roster).size() == 1,
            "keep the locomotion kind available when everyone is riding");
        passenger.stopRiding();
        helper.assertTrue(ColonyWays.roamers(roster).getFirst().body() == passenger,
            "the disembarked passenger must resume providing walking paths");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void residentAndPlayerUseTheSameSeatedHipHeight(GameTestHelper helper) {
        var resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        var vehicle = EntityType.BOAT.create(helper.getLevel());
        helper.assertTrue(resident.getVehicleAttachmentPoint(vehicle)
                .equals(player.getVehicleAttachmentPoint(vehicle)),
            "a resident wearing a player skin must sit at the player's hip height");
        helper.succeed();
    }
}
