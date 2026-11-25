package io.github.izakyl.folkways.vanilla.person.fear;

import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class FearEscapeGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FearEscapeGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void aWallHidesThePursuerUntilTheNextObservation(GameTestHelper helper) {
        floor(helper);
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var body = PersonBody.RESIDENT.get().create(level);
        var zombie = EntityType.ZOMBIE.create(level);
        place(helper, body, new BlockPos(10, 1, 10));
        place(helper, zombie, new BlockPos(13, 1, 10));
        helper.setBlock(new BlockPos(12, 1, 10), Blocks.STONE);
        helper.setBlock(new BlockPos(12, 2, 10), Blocks.STONE);
        zombie.setTarget(body);
        body.hurt(level.damageSources().mobAttack(zombie), 1);
        var fear = new FearPresence();
        var worker = TestWorker.of(body);
        try {
            helper.assertTrue(fear.urges(colony, worker, colony.view(level)).isEmpty(),
                "the wall hides the resident's attacker");
            helper.setBlock(new BlockPos(12, 1, 10), Blocks.AIR);
            helper.setBlock(new BlockPos(12, 2, 10), Blocks.AIR);
            helper.assertTrue(fear.urges(colony, worker, colony.view(level)).isEmpty(),
                "clearing the view does not trigger another sight check in the same tick");
        } catch (RuntimeException failure) {
            body.discard();
            zombie.discard();
            colony.raze();
            throw failure;
        }
        helper.runAfterDelay(10, () -> {
            try {
                body.getRandom().setSeed(42L);
                helper.assertFalse(fear.urges(colony, worker, colony.view(level)).isEmpty(),
                    "the same attacker triggers flight once visible");
                helper.succeed();
            } finally {
                body.discard();
                zombie.discard();
                colony.raze();
            }
        });
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void escapeFavorsTheSideWithColonyFacilitiesAndResidents(GameTestHelper helper) {
        floor(helper);
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var body = PersonBody.RESIDENT.get().create(level);
        var zombie = EntityType.ZOMBIE.create(level);
        var first = PersonBody.RESIDENT.get().create(level);
        var second = PersonBody.RESIDENT.get().create(level);
        place(helper, body, new BlockPos(10, 1, 10));
        place(helper, zombie, new BlockPos(13, 1, 10));
        zombie.setTarget(body);
        body.hurt(level.damageSources().mobAttack(zombie), 1);
        try {
            for (int side : new int[] {-1, 1}) {
                var probe = helper.absolutePos(new BlockPos(4, 1, 10 + 5 * side));
                var path = body.getNavigation().createPath(probe, 0);
                helper.assertTrue(path != null && path.canReach(), "both escape directions are walkable");
            }
            // Move the same colony support between the two sides while holding the random sample constant.
            for (int side : new int[] {-1, 1}) {
                BlockPos store = new BlockPos(5, 1, 10 + 5 * side);
                helper.setBlock(store, Blocks.CHEST);
                var claim = colony.hold(WaresContent.STORE,
                    new Claimed.Block(WorldPos.of(level, helper.absolutePos(store)))).orElseThrow();
                settle(helper, colony, first, new BlockPos(4, 1, 10 + 5 * side));
                settle(helper, colony, second, new BlockPos(6, 1, 10 + 6 * side));
                body.getRandom().setSeed(42L);
                var urges = new FearPresence().urges(colony, TestWorker.of(body), colony.view(level));
                helper.assertFalse(urges.isEmpty(), "the visible zombie triggers flight");
                BlockPos to = urges.getFirst().take().get().spec().site().cell();
                Vec3 destination = Vec3.atBottomCenterOf(to);
                Vec3 towardSupport = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(10, 1, store.getZ())))
                    .subtract(body.position());
                helper.assertTrue(destination.subtract(body.position()).dot(towardSupport) > 0,
                    "expected escape toward colony support on side " + side + ", got " + to);
                helper.assertTrue(destination.distanceToSqr(zombie.position()) > body.distanceToSqr(zombie),
                    "the chosen destination increases separation from the zombie");
                colony.release(claim.id(), ClaimRelease.LET_GO);
                helper.setBlock(store, Blocks.AIR);
            }
        } finally {
            body.discard();
            zombie.discard();
            first.discard();
            second.discard();
            colony.raze();
        }
        helper.succeed();
    }

    private static void settle(GameTestHelper helper, Colony colony, ResidentEntity resident, BlockPos at) {
        if (colony.claimOf(resident.getUUID()).isEmpty()) {
            place(helper, resident, at);
            colony.hold(PersonContent.ID, Claimed.Entity.of(resident)).orElseThrow();
            resident.joinColony(colony.id());
            colony.entered(resident);
        } else {
            resident.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(at)));
        }
    }

    private static void place(GameTestHelper helper, Mob mob, BlockPos at) {
        mob.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(at)));
        mob.setNoAi(true);
        mob.setOnGround(true);
        helper.getLevel().addFreshEntity(mob);
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 22; x++) {
            for (int z = 0; z < 22; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }
}
