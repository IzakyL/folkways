package io.github.izakyl.folkways.plugins.patrol;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.WorkProgress;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroups;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentSupportGameTests {
    private static final ResourceLocation WORK = ResourceLocation.fromNamespaceAndPath("folkways", "support_test_work");
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(ResidentSupportGameTests.class); }

    @GameTest(template = "empty_large", batch = "resident_support", timeoutTicks = 650)
    public static void anOrdinaryArmedWorkerSupportsAnActuallyHurtColleagueThenResumesTheSameJob(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        var worker = f.person(3, 5, true);
        var victim = f.person(12, 5, false); victim.setNoAi(true);
        var enemy = f.zombie(13, 5);
        Job job = f.job(worker);
        int[] resumed = {0};
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(job.ticks >= 5, "the ordinary worker actually performs its original tool job"))
            .thenExecute(() -> f.hurt(victim, enemy))
            .thenWaitUntil(() -> helper.assertTrue(enemy.getHealth() < enemy.getMaxHealth(),
                "the uncommanded armed colleague physically approaches and hits the real attacker: " + f.reading(worker)))
            .thenExecute(() -> {
                helper.assertTrue(enemy.getLastDamageSource() != null && enemy.getLastDamageSource().getEntity() == worker,
                    "this helper's actual weapon causes the damage");
                helper.assertTrue(worker.position().distanceToSqr(job.home) > 4, "support involved real travel away from the original job");
                helper.assertTrue(f.commands.current(worker.id()).isEmpty(), "support never manufactures a player order");
                helper.assertTrue(job.releases >= 1, "the same runner releases its original tool work before fighting");
                resumed[0] = job.ticks; enemy.discard();
            })
            .thenWaitUntil(() -> helper.assertTrue(job.ticks >= resumed[0] + 5 && job.working
                    && Reach.standingIn(worker, Stances.at(job.at)), "the same job resumes at its real worksite after support ends"))
            .thenExecute(() -> {
                helper.assertValueEqual(worker.pack().count(Items.IRON_AXE), 1, "the real borrowed work tool returns uniquely");
                helper.assertValueEqual(worker.pack().count(Items.IRON_SWORD), 1, "support returns its actual weapon once");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "resident_support", timeoutTicks = 500)
    public static void aNewMoveCancelsSupportAndTheCapturedMoveActuallyCompletes(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        var worker = f.person(3, 5, true);
        var victim = f.person(12, 5, false); victim.setNoAi(true);
        var enemy = f.zombie(13, 5);
        ResidentCommands.Order[] moved = {null};
        float[] health = {60};
        helper.startSequence()
            .thenExecute(() -> f.hurt(victim, enemy))
            .thenWaitUntil(() -> helper.assertTrue(worker.hasWeaponSession(), "support owns the real hand before the player replaces it"))
            .thenExecute(() -> {
                health[0] = enemy.getHealth();
                moved[0] = f.commands.move(worker, f.at(3, 12));
            })
            .thenWaitUntil(() -> helper.assertTrue(moved[0].status() == ResidentCommands.Status.ARRIVED
                    && Reach.standingIn(worker, Stances.at(moved[0].destination())), "the new MOVE really reaches its destination"))
            .thenIdle(30)
            .thenExecute(() -> {
                helper.assertValueEqual(enemy.getHealth(), health[0], "the revoked support lease cannot discharge another hit");
                helper.assertFalse(worker.hasWeaponSession(), "the interrupted hand loan is closed");
                helper.assertTrue(f.commands.current(worker.id()).isEmpty(), "completed movement releases control without restoring support");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "resident_support", timeoutTicks = 450)
    public static void supportRespectsRangeWeaknessOtherColoniesSelectionAndExplicitCommands(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        var victim = f.person(1, 1, false); victim.setNoAi(true);
        var enemy = f.zombie(2, 1);
        var far = f.person(20, 20, true);
        var weak = f.person(5, 5, true); weak.setHealth(5);
        var unarmed = f.person(4, 6, false);
        Colony other = Colonies.mint(f.level.getServer()); f.otherColonies.add(other);
        var foreign = f.person(other, 4, 3, true);
        var selected = f.person(6, 3, true);
        helper.assertTrue(f.selection.select(f.player, List.of(selected.id())), "the player actually reserves the selected worker");
        helper.assertTrue(f.selection.bindGroup(f.player, 0), "a real shortcut retains that ownership");
        var stopped = f.person(6, 5, true);
        var stop = f.commands.hold(stopped, f.at(6, 5)).allowDangerRetreat();
        var moving = f.person(6, 7, true);
        var move = f.commands.move(moving, f.at(3, 9));
        var retreating = f.person(6, 9, true);
        var retreat = f.commands.retreat(retreating, f.at(3, 10));
        var guard = f.person(20, 2, true);
        var guardOrder = f.commands.guard(guard, f.at(20, 2), new Vec3(-1, 0, 0)).deliberateGuard();
        var attacking = f.person(3, 18, true);
        var assignedEnemy = f.zombie(14, 20);
        var attack = f.commands.attack(List.of(attacking), assignedEnemy, f.player).getFirst();
        helper.startSequence().thenExecute(() -> f.hurt(victim, enemy))
            .thenWaitUntil(() -> helper.assertTrue(move.status() == ResidentCommands.Status.ARRIVED
                    && Reach.standingIn(moving, Stances.at(move.destination()))
                    && retreat.status() == ResidentCommands.Status.HOLDING
                    && Reach.standingIn(retreating, Stances.at(retreat.destination()))
                    && assignedEnemy.getHealth() < assignedEnemy.getMaxHealth(),
                "existing MOVE/RETREAT complete real travel and explicit ATTACK really hits its own target; MOVE="
                    + move.status() + "/" + move.failure() + "/" + f.reading(moving)
                    + "; RETREAT=" + retreat.status() + "/" + retreat.failure() + "/" + f.reading(retreating)
                    + "; ATTACK=" + attack.status() + "/" + attack.attackEnd() + "/" + attack.failure()
                    + "/" + f.reading(attacking) + "; assignedHP=" + assignedEnemy.getHealth()))
            .thenIdle(80)
            .thenExecute(() -> {
                helper.assertValueEqual(enemy.getHealth(), enemy.getMaxHealth(), "none of the ineligible residents joins the fight");
                for (var body : List.of(far, weak, unarmed, foreign, selected, stopped, moving, retreating, guard)) {
                    helper.assertFalse(body.hasWeaponSession(), "an ineligible worker cannot take a support hand loan: " + f.reading(body));
                }
                helper.assertTrue(f.commands.current(stopped.id()).orElse(null) == stop
                        && f.commands.current(retreating.id()).orElse(null) == retreat
                        && f.commands.current(guard.id()).orElse(null) == guardOrder,
                    "support preserves STOP, RETREAT and the original GUARD handles");
                helper.assertTrue(f.selection.reserved(selected.id()), "the shortcut-only resident stays under player ownership");
                helper.assertTrue(f.commands.current(attacking.id()).orElse(null) == attack
                        || attack.attackEnd() == ResidentCommands.AttackEnd.TARGET_DEFEATED,
                    "support cannot retarget an explicit player attack to the colony alarm");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "resident_support", timeoutTicks = 300)
    public static void playerFriendlyAndUnangryNeutralDamageDoesNotPublishAnAlarm(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        var worker = f.person(3, 5, true);
        var victim = f.person(10, 5, false); victim.setNoAi(true);
        var friend = f.person(11, 5, false); friend.setNoAi(true);
        var neutral = f.spawn(EntityType.ZOMBIFIED_PIGLIN, 12, 5);
        Job job = f.job(worker);
        int[] before = {0};
        float[] actualDamage = {0};
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(job.ticks >= 5, "the real worker has started ordinary labour"))
            .thenExecute(() -> {
                float health = victim.getHealth();
                helper.assertTrue(victim.hurt(f.level.damageSources().playerAttack(f.player), 1)
                    && victim.getHealth() < health, "real player damage is delivered");
                victim.invulnerableTime = 0; f.hurt(victim, friend);
                victim.invulnerableTime = 0; f.hurt(victim, neutral);
                actualDamage[0] = health - victim.getHealth();
                before[0] = job.ticks;
            }).thenIdle(100)
            .thenExecute(() -> {
                helper.assertTrue(actualDamage[0] >= 3, "all three sources dealt actual damage before legitimate natural recovery");
                helper.assertTrue(job.working && job.ticks > before[0] + 20 && job.releases == 0,
                    "player, friendly and non-hostile neutral hits never interrupt the colleague's real work");
                helper.assertValueEqual(friend.getHealth(), friend.getMaxHealth(), "friendly bodies are not made village enemies");
                helper.assertValueEqual(neutral.getHealth(), neutral.getMaxHealth(), "neutral mobs are not made permanent village enemies");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "resident_support", timeoutTicks = 350)
    public static void supportCanHitANeutralActuallyAttackingTheVictimWithoutProvokingItsPeer(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        var worker = f.person(3, 5, true);
        var victim = f.person(10, 5, false); victim.setNoAi(true);
        var attacker = f.spawn(EntityType.ZOMBIFIED_PIGLIN, 11, 5);
        var innocent = f.spawn(EntityType.ZOMBIFIED_PIGLIN, 11, 8);
        attacker.setTarget(victim);
        helper.startSequence().thenExecute(() -> f.hurt(victim, attacker))
            .thenWaitUntil(() -> helper.assertTrue(attacker.getHealth() < attacker.getMaxHealth(),
                "the helper actually hits the neutral mob whose current target is the victim: " + f.reading(worker)))
            .thenExecute(() -> {
                helper.assertTrue(attacker.getLastDamageSource() != null && attacker.getLastDamageSource().getEntity() == worker,
                    "the real helper's native weapon, not a synthetic damage callback, causes this hit");
                helper.assertValueEqual(innocent.getHealth(), innocent.getMaxHealth(), "an unprovoked peer does not inherit hostility");
                attacker.setTarget(null); attacker.discard();
            }).thenIdle(30)
            .thenExecute(() -> helper.assertFalse(worker.hasWeaponSession(), "the temporary explicit hostility lease ends with that attacker"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "resident_support", timeoutTicks = 600)
    public static void anEscapingAttackerCannotRenewSupportPastTheFixedIncidentLeash(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        var worker = f.person(3, 5, true);
        var victim = f.person(6, 5, false); victim.setNoAi(true);
        var enemy = f.zombie(7, 5);
        Job job = f.job(worker);
        float[] health = {60};
        int[] resumed = {0};
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(job.ticks >= 5, "the worker starts at its original real worksite"))
            .thenExecute(() -> f.hurt(victim, enemy))
            .thenWaitUntil(() -> helper.assertTrue(worker.hasWeaponSession(), "the bounded support response begins"))
            .thenExecute(() -> {
                enemy.moveTo(Vec3.atBottomCenterOf(f.at(20, 5).block(f.level)));
                health[0] = enemy.getHealth(); resumed[0] = job.ticks;
            })
            .thenWaitUntil(() -> helper.assertTrue(job.working && job.ticks > resumed[0] + 5,
                "leaving the original twelve-block incident anchor releases support and resumes the same job"))
            .thenIdle(210)
            .thenExecute(() -> {
                helper.assertValueEqual(enemy.getHealth(), health[0], "the old finite alarm cannot keep attacking a departing target");
                helper.assertTrue(Reach.standingIn(worker, Stances.at(job.at)), "the helper stays at its original job rather than pursuing");
                helper.assertFalse(worker.hasWeaponSession(), "the original support hand loan is gone after its deadline");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "resident_support", timeoutTicks = 350)
    public static void aHelperBeingHitCannotRecruitASecondRingOfTheVillage(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        var victim = f.person(2, 2, false); victim.setNoAi(true);
        var helperBody = f.person(16, 2, true);
        var outside = f.person(20, 20, true);
        var first = f.zombie(3, 2);
        var second = f.zombie(17, 2);
        Job job = f.job(outside);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(job.ticks > 5, "the second-ring resident really works outside the original alarm"))
            .thenExecute(() -> {
                helper.assertTrue(outside.distanceTo(victim) > 24 && outside.distanceTo(helperBody) < 24,
                    "only a chained alarm could recruit the distant resident");
                f.hurt(victim, first);
                f.hurt(helperBody, second);
            }).thenIdle(100)
            .thenExecute(() -> {
                helper.assertTrue(job.working && job.releases == 0 && job.ticks > 50,
                    "hitting a recruited helper does not propagate a new alarm to another ring");
                helper.assertFalse(outside.hasWeaponSession(), "the uninvolved resident never takes a support weapon loan");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "resident_support", timeoutTicks = 500)
    public static void aSavedLegacyPatrolLineStaysDormantWhileAnActualVPointRouteStillMoves(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        var resident = f.person(3, 5, true);
        helper.assertTrue(f.colony.hold(PatrolContent.BEAT,
            new Held.Line(List.of(f.at(3, 5), f.at(15, 5)))).isPresent(), "the old saved path remains in colony data");
        long wardUntil = f.level.getGameTime() + 1000;
        Wards.ward(f.level, f.at(3, 5).block(f.level), wardUntil);
        var patrol = f.colony.service(PatrolContent.ID, PatrolPresence.class).orElse(null);
        helper.assertTrue(patrol != null && patrol.legacyDisabled(), "legacy automatic route dispatch is disabled");
        f.rejectLegacyWatch = true;
        helper.startSequence().thenIdle(110)
            .thenExecute(() -> {
                helper.assertTrue(patrol.goals(f.colony.view(f.level)).isEmpty(), "a saved line never generates an automatic watch job");
                helper.assertTrue(f.colony.holdings().stream().anyMatch(holding -> holding.what() instanceof Held.Line),
                    "disabling dispatch preserves the original saved route data");
                helper.assertTrue(Wards.until(f.level, f.at(3, 5).block(f.level)) >= wardUntil,
                    "existing ward records are not erased by disabling legacy dispatch");
                helper.assertValueEqual(f.legacyWatchStarts, 0,
                    "the actual labour runner never starts a legacy WatchNode; ordinary idle strolls remain permitted");
                helper.assertTrue(UnitGroups.of(f.colony).commandSelectionPoints(List.of(resident),
                    List.of(f.at(3, 5), f.at(15, 5)), 0, true), "the explicit V patrol command is accepted");
            })
            .thenWaitUntil(() -> helper.assertTrue(ResidentCommands.of(f.colony).current(resident.id())
                    .filter(order -> order.mode() == ResidentCommands.Mode.GUARD
                        && order.destination().cell().getX() == f.at(15, 5).cell().getX()
                        && Reach.standingIn(resident, Stances.at(order.destination()))).isPresent(),
                "the real player-authored V route still uses its normal GUARD order and actually reaches the second point"))
            .thenSucceed();
    }

    private static final class Job implements Node {
        final NodeSpec spec;
        final UUID worker;
        final WorldPos at;
        final Vec3 home;
        int ticks;
        int releases;
        boolean working;
        Job(ResidentEntity body) {
            worker = body.id(); at = WorldSpaces.at(body); home = body.position();
            Workload.Continuous load = (level, who) -> { ticks++; working = true; return true; };
            spec = NodeSpec.of(UUID.randomUUID(), WORK, new WorkSite.AtBlock(at), Stances.at(at), load)
                .tools(new ToolUse(new ToolNeed.Tagged(ItemTags.AXES), 0)).done();
        }
        public NodeSpec spec() { return spec; }
        public Optional<UUID> worker() { return Optional.of(worker); }
        public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        public void released(ServerLevel level, Worker who, Ending how) { releases++; working = false; }
    }

    private static final class Fixture implements GameTestListener {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ResidentCommands commands;
        final CombatSelectionOwnership selection;
        final ServerPlayer player;
        final LaborFixture labor;
        final List<Entity> entities = new ArrayList<>();
        final List<Colony> otherColonies = new ArrayList<>();
        final List<ServerPlayer> otherPlayers = new ArrayList<>();
        final Consumer<WorkProgress> progress;
        final Map<UUID, ServerPlayer> playerIndex;
        boolean rejectLegacyWatch;
        int legacyWatchStarts;
        boolean closed;
        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper) {
            this.helper = helper; level = helper.getLevel();
            for (int x = 0; x < 22; x++) for (int z = 0; z < 22; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            colony = Colonies.mint(level.getServer()); commands = ResidentCommands.of(colony);
            selection = CombatSelectionOwnership.of(colony);
            player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "support_test"));
            player.moveTo(Vec3.atBottomCenterOf(at(10, 10).block(level)));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
            Charters.keep(colony, Charter.founded("support", player.getUUID()));
            labor = new LaborFixture(colony, level);
            progress = event -> {
                if (event.colony().equals(colony.id()) && event.node() instanceof WatchNode
                        && event.step() == WorkProgress.Step.STARTED) legacyWatchStarts++;
            };
            NeoForge.EVENT_BUS.addListener(progress);
            helper.testInfo.addListener(this);
            // Explicit attack resolves its commander through the real server player index.
            // Install only this random fixture UUID; the production authority checks remain intact.
            try {
                var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");
                field.setAccessible(true);
                playerIndex = (Map<UUID, ServerPlayer>) field.get(level.getServer().getPlayerList());
            } catch (ReflectiveOperationException unavailable) { throw new IllegalStateException(unavailable); }
            playerIndex.put(player.getUUID(), player);
            helper.onEachTick(() -> {
                if (closed) return;
                selection.heartbeat(player); labor.tick();
                if (rejectLegacyWatch) {
                    helper.assertValueEqual(legacyWatchStarts, 0, "a saved legacy line never starts a real automatic WatchNode");
                    entities.stream().filter(ResidentEntity.class::isInstance).map(ResidentEntity.class::cast).forEach(body ->
                        helper.assertFalse(body.doing().filter(doing -> doing.toward().orElse(doing.what())
                            .equals(PatrolContent.PATROLLING)).isPresent(), "legacy patrol work stays retired: " + reading(body)));
                }
            });
        }
        WorldPos at(int x, int z) { return WorldSpaces.at(level, helper.absolutePos(new BlockPos(x, 1, z))); }
        ResidentEntity person(int x, int z, boolean armed) {
            return person(colony, x, z, armed);
        }
        ResidentEntity person(Colony holder, int x, int z, boolean armed) {
            var body = PersonBody.RESIDENT.get().create(level);
            helper.assertTrue(body != null, "the registered person creates the actual test resident");
            body.moveTo(Vec3.atBottomCenterOf(at(x, z).block(level))); body.getRandom().setSeed(924);
            level.addFreshEntity(body);
            helper.assertTrue(holder.hold(PersonContent.ID, Held.Entity.of(body)).isPresent(), "the colony holds the actual resident");
            body.joinColony(holder.id()); entities.add(body);
            if (armed) arm(holder, body);
            return body;
        }
        void arm(Colony holder, ResidentEntity body) {
            ServerPlayer actor = player;
            if (holder != colony) {
                actor = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "support_other"));
                actor.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(holder.id()));
                actor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
                Charters.keep(holder, Charter.founded("other support", actor.getUUID())); otherPlayers.add(actor);
            }
            Vec3 previous = actor.position(); actor.moveTo(body.position().add(0, 0, 1));
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            body.pack().insert(sword);
            int cell = -1;
            for (int i = 0; i < body.pack().getContainerSize(); i++) if (body.pack().getItem(i).is(Items.IRON_SWORD)) { cell = i; break; }
            helper.assertTrue(cell >= 0, "the actual personal sword begins as one real carried item");
            CompoundTag seen = ResidentEquipment.read(actor, body);
            helper.assertTrue(seen.hasUUID("ticket"), "the authorized keeper opens the actual personal equipment exchange");
            CompoundTag act = new CompoundTag();
            act.putUUID("resident", body.id()); act.putUUID("ticket", seen.getUUID("ticket"));
            act.putString("kind", "equip"); act.putString("slot", "mainhand");
            act.putString("source", "resident"); act.putInt("index", cell);
            ResidentEquipment.act(actor, body, act);
            helper.assertTrue(ResidentEquipment.assigned(body).is(Items.IRON_SWORD)
                    && PersonalItems.purpose(body, ResidentEquipment.assigned(body)).filter("mainhand"::equals).isPresent()
                    && Equipment.locked(body, EquipmentSlot.MAINHAND),
                "the normal server equipment action designates and protects the real personal main-hand sword; result="
                    + ResidentEquipment.read(actor, body).getString("result"));
            helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), 1, "assigning the real sword never creates a duplicate");
            actor.moveTo(previous);
        }
        <T extends Mob> T spawn(EntityType<T> type, int x, int z) {
            T mob = type.create(level); helper.assertTrue(mob != null, "the real enemy type is registered");
            mob.moveTo(Vec3.atBottomCenterOf(at(x, z).block(level))); mob.setNoAi(true);
            level.addFreshEntity(mob); entities.add(mob); return mob;
        }
        Zombie zombie(int x, int z) {
            var zombie = spawn(EntityType.ZOMBIE, x, z);
            zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60); zombie.setHealth(60); return zombie;
        }
        void hurt(ResidentEntity victim, Mob source) {
            float before = victim.getHealth();
            helper.assertTrue(victim.hurt(level.damageSources().mobAttack(source), 1) && victim.getHealth() < before,
                "the support signal comes from actual hostile damage, not direct event injection");
        }
        Job job(ResidentEntity body) {
            body.pack().insert(new ItemStack(Items.IRON_AXE));
            Job job = new Job(body);
            colony.submit(WORK, level.dimension(), Grown.of(job), (id, how) -> { }); return job;
        }
        String reading(ResidentEntity body) {
            return "body=" + body.id() + ",feet=" + body.position() + ",doing=" + body.doing()
                + ",session=" + body.hasWeaponSession() + ",order=" + commands.current(body.id()).map(ResidentCommands.Order::mode)
                + ",personal=" + ResidentEquipment.assigned(body) + ",hp=" + body.getHealth();
        }
        void close() {
            if (closed) return; closed = true;
            NeoForge.EVENT_BUS.unregister(progress);
            try {
                try { labor.close(); } finally {
                    entities.forEach(Entity::discard); colony.raze(); otherColonies.forEach(Colony::raze);
                }
            } finally {
                playerIndex.remove(player.getUUID(), player);
                player.getInventory().clearContent();
                otherPlayers.forEach(actor -> actor.getInventory().clearContent());
            }
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
