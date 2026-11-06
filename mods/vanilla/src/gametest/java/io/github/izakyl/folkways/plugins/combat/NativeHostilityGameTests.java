package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
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

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void aZombieDiscoversPursuesAndHurtsAnEnrolledResident(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(true, new BlockPos(12, 1, 3));
        Zombie zombie = arena.monster(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        Vec3 start = zombie.position();
        helper.succeedWhen(
            () -> {
                helper.assertTrue(
                    resident.mob().getHealth() < resident.mob().getMaxHealth(),
                    "native zombie AI must inflict real damage without an assigned target");
                helper.assertTrue(
                    zombie.getTarget() == resident.mob(), "the enrolled resident is the acquired target");
                helper.assertTrue(zombie.position().distanceToSqr(start) > 4, "the zombie must actually pursue");
            });
    }

    @GameTest(template = "empty_large", timeoutTicks = 360)
    public static void aSkeletonDiscoversAndShootsAnEnrolledResident(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(true, new BlockPos(12, 1, 3));
        Skeleton skeleton = arena.monster(EntityType.SKELETON, new BlockPos(2, 1, 3));
        skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        helper.succeedWhen(
            () -> {
                helper.assertTrue(
                    resident.mob().getHealth() < resident.mob().getMaxHealth(),
                    "native skeleton AI must hit the resident with an arrow");
                helper.assertTrue(
                    skeleton.getTarget() == resident.mob(), "the skeleton acquired the resident itself");
                helper.assertTrue(
                    resident.mob().getLastDamageSource() != null
                    && resident.mob().getLastDamageSource().getDirectEntity() instanceof AbstractArrow,
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
        helper.runAfterDelay(
            120,
            () -> {
                helper.assertTrue(
                    zombie.getTarget() == null && skeleton.getTarget() == null,
                    "native AI must not acquire an unaffiliated resident or cow");
                helper.assertValueEqual(
                    resident.mob().getHealth(), resident.mob().getMaxHealth(), "resident unharmed");
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
            .thenWaitUntil(
            () -> helper.assertTrue(zombie.getTarget() == resident.mob(), "zombie acquired resident"))
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
        helper.assertValueEqual(
            loaded.targetSelector.getAvailableGoals().stream()
            .filter(
            goal ->
                goal.getGoal()
                    .getClass()
                    .getName()
                    .contains("NativeHostility$ResidentTargetGoal"))
            .count(),
            1L,
            "NBT reload and repeated join events install exactly one goal");
        helper.succeedWhen(
            () ->
                helper.assertTrue(
                    resident.mob().getHealth() < resident.mob().getMaxHealth(),
                    "a monster loaded from NBT must discover and attack without manual installation"));
    }

    @GameTest(template = "empty_large", timeoutTicks = 160)
    public static void neutralEndermenAndZombifiedPiglinsStayNeutral(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(true, new BlockPos(8, 1, 3));
        EnderMan enderman = arena.monster(EntityType.ENDERMAN, new BlockPos(3, 1, 3));
        ZombifiedPiglin piglin = arena.monster(EntityType.ZOMBIFIED_PIGLIN, new BlockPos(3, 1, 6));
        helper.runAfterDelay(
            100,
            () -> {
                helper.assertTrue(
                    enderman.getTarget() == null && piglin.getTarget() == null,
                    "native neutral monsters must not acquire an unprovoking resident");
                helper.assertFalse(
                    Threats.hostile(enderman, resident.mob()), "neutral enderman is not a patrol target");
                helper.assertFalse(
                    Threats.hostile(piglin, resident.mob()), "neutral piglin is not a patrol target");
                helper.assertValueEqual(
                    resident.mob().getHealth(), resident.mob().getMaxHealth(), "resident unharmed");
                helper.succeed();
            });
    }

    @GameTest(template = "empty_large", timeoutTicks = 180)
    public static void aZombieRetainsItsNativeVillagerTarget(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.resident(true, new BlockPos(6, 1, 3));
        var villager = arena.monster(EntityType.VILLAGER, new BlockPos(10, 1, 3));
        villager.setNoAi(true);
        Zombie zombie = arena.monster(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        helper.succeedWhen(
            () ->
                helper.assertTrue(
                    zombie.getTarget() == villager,
                    "the higher-priority native villager target is still acquired in a resident's presence"));
    }

    @GameTest(template = "empty")
    public static void nestedEnemyTagsCoverNativeVariantsWithoutMakingAnimalsHostile(GameTestHelper helper) {
        for (EntityType<?> type :
            List.of(
            EntityType.ZOMBIE,
            EntityType.ZOMBIE_VILLAGER,
            EntityType.HUSK,
            EntityType.DROWNED,
            EntityType.SKELETON,
            EntityType.STRAY,
            EntityType.WITHER_SKELETON,
            EntityType.BOGGED,
            EntityType.SPIDER,
            EntityType.CAVE_SPIDER,
            EntityType.CREEPER)) {
            helper.assertTrue(
                type.is(CombatTags.RESIDENT_ENEMIES), "native variant inherits the configured enemy tag: " + type);
            helper.assertTrue(
                type.is(CombatTags.PATROL_TARGETS), "patrol tag includes its nested resident enemy tag: " + type);
        }
        helper.assertFalse(EntityType.COW.is(CombatTags.RESIDENT_ENEMIES), "livestock is not hostile");
        helper.assertFalse(
            EntityType.ENDERMAN.is(CombatTags.RESIDENT_ENEMIES), "endermen are not opted into proactive hostility");
        helper.assertFalse(
            EntityType.ZOMBIFIED_PIGLIN.is(CombatTags.RESIDENT_ENEMIES), "neutral zombie relatives are excluded");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 400)
    public static void aDarkSpiderAcquiresAndBitesAResidentWithNativeAi(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(true, new BlockPos(12, 1, 5));
        Spider spider = arena.monster(EntityType.SPIDER, new BlockPos(4, 1, 5));
        spider.setNoAi(true);
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    spider.getLightLevelDependentMagicValue() < .5F,
                    "the enclosed arena really is dark after lighting settles"))
            .thenExecute(() -> spider.setNoAi(false))
            .thenWaitUntil(
            () -> {
                helper.assertTrue(
                    resident.mob().getHealth() < resident.mob().getMaxHealth(),
                    "a real native spider bite hurts");
                helper.assertTrue(
                    spider.getTarget() == resident.mob(),
                    "the spider selected the resident without a forced target");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 400)
    public static void aBrightSpiderStaysNeutralToResidentsAndPatrollers(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(true, new BlockPos(12, 1, 5));
        Spider spider = arena.monster(EntityType.SPIDER, new BlockPos(4, 1, 5));
        spider.setNoAi(true);
        // Spiders can climb into dim corners. Keep every boundary bright, not just their initial
        // footing.
        for (int x = 0; x < 22; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GLOWSTONE);
                helper.setBlock(new BlockPos(x, 5, z), Blocks.GLOWSTONE);
                if (x == 0 || x == 21 || z == 0 || z == 11) {
                    for (int y = 1; y < 5; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.GLOWSTONE);
                    }
                }
            }
        }
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    spider.getLightLevelDependentMagicValue() >= .5F,
                    "the arena really is bright after lighting settles"))
            .thenExecute(() -> spider.setNoAi(false))
            .thenIdle(120)
            .thenExecute(
            () -> {
                helper.assertTrue(
                    spider.getLightLevelDependentMagicValue() >= .5F,
                    "the spider stayed in a bright arena");
                helper.assertTrue(
                    spider.getTarget() == null,
                    "a bright spider does not acquire an unprovoking resident");
                helper.assertFalse(
                    Threats.hostile(spider, resident.mob()),
                    "patrol does not provoke a bright neutral spider");
                helper.assertValueEqual(
                    resident.mob().getHealth(),
                    resident.mob().getMaxHealth(),
                    "resident stays unharmed");
                spider.setTarget(resident.mob());
                helper.assertTrue(
                    Threats.hostile(spider, resident.mob()),
                    "a spider already fighting can still be defended against");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void aCreeperAcquiresApproachesAndExplodesAtAResident(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        Body resident = arena.resident(true, new BlockPos(12, 1, 5));
        Creeper creeper = arena.monster(EntityType.CREEPER, new BlockPos(4, 1, 5));
        Vec3 start = creeper.position();
        helper.succeedWhen(
            () -> {
                helper.assertTrue(creeper.isRemoved(), "the native creeper actually exploded");
                helper.assertTrue(creeper.position().distanceToSqr(start) > 4, "it approached its acquired target");
                helper.assertTrue(
                    resident.mob().getLastDamageSource() != null
                    && resident.mob().getLastDamageSource().is(DamageTypeTags.IS_EXPLOSION),
                    "the resident was damaged by a real explosion");
            });
    }

    private static final class Arena {
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
                    if (x == 0 || x == 21 || z == 0 || z == 11) {
                        for (int y = 1; y < 5; y++) {
                            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                        }
                    }
                }
            }
            Cleanup.on(helper, this::close);
        }

        Body resident(boolean enrolled, BlockPos at) {
            Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
            body.mob().setNoAi(true);
            place(body.mob(), at);
            if (enrolled) {
                colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
                body.joinColony(colony.id());
            }
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
    }
}
