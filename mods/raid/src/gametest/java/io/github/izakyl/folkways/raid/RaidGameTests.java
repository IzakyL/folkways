package io.github.izakyl.folkways.raid;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.combat.SpottedTargets;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.patrol.PatrolStaffing;
import io.github.izakyl.folkways.plugins.patrol.SentryPosts;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysRaidMod.ID, bus = EventBusSubscriber.Bus.MOD)
    @GameTestHolder("folkways")
    @PrefixGameTestTemplate(false)
public final class RaidGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RaidGameTests.class);
    }

    @BeforeBatch(batch = "raid")
    public static void rules(ServerLevel level) {
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, level.getServer());
    }

    @AfterBatch(batch = "raid")
    public static void restoreRules(ServerLevel level) {
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
    }

    @BeforeBatch(batch = "raid_trigger")
    public static void triggerRules(ServerLevel level) {
        rules(level);
    }

    @AfterBatch(batch = "raid_trigger")
    public static void restoreTriggerRules(ServerLevel level) {
        restoreRules(level);
    }

    static RaidDefinition definition(String entity, int waves) {
        String wave =
            "{\"delayTicks\":0,\"entries\":[{\"entity\":\"minecraft:"
            + entity
            + "\",\"count\":1,\"role\":\"fighter\"}]}";
        return RaidDefinition.parse(
            "{\"schemaVersion\":1,\"waves\":[" + String.join(",", Collections.nCopies(waves, wave)) + "]}");
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void adminCommandsAcceptNamespacedDefinitionsAndRequirePermissionTwo(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        var source = helper.getLevel().getServer().createCommandSourceStack();
        String command = "folkways raids start " + scene.colony.id() + " folkways:siege";
        var parsed = dispatcher.parse(command, source.withPermission(2));
        helper.assertFalse(parsed.getReader().canRead(), "the complete namespaced definition parses");
        helper.assertTrue(parsed.getExceptions().isEmpty(), "the admin command accepts a resource location");
        var forbidden = dispatcher.parse(command, source.withPermission(1));
        helper.assertTrue(forbidden.getReader().canRead(), "a lower-permission source cannot reach the raid action");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void existingColoniesReceiveEnabledRaidsAndFullGrace(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        helper.assertTrue(scene.director.enabled(), "installing optional Raid enables existing colonies");
        helper.assertValueEqual(scene.director.countdown(), (long) RaidConfig.GRACE.get(), "no retroactive raids");
        scene.director.advance(false);
        helper.assertValueEqual(
            scene.director.countdown(), (long) RaidConfig.GRACE.get(), "offline time does not consume grace");
        scene.director.enabled(false);
        RaidDirector restored = new RaidDirector(scene.colony, helper.getLevel().getServer());
        helper.assertFalse(restored.enabled(), "the colony opt-out persists");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
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

    @GameTest(batch = "raid", template = "empty_large")
    public static void pausedRaidDoesNotAdvanceOrRunAttackAi(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("vindicator", 1);
        scene.director.advance(true);
        int elapsed = scene.director.raid().elapsed;
        scene.director.advance(false);
        helper.assertValueEqual(scene.director.raid().elapsed, elapsed, "offline raids do not time out");
        helper.assertTrue(scene.director.loaded().getFirst().isNoAi(), "offline attackers pause");
        scene.director.advance(true);
        helper.assertFalse(scene.director.loaded().getFirst().isNoAi(), "returning members resume native AI");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void restartRestoresSlotsAndSnapshotWithoutDuplicateSpawns(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("zombie", 2);
        scene.director.advance(true);
        Mob original = scene.director.loaded().getFirst();
        CompoundTag entity = new CompoundTag();
        original.save(entity);
        RaidDirector restored = new RaidDirector(scene.colony, helper.getLevel().getServer());
        original.discard();
        restored.advance(true);
        helper.assertValueEqual(restored.loaded().size(), 0, "temporarily missing UUID is not replaced");
        Mob reloaded = (Mob) EntityType.loadEntityRecursive(entity, helper.getLevel(), value -> value);
        helper.getLevel().addFreshEntity(reloaded);
        restored.advance(true);
        helper.assertValueEqual(restored.loaded().size(), 1, "saved entity reassociates by UUID");
        helper.assertValueEqual(restored.raid().wave, 1, "no duplicate wave after restart");
        helper.assertValueEqual(
            restored.raid().definition.snapshot(),
            scene.director.raid().definition.snapshot(),
            "active definitions keep their own snapshot");
        helper.assertValueEqual(
            reloaded.goalSelector.getAvailableGoals().stream()
            .filter(goal -> goal.getGoal().getClass().getName().contains("RaidMembers$Approach"))
            .count(),
            1L,
            "one approach goal after load");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large", timeoutTicks = 340)
    public static void raidersAcquireAndDamageTheirColonysResidents(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("vindicator", 1);
        scene.director.advance(true);
        helper.succeedWhen(
            () -> {
                helper.assertFalse(scene.director.loaded().isEmpty(), "a real raid member spawned");
                Mob raider = scene.director.loaded().getFirst();
                helper.assertTrue(
                    raider.getTarget() == scene.person.mob(), "Raid narrows the existing native resident goal");
                helper.assertTrue(
                    scene.person.mob().getHealth() < scene.person.mob().getMaxHealth(),
                    "native combat inflicts real damage");
            });
    }

    @GameTest(batch = "raid", template = "empty_large", timeoutTicks = 500)
    public static void drawnPatrollerFightsRaidersThroughVanillaDefense(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.person.mob().setNoAi(false);
        scene.person.pack().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        scene.person.mob().getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        scene.person.mob().setHealth(100);
        LoadoutProfiles profiles = LoadoutProfiles.of(scene.colony);
        LoadoutProfile profile = profiles.create("Raid patrol");
        profiles.replace(
            new LoadoutProfile(
            profile.id(),
            profile.name(),
            List.of(LoadoutProfile.Entry.weapon(ItemSpec.of(Items.DIAMOND_SWORD))),
            Optional.empty()));
        profiles.assign(List.of(scene.person.id()), Optional.of(profile.id()));
        scene.colony
            .hold(
            PatrolContent.BEAT,
            new Held.Line(
            List.of(
            WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(14, 0, 8))),
            WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(18, 0, 8))))))
            .orElseThrow();
        scene.simulation = new LaborFixture(scene.colony, helper.getLevel());
        helper.onEachTick(scene.simulation::tick);
        scene.begin("zombie", 1);
        scene.director.advance(true);
        Mob raider = scene.director.loaded().getFirst();
        helper.succeedWhen(
            () ->
            helper.assertTrue(
            raider.getHealth() < raider.getMaxHealth() || !raider.isAlive(),
            "the existing drawn patrol runner really strikes a raid member"));
    }

    @GameTest(batch = "raid", template = "empty_large", timeoutTicks = 500)
    public static void bowSentrySpotsAndKillsAWaveMemberThroughVanillaDefense(GameTestHelper helper) {
        bowSentryDefense(helper, false);
    }

    @GameTest(batch = "raid", template = "empty_large", timeoutTicks = 500)
    public static void staffedBowSentryRespectsProfileSlotsAndKillsAWaveMember(GameTestHelper helper) {
        bowSentryDefense(helper, true);
    }

    private static void bowSentryDefense(GameTestHelper helper, boolean composition) {
        Scene scene = new Scene(helper);
        scene.person.mob().setNoAi(false);
        scene.person.pack().setItem(0, new ItemStack(Items.BOW));
        scene.person.pack().setItem(1, new ItemStack(Items.ARROW, 32));
        LoadoutProfiles profiles = LoadoutProfiles.of(scene.colony);
        LoadoutProfile profile = profiles.create("Raid sentry");
        profiles.replace(
            new LoadoutProfile(
            profile.id(),
            profile.name(),
            List.of(
            LoadoutProfile.Entry.weapon(ItemSpec.of(Items.BOW)),
            LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 32, 8)),
            Optional.empty()));
        profiles.assign(List.of(scene.person.id()), Optional.of(profile.id()));
        Set<WorldPos> cells = new LinkedHashSet<>();
        for (int x = 14; x <= 15; x++) {
            for (int z = 8; z <= 9; z++) {
                cells.add(WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(x, 0, z))));
            }
        }
        var zone = scene.colony.hold(SentryPosts.ZONE, new Held.Area(cells)).orElseThrow();
        helper.assertFalse(
            SentryPosts.of(scene.colony).assign(helper.getLevel(), zone.id(), scene.person.id()).refused(),
            "a ranged profile can take the patrol-owned post");
        if (composition) {
            PatrolStaffing staffing = PatrolStaffing.of(scene.colony);
            UUID slot = staffing.add(helper.getLevel(), zone.id(), profile.id());
            staffing.pin(helper.getLevel(), zone.id(), slot, Optional.of(scene.person.id()));
            helper.assertFalse(SentryPosts.of(scene.colony).legacyResidents().contains(scene.person.id()),
                "adding a composition replaces the legacy posting");
            helper.assertValueEqual(staffing.members(zone.id()), List.of(scene.person.id()),
                "the matching profile fills the pinned sentry slot");
            LoadoutProfile other = profiles.create("Other raid sentry");
            profiles.replace(new LoadoutProfile(other.id(), other.name(),
                profiles.get(profile.id()).orElseThrow().entries(), Optional.empty()));
            profiles.assign(List.of(scene.person.id()), Optional.of(other.id()));
            staffing.tick(helper.getLevel().getServer());
            helper.assertValueEqual(staffing.slots(zone.id()).getFirst().status(), "mismatch",
                "a different ranged profile reports the pinned slot mismatch");
            helper.assertTrue(SentryPosts.of(scene.colony).residents(zone.id()).isEmpty(),
                "a mismatched pin cannot act as a posted sentry");
            helper.assertValueEqual(staffing.shortage(zone.id()).get(profile.id()), 1,
                "the required profile remains visibly short");
            helper.assertTrue(
                SentryPosts.of(scene.colony).assign(helper.getLevel(), zone.id(), scene.person.id()).refused(),
                "legacy posting cannot bypass a configured slot");
            profiles.assign(List.of(scene.person.id()), Optional.of(profile.id()));
            staffing.tick(helper.getLevel().getServer());
            helper.assertValueEqual(SentryPosts.of(scene.colony).residents(zone.id()), List.of(scene.person.id()),
                "restoring the exact profile resumes the pinned sentry");
        }
        scene.begin("zombie", 1);
        scene.director.advance(true);
        Mob raider = scene.director.loaded().getFirst();
        // Keep the target in bow range so this case isolates sentry fire and death accounting.
        raider.setNoAi(true);
        raider.setHealth(4);
        AtomicBoolean spotted = new AtomicBoolean();
        AtomicBoolean fired = new AtomicBoolean();
        scene.simulation = new LaborFixture(scene.colony, helper.getLevel());
        helper.onEachTick(
            () -> {
                scene.simulation.tick();
                spotted.set(
                    spotted.get()
                    || SpottedTargets.of(scene.colony).get(scene.person.mob(), raider.getUUID()).isPresent());
                fired.set(
                    fired.get()
                    || !helper.getLevel()
                    .getEntitiesOfClass(
                    AbstractArrow.class,
                    scene.person.mob().getBoundingBox().inflate(24),
                    arrow -> arrow.getOwner() == scene.person.mob())
                    .isEmpty());
            });
        helper.succeedWhen(
            () -> {
                helper.assertFalse(raider.isAlive(), "the posted sentry kills the real wave member with a bow");
                helper.assertTrue(spotted.get(), "ordinary defender sight reports the raider into shared spotting");
                helper.assertTrue(
                    fired.get() && scene.person.pack().count(Items.ARROW) < 32,
                    "the sentry emits a real projectile and consumes finite profile ammunition");
                helper.assertValueEqual(scene.director.raid().remaining(), 0, "the death event confirms the raid slot");
                scene.director.advance(true);
                helper.assertTrue(
                    scene.director.raid().phase == RaidInstance.Phase.VICTORY,
                    "the confirmed defender kill completes the final wave");
            });
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void raidMembersNeverJoinVanillaVillageRaids(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("pillager", 1);
        scene.director.advance(true);
        Pillager pillager = (Pillager) scene.director.loaded().getFirst();
        helper.assertFalse(pillager.canJoinRaid(), "colony raiders cannot be stolen by village raids");
        scene.director.stop();
        helper.assertTrue(pillager.isRemoved(), "admin cancellation clears loaded members");
        helper.assertTrue(scene.director.raid().phase == RaidInstance.Phase.CANCELLED, "cancellation is no victory");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void dataDefinitionsAreBoundedAndLastResidentLossIsDefeat(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        boolean rejected = false;
        try {
            RaidDefinition.parse(
                "{\"schemaVersion\":1,\"waves\":[{\"delayTicks\":0,\"entries\":[{\"entity\":"
                + "\"minecraft:wither\",\"count\":1}]}]}");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "unbudgeted entity destruction cannot enter the definition pool");
        scene.begin("zombie", 1);
        scene.director.advance(true);
        scene.person.mob().kill();
        helper.runAfterDelay(
            2,
            () -> {
                scene.director.advance(true);
                helper.assertTrue(
                    scene.director.raid().phase == RaidInstance.Phase.PLUNDER,
                    "last held resident dying begins defeat plunder");
                scene.director.raid().plunderTicks = RaidConfig.PLUNDER_DURATION.get();
                scene.director.advance(true);
                helper.assertTrue(
                    scene.director.raid().phase == RaidInstance.Phase.DEFEAT, "plunder timeout records defeat");
                helper.succeed();
            });
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void breachesRespectServerAndDatapackCapsAndNeverOpenContainers(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin(
            RaidDefinition.parse(
            definition("zombie", 1)
            .snapshot()
            .replace("\"waves\":", "\"limits\":{\"brokenBlocks\":2},\"waves\":")));
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        BlockPos first = mob.blockPosition().east();
        helper.getLevel().setBlockAndUpdate(first, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
        helper.assertTrue(RaidEdits.breakBlock(mob, first), "normal stone and deepslate can be breached");
        BlockPos chest = first.east();
        helper.getLevel().setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        helper.assertFalse(RaidEdits.breakBlock(mob, chest), "direct breach never opens a block entity");
        helper.getLevel().setBlockAndUpdate(first, Blocks.OBSIDIAN.defaultBlockState());
        helper.assertFalse(RaidEdits.breakBlock(mob, first), "obsidian is outside the hardness cap");
        helper.getLevel().setBlockAndUpdate(first, Blocks.STONE.defaultBlockState());
        helper.assertTrue(RaidEdits.breakBlock(mob, first), "second block fits the shared cap");
        helper.getLevel().setBlockAndUpdate(first, Blocks.STONE.defaultBlockState());
        helper.assertFalse(RaidEdits.breakBlock(mob, first), "datapack cap stops the next edit");
        helper.assertValueEqual(scene.director.raid().broken, 2, "damage budget is exact for successful breaks");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void mobGriefingAndCancelledBreakEventsBlockRaidEdits(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("zombie", 1);
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        BlockPos at = mob.blockPosition().east();
        helper.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
        var rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        boolean before = rule.get();
        try {
            rule.set(false, helper.getLevel().getServer());
            helper.assertFalse(RaidEdits.breakBlock(mob, at), "mobGriefing=false forbids breaking");
        } finally {
            rule.set(before, helper.getLevel().getServer());
        }
        Consumer<BlockEvent.BreakEvent> deny =
            event -> {
                if (event.getPos().equals(at)) {
                    event.setCanceled(true);
                }
            };
        NeoForge.EVENT_BUS.addListener(deny);
        try {
            helper.assertFalse(RaidEdits.breakBlock(mob, at), "NeoForge block-break cancellation is authoritative");
        } finally {
            NeoForge.EVENT_BUS.unregister(deny);
        }
        helper.assertValueEqual(scene.director.raid().broken, 0, "refusals do not spend damage budget");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void realCreeperExplosionsDamageBuildingsWithinTheRaidCap(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin(
            RaidDefinition.parse(
            definition("creeper", 1)
            .snapshot()
            .replace("\"waves\":", "\"limits\":{\"brokenBlocks\":3},\"waves\":")));
        scene.director.advance(true);
        Mob creeper = scene.director.loaded().getFirst();
        creeper.setNoAi(true);
        BlockPos at = helper.absolutePos(new BlockPos(7, 1, 8));
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                helper.getLevel().setBlockAndUpdate(at.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        var building =
            BlockPos.betweenClosedStream(at.offset(-5, -1, -5), at.offset(5, 4, 5))
            .map(BlockPos::immutable)
            .filter(pos -> !helper.getLevel().getBlockState(pos).isAir())
            .toList();
        helper.getLevel()
            .explode(creeper, at.getX() + .5, at.getY() + 1, at.getZ() + .5, 4, Level.ExplosionInteraction.MOB);
        long missing = building.stream().filter(pos -> helper.getLevel().getBlockState(pos).isAir()).count();
        helper.assertTrue(
            missing > 0 && missing <= 3, "native creeper explosion makes real missing blocks within the cap");
        helper.assertTrue(scene.director.raid().broken <= 3, "shared cap includes explosion blocks");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large", timeoutTicks = 360)
    public static void aBreacherPhysicallyOpensAStoneWall(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        for (int z = 1; z < 15; z++) {
            for (int y = 1; y < 5; y++) {
                helper.setBlock(new BlockPos(11, y, z), Blocks.STONE_BRICKS);
            }
        }
        scene.begin(RaidDefinition.parse(definition("zombie", 1).snapshot().replace("fighter", "breacher")));
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        BlockPos near = helper.absolutePos(new BlockPos(9, 1, 8));
        mob.moveTo(near.getX() + .5, near.getY(), near.getZ() + .5);
        helper.succeedWhen(
            () -> {
                helper.assertTrue(scene.director.raid().broken > 0, "the breacher spends time attacking the wall");
                helper.assertTrue(
                    IntStream.range(1, 5)
                    .anyMatch(
                    y -> helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(11, y, 8))).isAir()),
                    "the wall really loses a block");
            });
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void realFireIsAttributedPersistedAndBoundedPerWave(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin(
            RaidDefinition.parse(
            definition("zombie", 1)
            .snapshot()
            .replace("\"waves\":", "\"limits\":{\"fireSourcesPerWave\":1},\"waves\":")));
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        BlockPos at = mob.blockPosition().east();
        helper.getLevel().setBlockAndUpdate(at.below(), Blocks.NETHERRACK.defaultBlockState());
        var rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        boolean before = rule.get();
        try {
            rule.set(true, helper.getLevel().getServer());
            helper.assertTrue(AttributedFires.ignite(mob, at), "the raider places real fire");
            helper.assertTrue(
                helper.getLevel().getBlockState(at).is(Blocks.FIRE), "damage control can scan Blocks.FIRE");
            helper.assertTrue(
                AttributedFires.of(helper.getLevel()).attributed(at), "the source records raid provenance");
            helper.assertFalse(AttributedFires.ignite(mob, at.north()), "per-wave source cap applies");
            CompoundTag saved =
                AttributedFires.of(helper.getLevel()).save(new CompoundTag(), helper.getLevel().registryAccess());
            helper.assertTrue(
                AttributedFires.load(saved, helper.getLevel().registryAccess()).attributed(at),
                "provenance persists independently of the colony");
        } finally {
            rule.set(before, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void fireGamerulesAndPlacementCancellationPreventIgnition(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("zombie", 1);
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        BlockPos at = mob.blockPosition().east();
        var fire = helper.getLevel().getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        var grief = helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        boolean oldFire = fire.get();
        boolean oldGrief = grief.get();
        Consumer<BlockEvent.EntityPlaceEvent> deny =
            event -> {
                if (event.getPos().equals(at)) {
                    event.setCanceled(true);
                }
            };
        try {
            fire.set(false, helper.getLevel().getServer());
            helper.assertFalse(AttributedFires.ignite(mob, at), "doFireTick=false prevents ignition");
            fire.set(true, helper.getLevel().getServer());
            grief.set(false, helper.getLevel().getServer());
            helper.assertFalse(AttributedFires.ignite(mob, at), "mobGriefing=false prevents ignition");
            grief.set(true, helper.getLevel().getServer());
            NeoForge.EVENT_BUS.addListener(deny);
            helper.assertFalse(AttributedFires.ignite(mob, at), "NeoForge placement cancellation is authoritative");
            helper.assertTrue(
                helper.getLevel().getBlockState(at).isAir(), "cancelled fire placement restores the actual cell");
            helper.assertValueEqual(scene.director.raid().fires, 0, "denied fire spends no source allowance");
        } finally {
            NeoForge.EVENT_BUS.unregister(deny);
            fire.set(oldFire, helper.getLevel().getServer());
            grief.set(oldGrief, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void attributedVanillaFireKeepsItsBurnBudgetAfterTheRaidEnds(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin(
            RaidDefinition.parse(
            definition("zombie", 1)
            .snapshot()
            .replace("\"waves\":", "\"limits\":{\"burnedBlocks\":2,\"fireSpreadBlocks\":2},\"waves\":")));
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        BlockPos at = helper.absolutePos(new BlockPos(8, 1, 8));
        mob.moveTo(at.getX() - 1, at.getY(), at.getZ());
        helper.getLevel().setBlockAndUpdate(at.below(), Blocks.NETHERRACK.defaultBlockState());
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (x != 0 || z != 0) {
                    helper.getLevel().setBlockAndUpdate(at.offset(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
                }
            }
        }
        var rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        boolean before = rule.get();
        try {
            rule.set(true, helper.getLevel().getServer());
            helper.assertTrue(AttributedFires.ignite(mob, at), "source fire is placed");
            scene.director.stop();
            var random = RandomSource.create(42);
            for (int tick = 0; tick < 512; tick++) {
                helper.getLevel().getBlockState(at).tick(helper.getLevel(), at, random);
            }
            int burned = AttributedFires.of(helper.getLevel()).burned(scene.director.raid().id);
            helper.assertTrue(
                burned > 0 && burned <= 2,
                "vanilla fire burns real timber within its persistent budget after withdrawal");
            for (BlockPos pos : BlockPos.betweenClosed(at.offset(-2, 0, -2), at.offset(2, 4, 2))) {
                if (helper.getLevel().getBlockState(pos).is(Blocks.FIRE)) {
                    helper.assertTrue(
                        AttributedFires.of(helper.getLevel()).attributed(pos), "every descendant keeps provenance");
                }
            }
        } finally {
            rule.set(before, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void plunderRemovesRealGoodsPreservesComponentsAndDropsCargoOnDeath(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("zombie", 1);
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        WorldPos at = scene.store(new BlockPos(12, 1, 3));
        var container = scene.colony.store(helper.getLevel(), at).orElseThrow();
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 64);
        diamonds.set(DataComponents.CUSTOM_NAME, Component.literal("recovery"));
        container.setItem(0, diamonds);
        scene.director.plunder();
        helper.assertFalse(RaidPlunder.take(scene.director, mob, at), "looting cannot remotely deduct stock");
        BlockPos beside = at.block(helper.getLevel()).west();
        mob.moveTo(beside.getX() + .5, beside.getY(), beside.getZ() + .5);
        helper.assertTrue(RaidPlunder.take(scene.director, mob, at), "actual adjacent looter takes its share");
        helper.assertValueEqual(
            container.getItem(0).getCount(), 52, "twenty percent of the actual stack is removed, rounded down");
        helper.assertTrue(RaidPlunder.carrying(mob), "real goods belong to the looter until death or escape");
        mob.hurt(helper.getLevel().damageSources().genericKill(), Float.MAX_VALUE);
        helper.runAfterDelay(
            2,
            () -> {
                var dropped =
                    helper.getLevel().getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(3)).stream()
                    .map(ItemEntity::getItem)
                    .filter(stack -> stack.is(Items.DIAMOND))
                    .toList();
                helper.assertValueEqual(
                    dropped.stream().mapToInt(ItemStack::getCount).sum(),
                    12,
                    "killing a looter drops the actual removed goods exactly once");
                helper.assertTrue(
                    dropped.stream().allMatch(stack -> stack.has(DataComponents.CUSTOM_NAME)),
                    "item components survive theft and recovery");
                helper.assertTrue(
                    scene.director.raid().plunder.getCompound(0).getBoolean("dropped"),
                    "the report marks goods dropped for recovery");
                helper.succeed();
            });
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void plunderPrefersValuablesAndSkipsLockedOrForbiddenStores(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("zombie", 1);
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        WorldPos at = scene.store(new BlockPos(12, 1, 3));
        var container = scene.colony.store(helper.getLevel(), at).orElseThrow();
        container.setItem(0, new ItemStack(Items.DIRT, 64));
        container.setItem(1, new ItemStack(Items.DIAMOND, 64));
        scene.director.plunder();
        helper.assertValueEqual(
            scene.director.raid().plunder.getCompound(0).getInt("slot"),
            1,
            "the precious item tag wins over ordinary materials");
        BlockPos beside = at.block(helper.getLevel()).west();
        mob.moveTo(beside.getX() + .5, beside.getY(), beside.getZ() + .5);
        var block = helper.getLevel().getBlockEntity(at.block(helper.getLevel()));
        CompoundTag locked = block.saveWithoutMetadata(helper.getLevel().registryAccess());
        locked.putString("Lock", "sealed");
        block.loadWithComponents(locked, helper.getLevel().registryAccess());
        helper.assertFalse(RaidPlunder.take(scene.director, mob, at), "native locked containers cannot be looted");
        locked.remove("Lock");
        block.loadWithComponents(locked, helper.getLevel().registryAccess());
        scene.colony.rule(
            FolkwaysRaidMod.RAIDS, helper.getLevel().dimension(), List.of(new StoreRule.Access(at, false, true)));
        helper.assertFalse(
            RaidPlunder.take(scene.director, mob, at), "registered withdrawal protection remains authoritative");
        helper.assertValueEqual(container.getItem(1).getCount(), 64, "denied looting removes nothing");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large", timeoutTicks = 300)
    public static void lootersWalkToARegisteredStoreBeforeTakingGoods(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("zombie", 1);
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        WorldPos at = scene.store(new BlockPos(15, 1, 3));
        var container = scene.colony.store(helper.getLevel(), at).orElseThrow();
        container.setItem(0, new ItemStack(Items.EMERALD, 64));
        scene.person.mob().setNoGravity(true);
        BlockPos remote = helper.absolutePos(new BlockPos(40, 1, 8));
        scene.person.mob().moveTo(remote.getX(), remote.getY(), remote.getZ());
        scene.director.plunder();
        helper.succeedWhen(
            () -> {
                helper.assertTrue(
                    container.getItem(0).getCount() < 64, "native movement reaches and loots real registered storage");
                helper.assertTrue(
                    mob.distanceToSqr(Vec3.atCenterOf(at.block(helper.getLevel()))) <= 16,
                    "goods move only at the store");
                helper.assertTrue(RaidPlunder.carrying(mob), "walking looter carries the removed items");
            });
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void holdingAnUndefendedStoreBeginsPhysicalPlunder(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("zombie", 1);
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        WorldPos at = scene.store(mob.blockPosition().east().subtract(helper.absolutePos(BlockPos.ZERO)));
        scene.colony.store(helper.getLevel(), at).orElseThrow().setItem(0, new ItemStack(Items.DIAMOND, 64));
        scene.person.mob().setNoGravity(true);
        scene.person.mob().moveTo(mob.getX() + 40, mob.getY(), mob.getZ());
        for (int tick = 0; tick < RaidConfig.STORE_HOLD.get(); tick += 20) {
            scene.director.advance(true);
        }
        helper.assertTrue(
            scene.director.raid().phase == RaidInstance.Phase.PLUNDER,
            "holding an undefended registered store triggers defeat");
        helper.assertValueEqual(
            scene.colony.store(helper.getLevel(), at).orElseThrow().getItem(0).getCount(),
            64,
            "defeat itself never deducts abstract goods");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void mobGriefingFalseBlocksExplosionsAndRealStoreWithdrawal(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("creeper", 1);
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        WorldPos at = scene.store(mob.blockPosition().east().subtract(helper.absolutePos(BlockPos.ZERO)));
        var container = scene.colony.store(helper.getLevel(), at).orElseThrow();
        container.setItem(0, new ItemStack(Items.DIAMOND, 64));
        scene.director.plunder();
        var rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        boolean before = rule.get();
        try {
            rule.set(false, helper.getLevel().getServer());
            helper.assertFalse(
                RaidPlunder.take(scene.director, mob, at), "mobGriefing=false forbids inventory mutation");
            var blocks =
                BlockPos.betweenClosedStream(
                mob.blockPosition().offset(-4, -1, -4), mob.blockPosition().offset(4, 4, 4))
                .map(BlockPos::immutable)
                .collect(Collectors.toMap(pos -> pos, pos -> helper.getLevel().getBlockState(pos)));
            helper.getLevel().explode(mob, mob.getX(), mob.getY() + 1, mob.getZ(), 4, Level.ExplosionInteraction.MOB);
            helper.assertTrue(
                blocks.entrySet().stream()
                .allMatch(entry -> helper.getLevel().getBlockState(entry.getKey()).equals(entry.getValue())),
                "mobGriefing=false leaves every block intact after an actual explosion");
            helper.assertValueEqual(
                container.getItem(0).getCount(), 64, "protected goods remain in their real container");
        } finally {
            rule.set(before, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void colonyPageAuthorityIsRecheckedForEverySettingAndReading(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        var keeper = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "raid_keeper"));
        var intruder = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "raid_intruder"));
        Charters.keep(scene.colony, Charter.founded("raid", keeper.getUUID()));
        keeper.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(scene.colony.id()));
        intruder.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(scene.colony.id()));
        helper.assertFalse(
            scene.director.setEnabled(intruder, false), "forged current-colony state grants no authority");
        helper.assertTrue(scene.director.setEnabled(keeper, false), "a keeper can opt out of raids");
        helper.assertFalse(
            scene.director.reading(keeper).isEmpty(), "authorized reading carries translated components");
        helper.assertTrue(scene.director.reading(intruder).isEmpty(), "foreign readers receive no raid details");
        Charters.keep(scene.colony, Charter.founded("revoked", intruder.getUUID()));
        helper.assertFalse(
            scene.director.setEnabled(keeper, true), "revoking membership invalidates an existing page action");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void looterCargoSurvivesReloadWithoutTakingTheSameShareAgain(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("zombie", 1);
        scene.director.advance(true);
        Mob original = scene.director.loaded().getFirst();
        WorldPos at = scene.store(original.blockPosition().east().subtract(helper.absolutePos(BlockPos.ZERO)));
        var container = scene.colony.store(helper.getLevel(), at).orElseThrow();
        container.setItem(0, new ItemStack(Items.DIAMOND, 64));
        scene.director.plunder();
        helper.assertTrue(RaidPlunder.take(scene.director, original, at), "the initial real withdrawal succeeds");
        CompoundTag saved = new CompoundTag();
        original.save(saved);
        original.discard();
        Mob loaded = (Mob) EntityType.loadEntityRecursive(saved, helper.getLevel(), entity -> entity);
        helper.getLevel().addFreshEntity(loaded);
        helper.assertTrue(RaidPlunder.carrying(loaded), "actual cargo is serialized on the looter");
        helper.assertFalse(
            RaidPlunder.take(scene.director, loaded, at), "the saved manifest marks that share already taken");
        helper.assertValueEqual(container.getItem(0).getCount(), 52, "reload never repeats the deduction");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void raidersIgnoreCloserResidentsFromAnotherColony(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("vindicator", 1);
        scene.director.advance(true);
        scene.person.mob().getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        scene.person.mob().setHealth(1000);
        Colony other = Colonies.mint(helper.getLevel().getServer());
        Body foreign = PersonBody.RESIDENT.get().create(helper.getLevel());
        foreign.mob().setNoAi(true);
        Mob raider = scene.director.loaded().getFirst();
        foreign.mob().moveTo(raider.getX() + 1, raider.getY(), raider.getZ());
        helper.getLevel().addFreshEntity(foreign.mob());
        other.hold(PersonContent.ID, Held.Entity.of(foreign.mob())).orElseThrow();
        foreign.joinColony(other.id());
        helper.runAfterDelay(
            80,
            () -> {
                try {
                    helper.assertValueEqual(
                        foreign.mob().getHealth(),
                        foreign.mob().getMaxHealth(),
                        "a closer foreign resident is never a strategic target");
                    helper.assertTrue(
                        raider.getTarget() == scene.person.mob(),
                        "the existing native goal still acquires its own colony's resident");
                } finally {
                    foreign.mob().discard();
                    other.raze();
                }
                helper.succeed();
            });
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void doubleDoorsNeedBothCellsAuthorizedBeforeBreaking(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("zombie", 1);
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        BlockPos at = mob.blockPosition().east();
        helper.getLevel().setBlockAndUpdate(at, Blocks.OAK_DOOR.defaultBlockState());
        helper.getLevel()
            .setBlockAndUpdate(
            at.above(),
            Blocks.OAK_DOOR
            .defaultBlockState()
            .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        Consumer<BlockEvent.BreakEvent> deny =
            event -> {
                if (event.getPos().equals(at.above())) {
                    event.setCanceled(true);
                }
            };
        NeoForge.EVENT_BUS.addListener(deny);
        try {
            helper.assertFalse(RaidEdits.breakBlock(mob, at), "denying the other half prevents the whole door break");
        } finally {
            NeoForge.EVENT_BUS.unregister(deny);
        }
        helper.assertTrue(helper.getLevel().getBlockState(at).is(Blocks.OAK_DOOR), "the lower half remains intact");
        helper.assertTrue(RaidEdits.breakBlock(mob, at), "both authorized halves may break through vanilla updates");
        helper.assertValueEqual(scene.director.raid().broken, 2, "both removed door cells consume budget exactly once");
        helper.succeed();
    }

    @GameTest(batch = "raid", template = "empty_large")
    public static void burningRaiderArrowsCannotIgniteProtectedTnt(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.begin("skeleton", 1);
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst();
        BlockPos at = mob.blockPosition().east();
        helper.getLevel().setBlockAndUpdate(at, Blocks.TNT.defaultBlockState());
        var arrow = new Arrow(helper.getLevel(), mob, new ItemStack(Items.ARROW), null);
        arrow.igniteForSeconds(30);
        RaidEdits.projectileHit(
            helper.getLevel().getBlockState(at),
            helper.getLevel(),
            new BlockHitResult(Vec3.atCenterOf(at), Direction.WEST, at, false),
            arrow);
        helper.assertTrue(
            helper.getLevel().getBlockState(at).is(Blocks.TNT),
            "protected TNT cannot become an unbudgeted secondary explosion");
        helper.assertValueEqual(scene.director.raid().broken, 0, "denied block reactions spend no budget");
        arrow.discard();
        helper.succeed();
    }

    @GameTest(batch = "raid_trigger", template = "empty_large")
    public static void automaticTriggerWaitsForGracePresenceAndDuskOrDefersSafely(GameTestHelper helper) {
        int grace = RaidConfig.GRACE.get();
        int residents = RaidConfig.MIN_RESIDENTS.get();
        long day = helper.getLevel().getDayTime();
        try {
            RaidConfig.GRACE.set(20);
            RaidConfig.MIN_RESIDENTS.set(1);
            Scene scene = new Scene(helper);
            helper.getLevel().setDayTime(1000);
            scene.director.advance(false);
            helper.assertValueEqual(scene.director.countdown(), 20L, "offline grace does not elapse");
            scene.director.advance(true);
            helper.assertFalse(scene.director.active(), "eligibility alone does not start a daytime raid");
            helper.getLevel().setDayTime(12500);
            scene.director.advance(true);
            helper.assertTrue(
                scene.director.active() || scene.director.countdown() == 1200,
                "dusk either begins a warning or postpones a failed outside spawn search");
            if (scene.director.active()) {
                helper.assertTrue(
                    scene.director.raid().phase == RaidInstance.Phase.WARNING,
                    "the automatic trigger begins with a warning");
                helper.assertTrue(
                    scene.director.raid().origin.cell().distSqr(scene.director.raid().target.cell()) >= 24 * 24,
                    "automatic raids never fall back to spawning inside the colony");
            }
        } finally {
            RaidConfig.GRACE.set(grace);
            RaidConfig.MIN_RESIDENTS.set(residents);
            helper.getLevel().setDayTime(day);
        }
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
            this.helper = helper;
            colony = Colonies.mint(helper.getLevel().getServer());
            director = RaidDirector.of(colony).orElseThrow(() -> new IllegalStateException("raid service missing"));
            for (int x = 0; x < 22; x++) {
                for (int z = 0; z < 16; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                    helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
                    if (x == 0 || x == 21 || z == 0 || z == 15) {
                        for (int y = 1; y < 5; y++) {
                            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                        }
                    }
                }
            }
            person = PersonBody.RESIDENT.get().create(helper.getLevel());
            person.mob().setNoAi(true);
            BlockPos at = helper.absolutePos(new BlockPos(15, 1, 8));
            person.mob().moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            helper.getLevel().addFreshEntity(person.mob());
            colony.hold(PersonContent.ID, Held.Entity.of(person.mob()))
                .orElseThrow(() -> new IllegalStateException("resident enrollment refused"));
            person.joinColony(colony.id());
            helper.testInfo.addListener(this);
        }

        void begin(String entity, int waves) {
            begin(definition(entity, waves));
        }

        void begin(RaidDefinition definition) {
            director.begin(
                definition,
                WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(15, 1, 8))),
                WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(6, 1, 8))),
                42L);
            director.raid().wait = 0;
        }

        WorldPos store(BlockPos cell) {
            helper.setBlock(cell, Blocks.BARREL);
            WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(cell));
            colony.stores(FolkwaysRaidMod.RAIDS, helper.getLevel().dimension(), List.of(at));
            return at;
        }

        void close() {
            if (simulation != null) {
                simulation.close();
            }
            director.loaded().forEach(Entity::discard);
            person.mob().discard();
            extra.forEach(Entity::discard);
            colony.raze();
        }

        public void testStructureLoaded(GameTestInfo info) {
        }

        public void testPassed(GameTestInfo info, GameTestRunner runner) {
            close();
        }

        public void testFailed(GameTestInfo info, GameTestRunner runner) {
            close();
        }

        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {
        }
    }
}
