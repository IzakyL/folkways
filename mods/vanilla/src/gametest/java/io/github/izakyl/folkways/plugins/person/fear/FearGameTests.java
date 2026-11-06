package io.github.izakyl.folkways.plugins.person.fear;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FearGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FearGameTests.class);
    }

    @GameTest(template = "empty")
    public static void aCampfireBurnSendsTheResidentAway(GameTestHelper helper) {
        Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        body.mob().moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        helper.getLevel().addFreshEntity(body.mob());
        try {
            FearPresence fear = new FearPresence();
            helper.assertTrue(fear.urges(worker(body), colony.view(helper.getLevel())).isEmpty(),
                "an unhurt resident has nothing to flee");
            body.mob().hurt(helper.getLevel().damageSources().campfire(), 1.0F);
            helper.assertTrue(fear.urges(worker(body), colony.view(helper.getLevel())).stream()
                    .anyMatch(urge -> urge.id().equals(FearPresence.FLEE)),
                "a campfire burn must make the resident flee");
        } finally {
            body.mob().discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void randomSamplingCannotWithdrawAnEscapeAlreadyUnderway(GameTestHelper helper) {
        floor(helper);
        Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        BlockPos at = helper.absolutePos(new BlockPos(10, 1, 10));
        body.mob().moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        body.mob().setNoAi(true);
        helper.getLevel().addFreshEntity(body.mob());
        try {
            body.mob().hurt(helper.getLevel().damageSources().campfire(), 1);
            FearPresence fear = new FearPresence();
            List<Urge> first = List.of();
            for (int attempt = 0; attempt < 20 && first.isEmpty(); attempt++) {
                first = fear.urges(worker(body), colony.view(helper.getLevel()));
            }
            helper.assertFalse(first.isEmpty(), "a safe point is found on an open floor");
            var destination = first.getFirst().take().get().spec().site().where();
            for (int attempt = 0; attempt < 100; attempt++) {
                var next = fear.urges(worker(body), colony.view(helper.getLevel()));
                helper.assertValueEqual(next.size(), 1, "a running escape is offered on every tick");
                helper.assertValueEqual(next.getFirst().take().get().spec().site().where(), destination,
                    "the escape destination stays stable while travelling");
            }
        } finally {
            body.mob().discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void escapingAnAttackerChoosesUsefulSeparation(GameTestHelper helper) {
        floor(helper);
        Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(10, 1, 10));
        body.mob().moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        body.mob().getRandom().setSeed(42L);
        var attacker = body.mob().position().add(2, 0, 0);
        var to = Fleeing.awayFrom(body.mob(), Optional.of(attacker)).orElseThrow();
        var point = Vec3.atBottomCenterOf(to);
        helper.assertTrue(point.distanceToSqr(attacker) > body.mob().position().distanceToSqr(attacker) + 16,
            "the escape point makes useful distance from the attacker");
        body.mob().discard();
        helper.succeed();
    }

    // Arming policy is LoadoutProfileGameTests' concern; here only fear's answer to real hostile damage.
    @GameTest(template = "empty_large")
    public static void hostileDamageKeepsFearUnderEitherNoProfilePolicy(GameTestHelper helper) {
        floor(helper);
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        var zombie = EntityType.ZOMBIE.create(helper.getLevel());
        var colony = Colonies.mint(helper.getLevel().getServer());
        BlockPos at = helper.absolutePos(new BlockPos(10, 1, 10));
        body.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        zombie.moveTo(body.getX() + 2, body.getY(), body.getZ());
        body.setNoAi(true);
        zombie.setNoAi(true);
        body.getRandom().setSeed(42L);
        helper.getLevel().addFreshEntity(body);
        helper.getLevel().addFreshEntity(zombie);
        colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        body.pack().insert(new ItemStack(Items.IRON_SWORD));
        zombie.setTarget(body);
        body.hurt(helper.getLevel().damageSources().mobAttack(zombie), 1);
        try {
            FearPresence fear = new FearPresence();
            var who = worker(body);
            helper.assertFalse(fear.urges(who, colony.view(helper.getLevel())).isEmpty(),
                "actual hostile damage retains the existing flee urge");
            ColonyFront.of(colony).setSetting(PatrolContent.ID, "no_profile",
                new ColonySettings.Value.Choice(ResourceLocation.fromNamespaceAndPath("folkways", "engage")));
            body.setHealth(body.getMaxHealth() * .25F);
            helper.assertFalse(fear.urges(who, colony.view(helper.getLevel())).isEmpty(),
                "engage policy does not disable wounded fear");
        } finally {
            body.discard();
            zombie.discard();
            colony.raze();
        }
        helper.succeed();
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 22; x++) {
            for (int z = 0; z < 22; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private static Worker worker(Body body) {
        return TestWorker.of(body).effects(Effects.NONE);
    }
}
