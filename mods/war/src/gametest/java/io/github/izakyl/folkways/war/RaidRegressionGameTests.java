package io.github.izakyl.folkways.war;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysWarMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class RaidRegressionGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(RaidRegressionGameTests.class); }

    @GameTest(batch = "war", template = "empty_large")
    public static void completedBreachSurvivesTicksBeforeTheSelectorStopsIt(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper);
        for (int z = 1; z < 15; z++) { for (int y = 1; y < 5; y++) { helper.setBlock(new BlockPos(11, y, z), Blocks.STONE_BRICKS); } }
        scene.begin(RaidDefinition.parse(RaidGameTests.definition("zombie", 1).snapshot().replace("fighter", "breacher")));
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst(); mob.setNoAi(true);
        BlockPos near = helper.absolutePos(new BlockPos(9, 1, 8)); mob.moveTo(near.getX() + .5, near.getY(), near.getZ() + .5);
        var goal = new WrappedGoal(1, new RaidBreachGoal(mob));
        helper.assertTrue(goal.canUse(), "the real wall provides a breach target"); goal.start();
        for (int tick = 0; tick < 120; tick++) { goal.tick(); }
        helper.assertTrue(goal.isRunning(), "the selector has not yet called stop");
        helper.assertFalse(goal.canContinueToUse(), "a completed breach asks the selector to stop");
        goal.tick(); goal.tick();
        helper.assertValueEqual(scene.director.raid().broken, 1, "extra running ticks neither crash nor repeat the edit");
        goal.stop(); goal.tick();
        helper.assertFalse(goal.isRunning(), "the selector owns the final stop");
        helper.succeed();
    }
    @GameTest(batch = "war", template = "empty_large")
    public static void waterConversionsKeepZombieAndHuskIdentityAndRealCargo(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper);
        scene.begin(RaidDefinition.parse("{\"schemaVersion\":1,\"waves\":[{\"delayTicks\":0,\"entries\":[{\"entity\":\"minecraft:zombie\",\"count\":1},{\"entity\":\"minecraft:husk\",\"count\":1}]}]}"));
        scene.director.advance(true); var members = scene.director.loaded();
        var store = scene.store(new BlockPos(8, 1, 3));
        var container = scene.colony.store(helper.getLevel(), store).orElseThrow();
        container.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 64));
        container.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 64));
        scene.director.plunder();
        for (Mob original : members) {
            BlockPos at = store.block(helper.getLevel()).west(); original.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            helper.assertTrue(RaidPlunder.take(scene.director, original, store), "a real share is carried before conversion");
            var saved = new net.minecraft.nbt.CompoundTag(); original.save(saved); saved.putInt("DrownedConversionTime", 0); original.discard();
            Mob loaded = (Mob) net.minecraft.world.entity.EntityType.loadEntityRecursive(saved, helper.getLevel(), entity -> entity);
            loaded.setNoAi(false); helper.getLevel().addFreshEntity(loaded); loaded.tick();
            helper.assertFalse(loaded.isRemoved(), "the native underwater conversion does not replace a marked member");
            helper.assertValueEqual(loaded.getType(), original.getType(), "zombies and husks keep their type and UUID");
            helper.assertTrue(RaidPlunder.carrying(loaded), "conversion attempts retain the actual cargo");
            helper.assertTrue(scene.director.owns(loaded), "the original member slot remains authoritative");
        }
        helper.assertValueEqual(scene.director.raid().remaining(), 2, "conversion creates no unresolved replacement slots");
        helper.assertValueEqual(container.getItem(0).getCount(), 52, "conversion never withdraws a second share");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void powderSnowConversionKeepsTheMarkedSkeletonAndDoesNotBlockOrdinaryMobs(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper); scene.begin("skeleton", 1); scene.director.advance(true);
        Mob original = scene.director.loaded().getFirst();
        var saved = new net.minecraft.nbt.CompoundTag(); original.save(saved); saved.putInt("StrayConversionTime", 0); original.discard();
        var loaded = (net.minecraft.world.entity.monster.Skeleton) net.minecraft.world.entity.EntityType.loadEntityRecursive(saved, helper.getLevel(), entity -> entity);
        loaded.setNoAi(false); loaded.setIsInPowderSnow(true); helper.getLevel().addFreshEntity(loaded); loaded.tick();
        helper.assertFalse(loaded.isRemoved(), "the native snow conversion cannot detach a raid skeleton");
        helper.assertTrue(scene.director.owns(loaded), "the skeleton keeps its registered UUID");
        var ordinary = net.minecraft.world.entity.EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(net.neoforged.neoforge.event.EventHooks.canLivingConvert(ordinary, net.minecraft.world.entity.EntityType.DROWNED, timer -> { }), "ordinary conversions are unchanged");
        ordinary.discard(); helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void loadedLeaderZombiesCannotCallUnregisteredReinforcements(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper); scene.begin("zombie", 1); scene.director.advance(true);
        Mob original = scene.director.loaded().getFirst();
        var attribute = net.minecraft.world.entity.ai.attributes.Attributes.SPAWN_REINFORCEMENTS_CHANCE;
        helper.assertValueEqual(original.getAttributeValue(attribute), 0D, "new raid zombies have no reinforcement chance");
        original.getAttribute(attribute).setBaseValue(1);
        original.getAttribute(attribute).addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("leader_zombie_bonus"), 1, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        var saved = new net.minecraft.nbt.CompoundTag(); original.save(saved); original.discard();
        Mob loaded = (Mob) net.minecraft.world.entity.EntityType.loadEntityRecursive(saved, helper.getLevel(), entity -> entity); helper.getLevel().addFreshEntity(loaded);
        helper.assertValueEqual(loaded.getAttributeValue(attribute), 0D, "join clears saved leader modifiers as well as the base chance");
        loaded.setTarget(scene.person.mob()); loaded.hurt(helper.getLevel().damageSources().mobAttack(scene.person.mob()), 1);
        helper.assertValueEqual(scene.director.raid().remaining(), 1, "damage creates no untracked raid members");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void pausedCreeperTicksFreezeTheLitFuseAndResumeItWithoutDamageWhilePaused(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper); scene.begin("creeper", 1); scene.director.advance(true);
        var creeper = (net.minecraft.world.entity.monster.Creeper) scene.director.loaded().getFirst();
        creeper.moveTo(scene.person.mob().getX() - 2, scene.person.mob().getY(), scene.person.mob().getZ());
        creeper.ignite(); creeper.tick(); creeper.tick(); float swelling = creeper.getSwelling(1);
        scene.director.advance(false); float health = scene.person.mob().getHealth();
        for (int tick = 0; tick < 80; tick++) {
            var event = net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.EntityTickEvent.Pre(creeper));
            helper.assertTrue(event.isCanceled(), "pause cancels the complete entity tick before the fuse advances");
            if (!event.isCanceled()) { creeper.tick(); }
        }
        helper.assertFalse(creeper.isRemoved(), "the paused creeper cannot explode");
        helper.assertValueEqual(creeper.getSwelling(1), swelling, "a lit fuse retains its exact progress");
        helper.assertValueEqual(scene.person.mob().getHealth(), health, "pause allows no explosion damage to residents");
        scene.director.advance(true);
        var resumed = net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.EntityTickEvent.Pre(creeper));
        helper.assertFalse(resumed.isCanceled(), "the member resumes its native tick"); creeper.tick();
        helper.assertTrue(creeper.getSwelling(1) > swelling, "resuming continues the saved fuse"); helper.succeed();
    }

    @net.minecraft.gametest.framework.BeforeBatch(batch = "war_spawn")
    public static void spawnRules(net.minecraft.server.level.ServerLevel level) { RaidGameTests.rules(level); }
    @net.minecraft.gametest.framework.AfterBatch(batch = "war_spawn")
    public static void restoreSpawnRules(net.minecraft.server.level.ServerLevel level) { RaidGameTests.restoreRules(level); }

    @GameTest(batch = "war_spawn", template = "empty_large")
    public static void nativeBabyInitializationNeverCreatesOrClaimsChickenMounts(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper); scene.begin("zombie", 1);
        var chicken = net.minecraft.world.entity.EntityType.CHICKEN.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(6, 1, 8)); chicken.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        helper.getLevel().addFreshEntity(chicken); scene.extra.add(chicken);
        long babySeed = 0;
        while (net.minecraft.util.RandomSource.create(babySeed).nextFloat() >= .05F) { babySeed++; }
        var before = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Chicken.class, chicken.getBoundingBox().inflate(16)).stream().map(net.minecraft.world.entity.Entity::getUUID).collect(java.util.stream.Collectors.toSet());
        for (int attempt = 0; attempt < 128; attempt++) {
            var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(helper.getLevel()); zombie.moveTo(chicken.position()); zombie.getRandom().setSeed(babySeed);
            RaidMembers.mark(zombie, scene.colony.id(), scene.director.raid().id, "fighter");
            var data = (net.minecraft.world.entity.monster.Zombie.ZombieGroupData) RaidMembers.prepareSpawn(zombie, helper.getLevel());
            helper.assertTrue(zombie.isBaby() && !data.canSpawnJockey, "native initialization keeps babies while disabling jockey creation");
            helper.assertFalse(zombie.isPassenger(), "no existing chicken is claimed"); zombie.discard();
        }
        var after = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Chicken.class, chicken.getBoundingBox().inflate(16)).stream().map(net.minecraft.world.entity.Entity::getUUID).collect(java.util.stream.Collectors.toSet());
        helper.assertValueEqual(after, before, "native initialization creates no extra chickens");
        helper.assertFalse(chicken.isChickenJockey(), "an existing chicken keeps its original state"); helper.succeed();
    }

    @GameTest(batch = "war_spawn", template = "empty_large")
    public static void aFailedWaveCannotLeaveJockeyMountsBehind(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper);
        for (int x = 2; x <= 10; x++) { for (int z = 4; z <= 12; z++) { helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE); } }
        scene.begin(RaidDefinition.parse("{\"schemaVersion\":1,\"waves\":[{\"delayTicks\":0,\"entries\":[{\"entity\":\"minecraft:zombie\",\"count\":32},{\"entity\":\"minecraft:ravager\",\"count\":1}]}]}"));
        long before = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Chicken.class, scene.person.mob().getBoundingBox().inflate(24)).size();
        scene.director.advance(true);
        helper.assertValueEqual(scene.director.raid().wave, 0, "the ceiling rejects the ravager after preparing zombies");
        helper.assertTrue(scene.director.loaded().isEmpty(), "a failed wave adds no member entities");
        helper.assertValueEqual((long) helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Chicken.class, scene.person.mob().getBoundingBox().inflate(24)).size(), before, "preparation and rollback cannot leak mounts");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void legacyJockeysDismountWithoutChangingAnUnownedChicken(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper); scene.begin("zombie", 1); scene.director.advance(true);
        var zombie = (net.minecraft.world.entity.monster.Zombie) scene.director.loaded().getFirst(); zombie.setBaby(true);
        var chicken = net.minecraft.world.entity.EntityType.CHICKEN.create(helper.getLevel()); chicken.moveTo(zombie.position()); chicken.setChickenJockey(true);
        helper.getLevel().addFreshEntity(chicken); scene.extra.add(chicken); zombie.startRiding(chicken, true);
        RaidMembers.joined(new net.neoforged.neoforge.event.entity.EntityJoinLevelEvent(zombie, helper.getLevel()));
        scene.director.advance(false);
        helper.assertFalse(zombie.isPassenger(), "legacy raiders cannot be carried by an untracked mount during pause");
        helper.assertTrue(chicken.isAlive() && chicken.isChickenJockey(), "unknown prior ownership does not authorize deleting or rewriting the chicken");
        helper.succeed();
    }

}
