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

    private static io.github.izakyl.folkways.core.api.terms.WorldPos giveCargo(RaidGameTests.Scene scene, Mob mob) {
        var at = scene.store(new BlockPos(8, 1, 3));
        var container = scene.colony.store(scene.helper.getLevel(), at).orElseThrow();
        var diamonds = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 64);
        diamonds.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("raid recovery")); container.setItem(0, diamonds);
        scene.director.plunder(); BlockPos beside = at.block(scene.helper.getLevel()).west(); mob.moveTo(beside.getX() + .5, beside.getY(), beside.getZ() + .5);
        scene.helper.assertTrue(RaidPlunder.take(scene.director, mob, at), "a looter carries an actual warehouse share"); return at;
    }

    private static int droppedDiamonds(GameTestHelper helper, Mob mob) {
        return helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, mob.getBoundingBox().inflate(4)).stream()
            .map(net.minecraft.world.entity.item.ItemEntity::getItem).filter(stack -> stack.is(net.minecraft.world.item.Items.DIAMOND)).mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void cancelledUnloadedCargoReturnsBeforeAiEvenAfterANewRaidAndSaveReload(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper); scene.begin("zombie", 1); scene.director.advance(true);
        Mob original = scene.director.loaded().getFirst(); giveCargo(scene, original);
        var attack = scene.director.raid().id; var saved = new net.minecraft.nbt.CompoundTag(); original.save(saved); original.discard(); scene.director.stop();
        helper.assertTrue(scene.director.pendingReturn(attack, original.getUUID()), "cancel records unresolved cargo by old raid and member UUID");
        scene.begin("zombie", 1);
        var restored = new RaidDirector(scene.colony, helper.getLevel().getServer());
        helper.assertTrue(restored.pendingReturn(attack, original.getUUID()), "starting another raid and reloading retain the old pending return");
        Mob loaded = (Mob) net.minecraft.world.entity.EntityType.loadEntityRecursive(saved, helper.getLevel(), entity -> entity);
        loaded.tickCount = 1; loaded.setNoAi(false); helper.getLevel().addFreshEntity(loaded); scene.extra.add(loaded);
        loaded.goalSelector.getAvailableGoals().stream().filter(goal -> goal.getGoal().getClass().getName().contains("RaidMembers$Approach")).forEach(goal -> goal.canUse());
        helper.assertTrue(!loaded.isRemoved() && RaidPlunder.carrying(loaded), "goal eligibility cannot discard an unsettled entity");
        var event = net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.EntityTickEvent.Pre(loaded));
        helper.assertTrue(event.isCanceled() && loaded.isRemoved(), "the first entity tick settles cargo before any native AI");
        helper.assertValueEqual(droppedDiamonds(helper, loaded), 12, "cancelled cargo is dropped once without waiting twenty ticks");
        helper.assertFalse(scene.director.pendingReturn(attack, original.getUUID()), "settlement acknowledges the durable pending return");
        RaidMembers.retire(loaded);
        helper.assertValueEqual(droppedDiamonds(helper, loaded), 12, "repeated retirement cannot duplicate recovery goods");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void versionOneCancelledSavesMigratePendingReturnsWithoutResettingSettings(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper); scene.begin("zombie", 1); scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst(); giveCargo(scene, mob); var attack = scene.director.raid().id; mob.discard(); scene.director.enabled(false);
        var legacy = scene.colony.kept(FolkwaysWarMod.RAIDS).copy(); legacy.putInt("schemaVersion", 1); legacy.remove("pendingReturns");
        long next = legacy.getLong("next"); scene.colony.keep(FolkwaysWarMod.RAIDS, legacy);
        var restored = new RaidDirector(scene.colony, helper.getLevel().getServer());
        helper.assertTrue(restored.pendingReturn(attack, mob.getUUID()), "version one cancellation migrates unresolved members into pending returns");
        helper.assertFalse(restored.enabled(), "migration preserves the opt-out");
        helper.assertValueEqual(scene.colony.kept(FolkwaysWarMod.RAIDS).getLong("next"), next, "migration preserves the cooldown");
        helper.assertValueEqual(scene.colony.kept(FolkwaysWarMod.RAIDS).getInt("schemaVersion"), 2, "the migrated ledger is explicitly versioned");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void plunderTimeoutDropsCargoThatHasNotReachedTheExit(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper); scene.begin("zombie", 1); scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst(); giveCargo(scene, mob);
        scene.director.raid().plunderTicks = WarConfig.PLUNDER_DURATION.get() - 20; scene.director.advance(true);
        helper.assertTrue(scene.director.raid().phase == RaidInstance.Phase.DEFEAT && mob.isRemoved(), "the bounded plunder ends with forced cleanup");
        helper.assertFalse(scene.director.raid().escaped.contains(mob.getUUID()), "timeout never invents a successful escape");
        helper.assertValueEqual(droppedDiamonds(helper, mob), 12, "forced cleanup leaves the actual cargo recoverable at the carrier");
        helper.assertTrue(scene.director.raid().plunder.getCompound(0).getBoolean("dropped"), "the report records the forced recovery drop"); helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void plunderTimeoutAlsoRetainsReturnsForMissingCarriers(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper); scene.begin("zombie", 1); scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst(); giveCargo(scene, mob); var attack = scene.director.raid().id;
        var saved = new net.minecraft.nbt.CompoundTag(); mob.save(saved); mob.discard();
        scene.director.raid().plunderTicks = WarConfig.PLUNDER_DURATION.get() - 20; scene.director.advance(true);
        helper.assertTrue(scene.director.pendingReturn(attack, mob.getUUID()), "forced cleanup preserves missing carriers rather than deleting their cargo");
        Mob loaded = (Mob) net.minecraft.world.entity.EntityType.loadEntityRecursive(saved, helper.getLevel(), entity -> entity); helper.getLevel().addFreshEntity(loaded); scene.extra.add(loaded);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.EntityTickEvent.Pre(loaded));
        helper.assertValueEqual(droppedDiamonds(helper, loaded), 12, "a returning timed-out carrier drops its real cargo");
        helper.assertFalse(scene.director.pendingReturn(attack, loaded.getUUID()), "the return is settled exactly once"); helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void onlyCarriersThatReachTheExitKeepStolenGoods(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper); scene.begin("zombie", 1); scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst(); giveCargo(scene, mob);
        BlockPos exit = scene.director.raid().origin.block(helper.getLevel()); mob.moveTo(exit.getX() + .5, exit.getY(), exit.getZ() + .5); scene.director.escaped(mob);
        helper.assertTrue(scene.director.raid().escaped.contains(mob.getUUID()) && mob.isRemoved(), "reaching the exit records an actual escape");
        helper.assertFalse(RaidPlunder.carrying(mob), "escaped goods are settled before the carrier is discarded");
        scene.director.advance(true); helper.assertValueEqual(droppedDiamonds(helper, mob), 0, "successful escape does not create a recovery drop"); helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void removedStoresDoNotBlockLaterPlunder(GameTestHelper helper) { skipInvalidStore(helper, "removed"); }
    @GameTest(batch = "war", template = "empty_large")
    public static void unregisteredStoresDoNotBlockLaterPlunder(GameTestHelper helper) { skipInvalidStore(helper, "unregistered"); }
    @GameTest(batch = "war", template = "empty_large")
    public static void lockedStoresDoNotBlockLaterPlunder(GameTestHelper helper) { skipInvalidStore(helper, "locked"); }
    @GameTest(batch = "war", template = "empty_large")
    public static void forbiddenStoresDoNotBlockLaterPlunder(GameTestHelper helper) { skipInvalidStore(helper, "forbidden"); }

    private static void skipInvalidStore(GameTestHelper helper, String reason) {
        var scene = new RaidGameTests.Scene(helper); scene.begin("zombie", 1); scene.director.advance(true); Mob mob = scene.director.loaded().getFirst();
        var first = scene.store(new BlockPos(8, 1, 3)); var second = scene.store(new BlockPos(12, 1, 3));
        scene.colony.stores(FolkwaysWarMod.RAIDS, helper.getLevel().dimension(), java.util.List.of(first, second));
        scene.colony.store(helper.getLevel(), first).orElseThrow().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 64));
        var remaining = scene.colony.store(helper.getLevel(), second).orElseThrow(); remaining.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT, 64)); scene.director.plunder();
        helper.assertValueEqual(RaidPlunder.target(scene.director, mob), first, "the valuable store is selected before it becomes invalid");
        switch (reason) {
            case "removed" -> helper.setBlock(new BlockPos(8, 1, 3), Blocks.AIR);
            case "unregistered" -> scene.colony.stores(FolkwaysWarMod.RAIDS, helper.getLevel().dimension(), java.util.List.of(second));
            case "locked" -> {
                var block = helper.getLevel().getBlockEntity(first.block(helper.getLevel())); var tag = block.saveWithoutMetadata(helper.getLevel().registryAccess());
                tag.putString("Lock", "sealed"); block.loadWithComponents(tag, helper.getLevel().registryAccess());
            }
            case "forbidden" -> scene.colony.rule(FolkwaysWarMod.RAIDS, helper.getLevel().dimension(), java.util.List.of(new io.github.izakyl.folkways.core.api.terms.StoreRule.Access(first, false, true)));
            default -> throw new IllegalArgumentException(reason);
        }
        helper.assertFalse(RaidPlunder.take(scene.director, mob, first), "a stale in-flight target cannot withdraw stock");
        helper.assertValueEqual(RaidPlunder.target(scene.director, mob), second, "invalid entries cannot starve a later accessible store");
        BlockPos beside = second.block(helper.getLevel()).west(); mob.moveTo(beside.getX() + .5, beside.getY(), beside.getZ() + .5);
        helper.assertTrue(RaidPlunder.take(scene.director, mob, second), "looting continues at the next actual container");
        helper.assertValueEqual(remaining.getItem(0).getCount(), 52, "only the valid store supplies the next share"); helper.succeed();
    }

}
