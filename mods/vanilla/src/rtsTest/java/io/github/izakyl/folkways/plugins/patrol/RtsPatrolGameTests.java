package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.inventory.InventoryObservations;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.rts.FolkwaysRtsMod;
import io.github.izakyl.folkways.rts.combat.group.UnitGroup;
import io.github.izakyl.folkways.rts.combat.command.ResidentCommands;
import io.github.izakyl.folkways.rts.combat.group.UnitGroups;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile;
import java.util.Optional;
import net.neoforged.neoforge.common.Tags;

@EventBusSubscriber(modid = FolkwaysRtsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RtsPatrolGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RtsPatrolGameTests.class);
    }

    @GameTest(template = "empty_large")
    public static void failedWaitingCannotResumeAnOldPatrolDutyButCanDefendItself(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        Body guard = PersonBody.RESIDENT.get().create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        place(helper, guard.mob(), new BlockPos(5, 1, 5));
        place(helper, zombie, new BlockPos(8, 1, 5));
        zombie.setNoAi(true);
        try {
            colony.hold(PersonContent.ID, Held.Entity.of(guard.mob())).orElseThrow();
            guard.joinColony(colony.id());
            arm(colony, guard, "Melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS));
            PatrolPresence patrol = new PatrolPresence(colony);
            patrol.watched(guard.id(), level.getGameTime());
            UnitGroups groups = UnitGroups.of(colony);
            for (int x = 0; x <= 11; x++) {
                for (int z = 0; z <= 11; z++) {
                    for (int y = -3; y <= 0; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                    }
                }
            }
            UnitGroup group = groups.stopSelection(List.of(guard));
            helper.assertValueEqual(
                group.phase(),
                UnitGroup.Phase.FAILED_WAITING,
                "a stop without any safe local footing still cancels movement and waits");
            for (int x = 0; x <= 11; x++) {
                for (int z = 0; z <= 11; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            guard.pack().insert(new ItemStack(Items.IRON_SWORD));
            InventoryObservations.publish(colony, level);
            Worker armed = worker(guard, guard.pack());
            helper.assertTrue(
                patrol.urges(armed, colony.view(level)).isEmpty(),
                "an old on-duty ward cannot restart proactive hunting while the squad waits for"
                + " intervention");
            guard.mob().setLastHurtByMob(zombie);
            helper.assertFalse(
                patrol.urges(armed, colony.view(level)).isEmpty(),
                "failed waiting still permits bounded defence against a real recent attacker");
        } finally {
            guard.mob().discard();
            zombie.discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void manualGuardRetainsCombatForAPersistentlyAssignedSentry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        Body guard = PersonBody.RESIDENT.get().create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        for (int x = 0; x < 15; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        place(helper, guard.mob(), new BlockPos(5, 1, 5));
        place(helper, zombie, new BlockPos(8, 1, 5));
        guard.mob().setNoAi(true);
        zombie.setNoAi(true);
        try {
            colony.hold(PersonContent.ID, Held.Entity.of(guard.mob())).orElseThrow();
            guard.joinColony(colony.id());
            arm(colony, guard, "Bow", ItemSpec.of(Items.BOW));
            UUID zone = colony.hold(SentryPosts.ZONE,
                new Held.Area(Set.of(WorldPos.of(level, helper.absolutePos(new BlockPos(5, 0, 5))))))
                .orElseThrow().id();
            SentryPosts posts = SentryPosts.of(colony);
            PatrolStaffing.of(colony).configure(level, zone,
                java.util.Set.of(LoadoutProfiles.of(colony).assigned(guard.id()).orElseThrow().id()), 1);
            var order = ResidentCommands.of(colony).guard(guard,
                WorldPos.of(level, helper.absolutePos(new BlockPos(5, 1, 5))), new Vec3(1, 0, 0));
            guard.pack().insert(new ItemStack(Items.IRON_SWORD));
            InventoryObservations.publish(colony, level);
            Worker armed = worker(guard, guard.pack());
            helper.assertTrue(posts.urges(armed, colony.view(level)).isEmpty(), "direct control suspends post duty");
            helper.assertFalse(colony.service(PatrolContent.ID, PatrolPresence.class).orElseThrow()
                .urges(armed, colony.view(level)).isEmpty(),
                "the existing manual guard still acquires an in-leash hostile despite its saved post assignment");
            order.close();
        } finally {
            guard.mob().discard();
            zombie.discard();
            colony.raze();
        }
        helper.succeed();
    }

    private static void place(GameTestHelper helper, Mob mob, BlockPos relative) {
        BlockPos at = helper.absolutePos(relative);
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);
    }

    private static Worker worker(Body body, ItemStack weapon) {
        Container pack = new SimpleContainer(1);
        pack.setItem(0, weapon);
        return worker(body, pack);
    }

    private static Worker worker(Body body, Container pack) {
        return TestWorker.of(body).pack(pack).heldFromPack();
    }

    private static void arm(Colony colony, Body body, String name, ItemSpec weapon) {
        LoadoutProfiles profiles = LoadoutProfiles.of(colony);
        LoadoutProfile profile =
            profiles.all().stream()
            .filter(one -> one.name().equals(name))
            .findFirst()
            .orElseGet(() -> profiles.add(profiles.create(name).id(), LoadoutProfile.Entry.weapon(weapon)));
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
    }
}
