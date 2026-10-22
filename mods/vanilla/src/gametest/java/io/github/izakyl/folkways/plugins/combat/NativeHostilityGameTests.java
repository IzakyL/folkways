package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NativeHostilityGameTests {

    private NativeHostilityGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(NativeHostilityGameTests.class);
    }

    @GameTest(template = "empty")
    public static void onlyEnrolledResidentsAreNativeTargets(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        Body resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        Cow cow = EntityType.COW.create(helper.getLevel());
        try {
            helper.assertFalse(NativeHostility.eligible(resident.mob()),
                "unaffiliated residents are not targets");
            helper.assertFalse(NativeHostility.eligible(cow), "ordinary livestock is not a resident");
            resident.joinColony(colony.id());
            helper.assertTrue(NativeHostility.eligible(resident.mob()),
                "a resident enrolled in a colony is a valid target");
            resident.leaveColony();
            helper.assertFalse(NativeHostility.eligible(resident.mob()),
                "a resident no longer enrolled is not targeted");
        } finally {
            resident.mob().discard();
            cow.discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeHostilityIsOnlyInstalledOnSupportedMonsters(GameTestHelper helper) {
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        Skeleton skeleton = EntityType.SKELETON.create(helper.getLevel());
        EnderMan enderman = EntityType.ENDERMAN.create(helper.getLevel());
        Cow cow = EntityType.COW.create(helper.getLevel());
        try {
            check(helper, zombie, true);
            check(helper, skeleton, true);
            check(helper, enderman, false);
            check(helper, cow, false);
        } finally {
            zombie.discard();
            skeleton.discard();
            enderman.discard();
            cow.discard();
        }
        helper.succeed();
    }

    private static void check(GameTestHelper helper, Mob mob, boolean supported) {
        NativeHostility.install(mob);
        int first = mob.targetSelector.getAvailableGoals().size();
        NativeHostility.install(mob);
        int second = mob.targetSelector.getAvailableGoals().size();
        helper.assertValueEqual(second, first, "injecting a goal again must not duplicate it");
        long added = mob.targetSelector.getAvailableGoals().stream()
            .map(WrappedGoal::getGoal)
            .filter(goal -> goal.getClass().getName().contains("NativeHostility$ResidentTargetGoal"))
            .count();
        helper.assertValueEqual(added, supported ? 1L : 0L,
            "unexpected added target goal on " + mob.getType());
    }
}
