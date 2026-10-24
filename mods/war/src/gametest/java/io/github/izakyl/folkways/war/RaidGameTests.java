package io.github.izakyl.folkways.war;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysWarMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class RaidGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(RaidGameTests.class); }

    @net.minecraft.gametest.framework.BeforeBatch(batch = "war")
    public static void rules(net.minecraft.server.level.ServerLevel level) {
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(true, level.getServer());
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = "war")
    public static void restoreRules(net.minecraft.server.level.ServerLevel level) {
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
    }

    static RaidDefinition definition(String entity, int waves) {
        String wave = "{\"delayTicks\":0,\"entries\":[{\"entity\":\"minecraft:" + entity + "\",\"count\":1,\"role\":\"fighter\"}]}";
        return RaidDefinition.parse("{\"schemaVersion\":1,\"waves\":[" + String.join(",", java.util.Collections.nCopies(waves, wave)) + "]}");
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void existingColoniesReceiveEnabledRaidsAndFullGrace(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        helper.assertTrue(scene.director.enabled(), "installing optional War enables existing colonies");
        helper.assertValueEqual(scene.director.countdown(), (long) WarConfig.GRACE.get(), "no retroactive raids");
        scene.director.advance(false);
        helper.assertValueEqual(scene.director.countdown(), (long) WarConfig.GRACE.get(), "offline time does not consume grace");
        scene.director.enabled(false);
        RaidDirector restored = new RaidDirector(scene.colony, helper.getLevel().getServer());
        helper.assertFalse(restored.enabled(), "the colony opt-out persists");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void warningWavesAndVictoryUseConfirmedDeaths(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("zombie", 2);
        scene.director.raid().wait = 40;
        scene.director.advance(true);
        helper.assertValueEqual(scene.director.loaded().size(), 0, "warning precedes spawning");
        scene.director.advance(true);
        helper.assertValueEqual(scene.director.raid().wave, 1, "first wave appears after warning");
        Mob first = scene.director.loaded().getFirst();
        first.discard();
        scene.director.advance(true);
        helper.assertValueEqual(scene.director.raid().wave, 1, "missing entity is not a kill and never respawns");
        helper.assertValueEqual(scene.director.raid().remaining(), 1, "unloaded slot remains outstanding");
        scene.director.died(first.getUUID());
        scene.director.advance(true);
        helper.assertValueEqual(scene.director.raid().wave, 2, "confirmed death advances the wave");
        scene.director.loaded().getFirst().kill();
        scene.director.advance(true);
        helper.assertTrue(scene.director.raid().phase == RaidInstance.Phase.VICTORY, "all waves cleared once");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void pausedRaidDoesNotAdvanceOrRunAttackAi(GameTestHelper helper) {
        Scene scene = new Scene(helper); scene.begin("vindicator", 1); scene.director.advance(true);
        int elapsed = scene.director.raid().elapsed;
        scene.director.advance(false);
        helper.assertValueEqual(scene.director.raid().elapsed, elapsed, "offline raids do not time out");
        helper.assertTrue(scene.director.loaded().getFirst().isNoAi(), "offline attackers pause");
        scene.director.advance(true);
        helper.assertFalse(scene.director.loaded().getFirst().isNoAi(), "returning members resume native AI");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void restartRestoresSlotsAndSnapshotWithoutDuplicateSpawns(GameTestHelper helper) {
        Scene scene = new Scene(helper); scene.begin("zombie", 2); scene.director.advance(true);
        Mob original = scene.director.loaded().getFirst();
        CompoundTag entity = new CompoundTag(); original.save(entity);
        RaidDirector restored = new RaidDirector(scene.colony, helper.getLevel().getServer());
        original.discard();
        restored.advance(true);
        helper.assertValueEqual(restored.loaded().size(), 0, "temporarily missing UUID is not replaced");
        Mob reloaded = (Mob) EntityType.loadEntityRecursive(entity, helper.getLevel(), value -> value);
        helper.getLevel().addFreshEntity(reloaded);
        restored.advance(true);
        helper.assertValueEqual(restored.loaded().size(), 1, "saved entity reassociates by UUID");
        helper.assertValueEqual(restored.raid().wave, 1, "no duplicate wave after restart");
        helper.assertValueEqual(restored.raid().definition.snapshot(), scene.director.raid().definition.snapshot(), "active definitions keep their own snapshot");
        helper.assertValueEqual(reloaded.goalSelector.getAvailableGoals().stream().filter(goal -> goal.getGoal().getClass().getName().contains("RaidMembers$Approach")).count(), 1L, "one approach goal after load");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large", timeoutTicks = 340)
    public static void raidersAcquireAndDamageTheirColonysResidents(GameTestHelper helper) {
        Scene scene = new Scene(helper); scene.begin("vindicator", 1); scene.director.advance(true);
        helper.succeedWhen(() -> {
            helper.assertFalse(scene.director.loaded().isEmpty(), "a real raid member spawned");
            Mob raider = scene.director.loaded().getFirst();
            helper.assertTrue(raider.getTarget() == scene.person.mob(), "War narrows the existing native resident goal");
            helper.assertTrue(scene.person.mob().getHealth() < scene.person.mob().getMaxHealth(), "native combat inflicts real damage");
        });
    }

    @GameTest(batch = "war", template = "empty_large", timeoutTicks = 360)
    public static void guardsFightRaidersThroughExistingResidentCommands(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.person.mob().setNoAi(false); scene.person.pack().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        scene.person.mob().getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
        scene.person.mob().setHealth(100);
        ResidentCommands.of(scene.colony).guard(scene.person, WorldPos.of(helper.getLevel(), scene.person.mob().blockPosition()), new net.minecraft.world.phys.Vec3(1, 0, 0));
        LaborFixture simulation = new LaborFixture(scene.colony, helper.getLevel()); scene.simulation = simulation;
        helper.onEachTick(simulation::tick);
        scene.begin("zombie", 1); scene.director.advance(true);
        Mob raider = scene.director.loaded().getFirst();
        helper.succeedWhen(() -> helper.assertTrue(raider.getHealth() < raider.getMaxHealth() || !raider.isAlive(), "the existing guard runner really strikes a raid member"));
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void raidMembersNeverJoinVanillaVillageRaids(GameTestHelper helper) {
        Scene scene = new Scene(helper); scene.begin("pillager", 1); scene.director.advance(true);
        Pillager pillager = (Pillager) scene.director.loaded().getFirst();
        helper.assertFalse(pillager.canJoinRaid(), "colony raiders cannot be stolen by village raids");
        scene.director.stop();
        helper.assertTrue(pillager.isRemoved(), "admin cancellation clears loaded members");
        helper.assertTrue(scene.director.raid().phase == RaidInstance.Phase.CANCELLED, "cancellation is no victory");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void dataDefinitionsAreBoundedAndLastResidentLossIsDefeat(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        boolean rejected = false;
        try { RaidDefinition.parse("{\"schemaVersion\":1,\"waves\":[{\"delayTicks\":0,\"entries\":[{\"entity\":\"minecraft:wither\",\"count\":1}]}]}"); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "unbudgeted entity destruction cannot enter the definition pool");
        scene.begin("zombie", 1); scene.director.advance(true); scene.person.mob().kill();
        helper.runAfterDelay(2, () -> {
            scene.director.advance(true);
            helper.assertTrue(scene.director.raid().phase == RaidInstance.Phase.DEFEAT, "last held resident dying loses the raid");
            helper.succeed();
        });
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void breachesRespectServerAndDatapackCapsAndNeverOpenContainers(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin(RaidDefinition.parse(definition("zombie", 1).snapshot().replace("\"waves\":", "\"limits\":{\"brokenBlocks\":2},\"waves\":")));
        scene.director.advance(true); Mob mob = scene.director.loaded().getFirst();
        BlockPos first = mob.blockPosition().east();
        helper.getLevel().setBlockAndUpdate(first, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
        helper.assertTrue(WarEdits.breakBlock(mob, first), "normal stone and deepslate can be breached");
        BlockPos chest = first.east(); helper.getLevel().setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        helper.assertFalse(WarEdits.breakBlock(mob, chest), "direct breach never opens a block entity");
        helper.getLevel().setBlockAndUpdate(first, Blocks.OBSIDIAN.defaultBlockState());
        helper.assertFalse(WarEdits.breakBlock(mob, first), "obsidian is outside the hardness cap");
        helper.getLevel().setBlockAndUpdate(first, Blocks.STONE.defaultBlockState());
        helper.assertTrue(WarEdits.breakBlock(mob, first), "second block fits the shared cap");
        helper.getLevel().setBlockAndUpdate(first, Blocks.STONE.defaultBlockState());
        helper.assertFalse(WarEdits.breakBlock(mob, first), "datapack cap stops the next edit");
        helper.assertValueEqual(scene.director.raid().broken, 2, "damage budget is exact for successful breaks");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void mobGriefingAndCancelledBreakEventsBlockRaidEdits(GameTestHelper helper) {
        Scene scene = new Scene(helper); scene.begin("zombie", 1); scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst(); BlockPos at = mob.blockPosition().east();
        helper.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
        var rule = helper.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING);
        boolean before = rule.get();
        try { rule.set(false, helper.getLevel().getServer()); helper.assertFalse(WarEdits.breakBlock(mob, at), "mobGriefing=false forbids breaking"); }
        finally { rule.set(before, helper.getLevel().getServer()); }
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.BreakEvent> deny = event -> { if (event.getPos().equals(at)) { event.setCanceled(true); } };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(deny);
        try { helper.assertFalse(WarEdits.breakBlock(mob, at), "NeoForge block-break cancellation is authoritative"); }
        finally { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(deny); }
        helper.assertValueEqual(scene.director.raid().broken, 0, "refusals do not spend damage budget");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void realCreeperExplosionsDamageBuildingsWithinTheRaidCap(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin(RaidDefinition.parse(definition("creeper", 1).snapshot().replace("\"waves\":", "\"limits\":{\"brokenBlocks\":3},\"waves\":")));
        scene.director.advance(true); Mob creeper = scene.director.loaded().getFirst(); creeper.setNoAi(true);
        BlockPos at = helper.absolutePos(new BlockPos(7, 1, 8));
        for (int x = -2; x <= 2; x++) { for (int z = -2; z <= 2; z++) { helper.getLevel().setBlockAndUpdate(at.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState()); } }
        var building = BlockPos.betweenClosedStream(at.offset(-5, -1, -5), at.offset(5, 4, 5)).map(BlockPos::immutable)
            .filter(pos -> !helper.getLevel().getBlockState(pos).isAir()).toList();
        helper.getLevel().explode(creeper, at.getX() + .5, at.getY() + 1, at.getZ() + .5, 4, net.minecraft.world.level.Level.ExplosionInteraction.MOB);
        long missing = building.stream().filter(pos -> helper.getLevel().getBlockState(pos).isAir()).count();
        helper.assertTrue(missing > 0 && missing <= 3, "native creeper explosion makes real missing blocks within the cap");
        helper.assertTrue(scene.director.raid().broken <= 3, "shared cap includes explosion blocks");
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large", timeoutTicks = 360)
    public static void aBreacherPhysicallyOpensAStoneWall(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        for (int z = 1; z < 15; z++) { for (int y = 1; y < 5; y++) { helper.setBlock(new BlockPos(11, y, z), Blocks.STONE_BRICKS); } }
        scene.begin(RaidDefinition.parse(definition("zombie", 1).snapshot().replace("fighter", "breacher")));
        scene.director.advance(true); Mob mob = scene.director.loaded().getFirst();
        BlockPos near = helper.absolutePos(new BlockPos(9, 1, 8)); mob.moveTo(near.getX() + .5, near.getY(), near.getZ() + .5);
        helper.succeedWhen(() -> {
            helper.assertTrue(scene.director.raid().broken > 0, "the breacher spends time attacking the wall");
            helper.assertTrue(java.util.stream.IntStream.range(1, 5).anyMatch(y -> helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(11, y, 8))).isAir()), "the wall really loses a block");
        });
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void realFireIsAttributedPersistedAndBoundedPerWave(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin(RaidDefinition.parse(definition("zombie", 1).snapshot().replace("\"waves\":", "\"limits\":{\"fireSourcesPerWave\":1},\"waves\":")));
        scene.director.advance(true); Mob mob = scene.director.loaded().getFirst();
        BlockPos at = mob.blockPosition().east(); helper.getLevel().setBlockAndUpdate(at.below(), Blocks.NETHERRACK.defaultBlockState());
        var rule = helper.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK); boolean before = rule.get();
        try {
            rule.set(true, helper.getLevel().getServer());
            helper.assertTrue(AttributedFires.ignite(mob, at), "the raider places real fire");
            helper.assertTrue(helper.getLevel().getBlockState(at).is(Blocks.FIRE), "damage control can scan Blocks.FIRE");
            helper.assertTrue(AttributedFires.of(helper.getLevel()).attributed(at), "the source records raid provenance");
            helper.assertFalse(AttributedFires.ignite(mob, at.north()), "per-wave source cap applies");
            CompoundTag saved = AttributedFires.of(helper.getLevel()).save(new CompoundTag(), helper.getLevel().registryAccess());
            helper.assertTrue(AttributedFires.load(saved, helper.getLevel().registryAccess()).attributed(at), "provenance persists independently of the colony");
        } finally { rule.set(before, helper.getLevel().getServer()); }
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void fireGamerulesAndPlacementCancellationPreventIgnition(GameTestHelper helper) {
        Scene scene = new Scene(helper); scene.begin("zombie", 1); scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst(); BlockPos at = mob.blockPosition().east();
        var fire = helper.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK);
        var grief = helper.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING);
        boolean oldFire = fire.get(); boolean oldGrief = grief.get();
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> deny = event -> { if (event.getPos().equals(at)) { event.setCanceled(true); } };
        try {
            fire.set(false, helper.getLevel().getServer()); helper.assertFalse(AttributedFires.ignite(mob, at), "doFireTick=false prevents ignition");
            fire.set(true, helper.getLevel().getServer()); grief.set(false, helper.getLevel().getServer());
            helper.assertFalse(AttributedFires.ignite(mob, at), "mobGriefing=false prevents ignition");
            grief.set(true, helper.getLevel().getServer()); net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(deny);
            helper.assertFalse(AttributedFires.ignite(mob, at), "NeoForge placement cancellation is authoritative");
            helper.assertTrue(helper.getLevel().getBlockState(at).isAir(), "cancelled fire placement restores the actual cell");
            helper.assertValueEqual(scene.director.raid().fires, 0, "denied fire spends no source allowance");
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(deny); fire.set(oldFire, helper.getLevel().getServer()); grief.set(oldGrief, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    @GameTest(batch = "war", template = "empty_large")
    public static void attributedVanillaFireKeepsItsBurnBudgetAfterTheRaidEnds(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin(RaidDefinition.parse(definition("zombie", 1).snapshot().replace("\"waves\":", "\"limits\":{\"burnedBlocks\":2,\"fireSpreadBlocks\":2},\"waves\":")));
        scene.director.advance(true); Mob mob = scene.director.loaded().getFirst();
        BlockPos at = helper.absolutePos(new BlockPos(8, 1, 8)); mob.moveTo(at.getX() - 1, at.getY(), at.getZ());
        helper.getLevel().setBlockAndUpdate(at.below(), Blocks.NETHERRACK.defaultBlockState());
        for (int x = -2; x <= 2; x++) { for (int z = -2; z <= 2; z++) { if (x != 0 || z != 0) { helper.getLevel().setBlockAndUpdate(at.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState()); } } }
        var rule = helper.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK); boolean before = rule.get();
        try {
            rule.set(true, helper.getLevel().getServer()); helper.assertTrue(AttributedFires.ignite(mob, at), "source fire is placed"); scene.director.stop();
            var random = net.minecraft.util.RandomSource.create(42);
            for (int tick = 0; tick < 512; tick++) { helper.getLevel().getBlockState(at).tick(helper.getLevel(), at, random); }
            int burned = AttributedFires.of(helper.getLevel()).burned(scene.director.raid().id);
            helper.assertTrue(burned > 0 && burned <= 2, "vanilla fire burns real timber within its persistent budget after withdrawal");
            for (BlockPos pos : BlockPos.betweenClosed(at.offset(-2, 0, -2), at.offset(2, 4, 2))) {
                if (helper.getLevel().getBlockState(pos).is(Blocks.FIRE)) { helper.assertTrue(AttributedFires.of(helper.getLevel()).attributed(pos), "every descendant keeps provenance"); }
            }
        } finally { rule.set(before, helper.getLevel().getServer()); }
        helper.succeed();
    }

    static final class Scene implements GameTestListener {
        final GameTestHelper helper;
        final Colony colony;
        final RaidDirector director;
        final Body person;
        LaborFixture simulation;
        final List<Entity> extra = new ArrayList<>();
        Scene(GameTestHelper helper) {
            this.helper = helper; colony = Colonies.mint(helper.getLevel().getServer()); director = RaidDirector.of(colony).orElseThrow();
            for (int x = 0; x < 22; x++) {
                for (int z = 0; z < 16; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                    helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
                    if (x == 0 || x == 21 || z == 0 || z == 15) { for (int y = 1; y < 5; y++) { helper.setBlock(new BlockPos(x, y, z), Blocks.STONE); } }
                }
            }
            person = PersonBody.RESIDENT.get().create(helper.getLevel()); person.mob().setNoAi(true);
            BlockPos at = helper.absolutePos(new BlockPos(15, 1, 8)); person.mob().moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            colony.hold(PersonContent.ID, Held.Entity.of(person.mob())).orElseThrow(); person.joinColony(colony.id());
            helper.getLevel().addFreshEntity(person.mob()); helper.testInfo.addListener(this);
        }
        void begin(String entity, int waves) { begin(definition(entity, waves)); }
        void begin(RaidDefinition definition) {
            director.begin(definition, WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(15, 1, 8))),
                WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(6, 1, 8))), 42L);
            director.raid().wait = 0;
        }
        void close() {
            if (simulation != null) { simulation.close(); }
            director.loaded().forEach(Entity::discard); person.mob().discard(); extra.forEach(Entity::discard); colony.raze();
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
