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
        void begin(String entity, int waves) {
            director.begin(definition(entity, waves), WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(15, 1, 8))),
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
