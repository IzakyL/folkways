package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import java.util.Optional;
import java.util.Map;
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
    public static void interpolatedWalkingDoesNotRequireClientVelocity(GameTestHelper helper) {
        var resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        var resource = ResourceLocation.fromNamespaceAndPath("folkways", "test");
        var model = new ResidentLook.Model(resource, resource, resource, "idle", "walk", Optional.empty(),
            ResidentLooks.FALLBACK.appearance().skin(), 1.0F);
        resident.setDeltaMovement(0, 0, 0);
        resident.walkAnimation.update(0.4F, 1.0F);
        helper.assertValueEqual(ResidentAnimation.loop(resident, model, false), "walk",
            "interpolated displacement drives walking even when GeckoLib's velocity test is false");
        resident.walkAnimation.update(0, 1.0F);
        helper.assertValueEqual(ResidentAnimation.loop(resident, model, false), "idle", "stopping restores idle");
        helper.succeed();
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
        var dining = new ResidentLook.Model(resource, resource, resource, "idle", "walk",
            Optional.of("work"), Optional.of("ride"), ResidentLooks.FALLBACK.appearance().skin(), 1.0F,
            Map.of("dining", "table_meal"));
        resident.leisureMotion("dining");
        helper.assertValueEqual(ResidentAnimation.loop(resident, dining, false), "table_meal",
            "a seated table meal uses the model's authored dining animation");
        helper.assertValueEqual(ResidentAnimation.loop(resident, model, false), "ride",
            "models without a dining clip retain seated legs for the renderer's eating gesture");
        var seatedMeal = new ResidentLook.Model(resource, resource, resource, "idle", "walk",
            Optional.empty(), Optional.of("ride"), ResidentLooks.FALLBACK.appearance().skin(), 1.0F,
            Map.of("dining", "standing_meal", "dining_seated", "seated_meal"));
        helper.assertValueEqual(ResidentAnimation.loop(resident, seatedMeal, false), "seated_meal",
            "seated meals select the composed chair and item-use clip");
        resident.leisureMotion("");
        helper.assertValueEqual(ResidentAnimation.loop(resident, dining, false), "ride",
            "finishing the meal restores the ordinary seated pose");
        resident.stopRiding();
        helper.assertTrue(!ResidentAnimation.seated(resident), "the rider pose must end on dismount");
        helper.assertValueEqual(ResidentAnimation.loop(resident, model, true), "walk", "resume walking on dismount");
        helper.assertValueEqual(ResidentAnimation.loop(resident, model, false), "idle", "and idle when stopped");
        helper.succeed();
    }
}
