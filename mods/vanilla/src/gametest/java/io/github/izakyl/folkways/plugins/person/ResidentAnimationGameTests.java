package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentAnimationGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentAnimationGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void realBoardingOverridesWalkingAndDismountRestoresIt(GameTestHelper helper) {
        var resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        var boat = EntityType.BOAT.create(helper.getLevel());
        var resource = ResourceLocation.fromNamespaceAndPath("folkways", "test");
        var model = new ResidentLook.Model(resource, resource, resource, "idle", "walk",
            Optional.of("work"), Optional.of("ride"), ResidentLooks.FALLBACK.appearance().skin(), 1.0F);
        helper.assertValueEqual(ResidentAnimation.loop(resident, model, true), "walk", "walk before boarding");
        helper.assertTrue(resident.startRiding(boat, true), "the resident must really board the boat");
        helper.assertTrue(ResidentAnimation.seated(resident), "a real seated passenger must select the rider pose");
        helper.assertValueEqual(ResidentAnimation.loop(resident, model, true), "ride",
            "vehicle motion must never select walk for the seated passenger");
        helper.assertValueEqual(ResidentAnimation.loop(resident, model, false), "ride",
            "a stationary seat must keep the same rider pose");
        var old = new ResidentLook.Model(resource, resource, resource, "idle", "walk", Optional.empty(),
            ResidentLooks.FALLBACK.appearance().skin(), 1.0F);
        helper.assertValueEqual(ResidentAnimation.loop(resident, old, true), "idle",
            "old models keep their idle upper body while the renderer applies humanoid seated legs");
        resident.stopRiding();
        helper.assertTrue(!ResidentAnimation.seated(resident), "the rider pose must end on dismount");
        helper.assertValueEqual(ResidentAnimation.loop(resident, model, true), "walk", "resume walking on dismount");
        helper.assertValueEqual(ResidentAnimation.loop(resident, model, false), "idle", "and idle when stopped");
        helper.succeed();
    }
}
