package io.github.izakyl.folkways.effect;

import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.effect.perk.GlobalPerks;
import io.github.izakyl.folkways.effect.perk.PerkPool;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.plugins.build.BuildContent;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import io.github.izakyl.folkways.plugins.pasture.PastureContent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EffectsGameTests {

    private static final double CLOSE = 1.0E-9;

    private static final Stat SPEED = new Stat(ResourceLocation.fromNamespaceAndPath("folkways", "test_speed"),
        1.0, 0.5, 3.0);

    private static final Flag DOES = new Flag(ResourceLocation.fromNamespaceAndPath("folkways", "test_does"));

    private static final ResourceLocation TRADE = ResourceLocation.fromNamespaceAndPath("folkways", "test_trade");

    private static final ResourceLocation DOG_GOLEM = ResourceLocation.fromNamespaceAndPath("modulargolems", "dog_golem");

    private static final ResourceLocation OTHER_TRADE = ResourceLocation.fromNamespaceAndPath("folkways", "test_other");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(EffectsGameTests.class);
    }

    // Every Add goes on the base before any Multiply, and what comes of them is held to the stat's bounds.
    @GameTest(template = "empty")
    public static void addsGoOnBeforeFactorsAndBoundsHold(GameTestHelper helper) {
        Effects effects = Effects.of(List.of(
            new Effects.Part("a", Modifier.add(SPEED, 0.5)),
            new Effects.Part("b", Modifier.multiply(SPEED, 1.2)),
            new Effects.Part("c", Modifier.add(SPEED, 0.25))));
        near(helper, effects.value(SPEED), (1.0 + 0.5 + 0.25) * 1.2, "adds summed, then multiplied");
        near(helper, Effects.of(List.of(new Effects.Part("a", Modifier.multiply(SPEED, 10.0)))).value(SPEED), 3.0,
            "held to the stat's ceiling");
        near(helper, Effects.of(List.of(new Effects.Part("a", Modifier.multiply(SPEED, 0.1)))).value(SPEED), 0.5,
            "held to the stat's floor");
        near(helper, Effects.NONE.value(SPEED), 1.0, "nothing on it is its base");
        helper.succeed();
    }

    // A change scoped to a trade bears on that trade's question alone; an unscoped one on every question.
    @GameTest(template = "empty")
    public static void aTradesChangeStaysInItsTrade(GameTestHelper helper) {
        Effects effects = Effects.of(List.of(
            new Effects.Part("hands", new Modifier.Multiply(SPEED, 2.0, Scope.trade(TRADE))),
            new Effects.Part("mood", Modifier.multiply(SPEED, 1.1))));
        near(helper, effects.value(SPEED, TRADE), 2.2, "the trade's own question");
        near(helper, effects.value(SPEED, OTHER_TRADE), 1.1, "another trade's question");
        near(helper, effects.value(SPEED), 1.1, "a question about no trade");
        helper.succeed();
    }

    // A flag holds while something grants it and nothing forbids it, whichever comes first.
    @GameTest(template = "empty")
    public static void aForbidOutweighsAnyGrant(GameTestHelper helper) {
        Effects granted = Effects.of(List.of(new Effects.Part("a", Modifier.grant(DOES))));
        helper.assertTrue(granted.has(DOES), "granted");
        helper.assertFalse(Effects.NONE.has(DOES), "nothing grants it");
        helper.assertFalse(granted.with(Effects.of(List.of(new Effects.Part("b", Modifier.forbid(DOES))))).has(DOES),
            "forbidden after the grant");
        helper.assertFalse(Effects.of(List.of(new Effects.Part("b", Modifier.forbid(DOES)))).with(granted).has(DOES),
            "forbidden before the grant");
        helper.assertTrue(Effects.of(List.of(new Effects.Part("a", new Modifier.Grant(DOES, Scope.trade(TRADE)))))
            .has(DOES, TRADE), "granted in its trade");
        helper.assertFalse(Effects.of(List.of(new Effects.Part("a", new Modifier.Grant(DOES, Scope.trade(TRADE)))))
            .has(DOES), "not granted outside it");
        helper.succeed();
    }

    // The perks grant through effects what they did by their ranks: the same ladders, the same numbers.
    @GameTest(template = "empty")
    public static void perksGrantWhatTheirRanksDid(GameTestHelper helper) {
        Effects hands = PerkPool.effects(Map.of(FarmingContent.QUICK_HANDS, 2));
        near(helper, 1.0 / hands.value(Stats.WORK_SPEED, FarmingContent.VOCATION),
            FolkwaysVanillaConfig.farmingHaste().get(1).doubleValue(), "farming takes the ladder's share of its ticks");
        near(helper, hands.value(Stats.WORK_SPEED, BuildContent.VOCATION), 1.0, "and building none of it");

        Effects body = PerkPool.effects(Map.of(
            GlobalPerks.PACKMULE, 2, GlobalPerks.BRISK, 3, GlobalPerks.LONGARM, 3, GlobalPerks.ASCETIC, 1));
        near(helper, body.value(Stats.PACK_ROOM), 1.30, "packmule II");
        near(helper, body.value(Stats.MOVE_SPEED), 1.30, "brisk III");
        near(helper, body.value(Stats.REACH), 3.0, "longarm III");
        near(helper, body.value(Stats.APPETITE), FolkwaysConfig.asceticAppetite().get(0).doubleValue(), "ascetic I");

        near(helper, PerkPool.effects(Map.of(PastureContent.BUTCHER, 3)).value(PastureContent.CARCASS_YIELD), 1.0,
            "butcher III doubles ordinary products");
        helper.assertTrue(PerkPool.effects(Map.of(FarmingContent.REPLANT, 1)).has(FarmingContent.REPLANTS),
            "replant grants replanting");
        helper.assertTrue(PerkPool.effects(Map.of(PerkPool.STOIC, 1)).has(Stats.PATIENT), "stoic grants patience");
        helper.assertFalse(PerkPool.effects(Map.of(PerkPool.STOIC, 0)).has(Stats.PATIENT), "no rank grants nothing");
        helper.assertTrue(PerkPool.effects(Map.of("no_such_perk", 1)).parts().isEmpty(),
            "a perk no longer declared grants nothing");
        helper.succeed();
    }

    // A golem built with brisk walks faster while it serves a colony, and at its own pace once let go.
    @GameTest(template = "empty")
    public static void aBuiltGolemCarriesItsPerksWhileEnrolled(GameTestHelper helper) {
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(DOG_GOLEM);
        if (type.isEmpty() || ResidentKinds.of(DOG_GOLEM).isEmpty()) {
            helper.succeed();
            return;
        }
        Entity golem = type.get().create(helper.getLevel());
        helper.assertTrue(golem != null, "a dog golem can be made");
        Body body = Bodies.of(golem).orElseThrow();
        AttributeInstance speed = body.mob().getAttribute(Attributes.MOVEMENT_SPEED);
        helper.assertTrue(speed != null, "a dog golem walks");
        double own = speed.getValue();
        body.joinColony(UUID.randomUUID());
        near(helper, speed.getValue(), own * 1.30, "brisk III while enrolled");
        body.leaveColony();
        near(helper, speed.getValue(), own, "its own pace once let go");
        golem.discard();
        helper.succeed();
    }

    private static void near(GameTestHelper helper, double got, double want, String what) {
        helper.assertTrue(Math.abs(got - want) < CLOSE, what + ": " + got + " where " + want + " was due");
    }
}
