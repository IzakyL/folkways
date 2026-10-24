package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import java.util.ArrayList;
import java.util.List;
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
        ZombifiedPiglin piglin = EntityType.ZOMBIFIED_PIGLIN.create(helper.getLevel());
        Cow cow = EntityType.COW.create(helper.getLevel());
        try {
            check(helper, zombie, true);
            check(helper, skeleton, true);
            check(helper, enderman, false);
            check(helper, piglin, false);
            check(helper, cow, false);
        } finally {
            zombie.discard();
            skeleton.discard();
            enderman.discard();
            piglin.discard();
            cow.discard();
        }
        helper.succeed();
    }

    private static void check(GameTestHelper helper, Mob mob, boolean supported) {
        List<WrappedGoal> original = List.copyOf(mob.targetSelector.getAvailableGoals());
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
        helper.assertTrue(mob.targetSelector.getAvailableGoals().containsAll(original),
            "all native target goals are retained");
        mob.targetSelector.getAvailableGoals().stream()
            .filter(goal -> goal.getGoal().getClass().getName().contains("NativeHostility$ResidentTargetGoal"))
            .forEach(goal -> helper.assertTrue(original.stream().allMatch(nativeGoal ->
                    nativeGoal.getPriority() < goal.getPriority()),
                "resident acquisition must follow every existing native target priority"));
    }

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void aZombieDiscoversPursuesAndHurtsAnEnrolledResident(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(true, new BlockPos(12, 1, 3));
        Zombie zombie = arena.monster(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        Vec3 start = zombie.position();
        helper.succeedWhen(() -> {
            helper.assertTrue(resident.mob().getHealth() < resident.mob().getMaxHealth(),
                "native zombie AI must inflict real damage without an assigned target");
            helper.assertTrue(zombie.getTarget() == resident.mob(), "the enrolled resident is the acquired target");
            helper.assertTrue(zombie.position().distanceToSqr(start) > 4, "the zombie must actually pursue");
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 360)
    public static void aSkeletonDiscoversAndShootsAnEnrolledResident(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(true, new BlockPos(12, 1, 3));
        Skeleton skeleton = arena.monster(EntityType.SKELETON, new BlockPos(2, 1, 3));
        skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        helper.succeedWhen(() -> {
            helper.assertTrue(resident.mob().getHealth() < resident.mob().getMaxHealth(),
                "native skeleton AI must hit the resident with an arrow");
            helper.assertTrue(skeleton.getTarget() == resident.mob(), "the skeleton acquired the resident itself");
            helper.assertTrue(resident.mob().getLastDamageSource() != null
                    && resident.mob().getLastDamageSource().getDirectEntity() instanceof
                        net.minecraft.world.entity.projectile.AbstractArrow,
                "the damage must come from a real native projectile");
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 160)
    public static void unEnrolledResidentsAndLivestockAreIgnored(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(false, new BlockPos(8, 1, 3));
        Cow cow = arena.monster(EntityType.COW, new BlockPos(8, 1, 6));
        cow.setNoAi(true);
        Zombie zombie = arena.monster(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        Skeleton skeleton = arena.monster(EntityType.SKELETON, new BlockPos(2, 1, 6));
        skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(zombie.getTarget() == null && skeleton.getTarget() == null,
                "native AI must not acquire an unaffiliated resident or cow");
            helper.assertValueEqual(resident.mob().getHealth(), resident.mob().getMaxHealth(), "resident unharmed");
            helper.assertValueEqual(cow.getHealth(), cow.getMaxHealth(), "livestock unharmed");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 220)
    public static void leavingTheColonyEndsAnExistingPursuit(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(true, new BlockPos(12, 1, 3));
        Zombie zombie = arena.monster(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(zombie.getTarget() == resident.mob(), "zombie acquired resident"))
            .thenExecute(resident::leaveColony)
            .thenWaitUntil(() -> helper.assertTrue(zombie.getTarget() == null, "leaving enrollment ends pursuit"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void loadedMonstersReinstallExactlyOneGoalAndAttack(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(true, new BlockPos(12, 1, 3));
        Zombie original = arena.monster(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        CompoundTag saved = new CompoundTag();
        original.save(saved);
        original.discard();
        Zombie loaded = (Zombie) EntityType.loadEntityRecursive(saved, helper.getLevel(), entity -> entity);
        helper.getLevel().addFreshEntity(loaded);
        arena.entities.add(loaded);
        NativeHostility.joined(new EntityJoinLevelEvent(loaded, helper.getLevel()));
        NativeHostility.joined(new EntityJoinLevelEvent(loaded, helper.getLevel()));
        helper.assertValueEqual(loaded.targetSelector.getAvailableGoals().stream()
            .filter(goal -> goal.getGoal().getClass().getName().contains("NativeHostility$ResidentTargetGoal")).count(),
            1L, "NBT reload and repeated join events install exactly one goal");
        helper.succeedWhen(() -> helper.assertTrue(resident.mob().getHealth() < resident.mob().getMaxHealth(),
            "a monster loaded from NBT must discover and attack without manual installation"));
    }

    @GameTest(template = "empty_large", timeoutTicks = 160)
    public static void neutralEndermenAndZombifiedPiglinsStayNeutral(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(true, new BlockPos(8, 1, 3));
        EnderMan enderman = arena.monster(EntityType.ENDERMAN, new BlockPos(3, 1, 3));
        ZombifiedPiglin piglin = arena.monster(EntityType.ZOMBIFIED_PIGLIN, new BlockPos(3, 1, 6));
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(enderman.getTarget() == null && piglin.getTarget() == null,
                "native neutral monsters must not acquire an unprovoking resident");
            helper.assertFalse(Threats.hostile(enderman, resident.mob()), "neutral enderman is not a patrol target");
            helper.assertFalse(Threats.hostile(piglin, resident.mob()), "neutral piglin is not a patrol target");
            helper.assertValueEqual(resident.mob().getHealth(), resident.mob().getMaxHealth(), "resident unharmed");
            helper.succeed();
        });
    }

    private static final class Arena implements GameTestListener {
        private final GameTestHelper helper;
        private final Colony colony;
        private final List<Entity> entities = new ArrayList<>();

        Arena(GameTestHelper helper) {
            this.helper = helper;
            colony = Colonies.mint(helper.getLevel().getServer());
            for (int x = 0; x < 22; x++) {
                for (int z = 0; z < 12; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                    helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
                }
            }
            helper.testInfo.addListener(this);
        }

        Body resident(boolean enrolled, BlockPos at) {
            Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
            body.mob().setNoAi(true);
            if (enrolled) {
                colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
                body.joinColony(colony.id());
            }
            place(body.mob(), at);
            return body;
        }

        <T extends Mob> T monster(EntityType<T> type, BlockPos at) {
            T mob = type.create(helper.getLevel());
            mob.setPersistenceRequired();
            mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            mob.getRandom().setSeed(42L);
            place(mob, at);
            return mob;
        }

        private void place(Mob mob, BlockPos at) {
            BlockPos absolute = helper.absolutePos(at);
            mob.moveTo(absolute.getX() + .5, absolute.getY(), absolute.getZ() + .5);
            entities.add(mob);
            helper.getLevel().addFreshEntity(mob);
        }

        private void close() {
            entities.forEach(Entity::discard);
            colony.raze();
        }

        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
