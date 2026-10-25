package io.github.izakyl.folkways.plugins.combat.group;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FormationTransitGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(FormationTransitGameTests.class); }

    @GameTest(template = "formation_long", batch = "formation_transit", timeoutTicks = 1800)
    public static void eightMembersTraverseAOneCellCorridorWithoutInternalAssemblyOrHealthyOrderReplacement(GameTestHelper helper) {
        corridor(helper, 8);
    }

    @GameTest(template = "formation_long", batch = "formation_transit", timeoutTicks = 2400)
    public static void sixteenMembersTraverseTheSameOneCellCorridorAndAllReachTheirOwnFinalSlots(GameTestHelper helper) {
        corridor(helper, 16);
    }

    private static void corridor(GameTestHelper helper, int count) {
        Scene f = new Scene(helper, count);
        for (int x = 14; x <= 54; x++) for (int z = 1; z < 23; z++) if (z != 11) {
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        }
        UnitGroup group = f.start(f.at(66, 11), false);
        Map<UUID, ResidentCommands.Order> original = f.handles();
        f.watchDwell(group);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(group.routeComplete(),
                "the complete squad crosses the actual one-cell passage: " + f.reading(group)))
            .thenExecute(() -> {
                f.assertFinal(group);
                helper.assertTrue(f.people.stream().allMatch(body -> body.getX() > f.at(54, 11).cell().getX()),
                    "every real member has physically passed the corridor exit");
                original.forEach((member, order) -> helper.assertTrue(f.commands.current(member).orElse(null) == order,
                    "transit and queue yielding keep every healthy member's original runner handle"));
                f.release(group);
            }).thenSucceed();
    }

    @GameTest(template = "formation_long", batch = "formation_transit", timeoutTicks = 2000)
    public static void aRealUDetourKeepsHealthyHandlesAndLocalCornerRecoveryBounded(GameTestHelper helper) {
        Scene f = new Scene(helper, 2);
        f.place(f.people.get(0), 4, 4); f.place(f.people.get(1), 4, 6);
        // A short five-wide U: outbound and return aisles connect only at x=17..21.
        // Filling the unused plane avoids testing native broad-plane search expansion limits.
        for (int x = 1; x < 79; x++) for (int z = 1; z < 23; z++) {
            boolean aisle = x <= 21 && (z >= 3 && z <= 7 || z >= 12 && z <= 16)
                || x >= 17 && x <= 21 && z >= 3 && z <= 16;
            if (!aisle) for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        }
        f.people.forEach(body -> {
            // The complete U is under forty blocks; real outward walking still lasts over 200 ticks.
            // Stay above native movement's .003 velocity dead zone: .03 attributes do not produce reliable walking.
            body.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(128);
            body.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.11);
        });
        WorldPos goal = f.at(4, 14);
        f.people.forEach(body -> {
            var path = body.getNavigation().createPath(goal.block(f.level), 0);
            helper.assertTrue(path != null && path.canReach(),
                "the bounded U must expose a complete native path within its explicit search range: start=" + body.position()
                    + ",goal=" + goal + ",nodes=" + (path == null ? "none" : path.getNodeCount())
                    + ",end=" + (path == null ? "none" : path.getEndNode())
                    + ",nativeWalked=" + (path == null || path.getEndNode() == null ? "none" : path.getEndNode().walkedDistance));
        });
        UnitGroup group = f.start(goal, false);
        Map<UUID, ResidentCommands.Order> original = f.handles();
        Map<UUID, ResidentCommands.Order> observed = new LinkedHashMap<>(original);
        List<String> changes = new ArrayList<>();
        List<Integer> recoveredCorners = new ArrayList<>();
        long started = f.level.getGameTime();
        UUID generation = group.pointCommandId().orElse(null);
        helper.assertTrue(generation != null, "the actual point command has a generation");
        record Position(long tick, Vec3 feet) { }
        ArrayDeque<Position> history = new ArrayDeque<>();
        history.add(new Position(started, f.people.getFirst().position()));
        AtomicBoolean visited = new AtomicBoolean();
        AtomicLong outwardWalkingTicks = new AtomicLong();
        float[] walked = {f.people.getFirst().walkDist};
        AtomicBoolean longOutward = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (f.closed) return;
            ResidentEntity body = f.people.getFirst();
            long now = f.level.getGameTime();
            if (history.getLast().tick() != now) history.addLast(new Position(now, body.position()));
            while (history.getFirst().tick() < now - 100) history.removeFirst();
            helper.assertTrue(group.patrolStop() == 0 && group.pointRoute().equals(List.of(goal))
                    && group.pointCommandId().filter(generation::equals).isPresent()
                    && f.people.stream().allMatch(member -> f.groups.ofResident(member.id()).orElse(null) == group),
                "local corner recovery preserves the actual group, route generation and mandatory stop");
            helper.assertTrue(f.commands.current(f.people.get(1).id()).orElse(null) == original.get(f.people.get(1).id()),
                "the other healthy resident keeps its exact original order for the entire U");
            if (f.people.stream().anyMatch(member -> member.onGround() && Reach.standingIn(member, Stances.at(goal)))) visited.set(true);
            f.people.forEach(member -> {
                ResidentCommands.Order current = f.commands.current(member.id()).orElse(null);
                ResidentCommands.Order before = observed.put(member.id(), current);
                if (before != current) {
                    double maxApart = 0;
                    for (Position a : history) for (Position b : history) maxApart = Math.max(maxApart, a.feet().distanceTo(b.feet()));
                    long span = history.getLast().tick() - history.getFirst().tick();
                    var path = member.getNavigation().getPath();
                    changes.add("tick=" + (f.level.getGameTime() - started) + ",body=" + member.id() + "@" + member.position()
                        + ",old=" + (before == null ? "none" : before.id() + ":" + before.status() + ":" + before.failure())
                        + ",new=" + (current == null ? "none" : current.id())
                        + ",path=" + (path == null ? "none" : path.getNextNodeIndex() + "/" + path.getNodeCount())
                        + ",historySpan=" + span + ",samples=" + history.size() + ",maxApart=" + maxApart);
                    helper.assertTrue(member == body && before != null && current != null && changes.size() <= 2
                            && before.status() == ResidentCommands.Status.RELEASED && before.failure().isEmpty(),
                        "only the genuinely blocked first resident receives at most two local recoveries: " + changes);
                    helper.assertTrue(span == 100 && history.size() == 101 && maxApart < .5,
                        "a replacement requires one hundred real consecutive ticks without half a block of physical progress: " + changes);
                    double x = member.getX() - f.at(0, 0).cell().getX();
                    double z = member.getZ() - f.at(0, 0).cell().getZ();
                    int corner = z >= 7 && z <= 8 ? 1 : z >= 12 && z <= 13 ? 2 : 0;
                    helper.assertTrue(x >= 17 && x <= 18 && corner != 0 && !recoveredCorners.contains(corner),
                        "each recovery occurs only once at one of the two known physical inner corners: " + changes);
                    recoveredCorners.add(corner);
                }
            });
            if (body.getX() > f.at(6, 5).cell().getX() && body.getX() < f.at(17, 5).cell().getX()
                    && body.getZ() < f.at(4, 9).cell().getZ() && body.walkDist > walked[0]) {
                helper.assertTrue(f.commands.current(body.id()).orElse(null) == original.get(body.id()),
                    "continuous outward walking keeps the first resident's original runner even while getting farther from its goal");
                if (outwardWalkingTicks.incrementAndGet() > 200) longOutward.set(true);
            }
            walked[0] = body.walkDist;
        });
        f.watchDwell(group);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(group.routeComplete(),
                "both actual runners finish the useful U-shaped path: " + f.reading(group)))
            .thenExecute(() -> {
                helper.assertTrue(longOutward.get(), "real outward walking exceeds the old two-hundred-tick Euclidean progress deadline: ticks=" + outwardWalkingTicks.get());
                f.assertFinal(group);
                helper.assertTrue(visited.get(), "a real member physically visits the authored click before completing the final dwell");
                helper.assertTrue(changes.size() <= 2, "physical corner recovery stays bounded: " + changes);
                f.release(group);
            }).thenSucceed();
    }

    @GameTest(template = "formation_long", batch = "formation_transit", timeoutTicks = 1600)
    public static void oneTemporarilyEnclosedMemberRecoversWithoutRestartingHealthyMembersOrSkippingTheirStop(GameTestHelper helper) {
        Scene f = new Scene(helper, 4);
        for (int n = 0; n < 4; n++) f.place(f.people.get(n), 4, 4 + n * 4);
        ResidentEntity blocked = f.people.get(3);
        f.cage(4, 16, true);
        UnitGroup group = f.start(f.at(66, 11), false);
        Map<UUID, ResidentCommands.Order> original = f.handles();
        f.watchDwell(group);
        helper.startSequence().thenIdle(180)
            .thenExecute(() -> {
                helper.assertFalse(group.routeComplete(), "three survivors cannot substitute for the enclosed member's mandatory arrival");
                helper.assertTrue(group.members().size() == 4 && group.waitingMembers() > 0 && group.failedMembers().contains(blocked.id()),
                    "the temporarily blocked member remains owned and visibly identified: " + f.reading(group));
                for (int n = 0; n < 3; n++) {
                    ResidentEntity body = f.people.get(n);
                    helper.assertTrue(body.getX() > f.at(15, 11).cell().getX(), "healthy members continue real transit towards their stop");
                    helper.assertTrue(f.commands.current(body.id()).orElse(null) == original.get(body.id()), "another member's failure never resets a healthy handle");
                }
                f.cage(4, 16, false);
            })
            .thenWaitUntil(() -> helper.assertTrue(group.routeComplete(), "opening the actual cage allows a finite local recovery: " + f.reading(group)))
            .thenExecute(() -> {
                f.assertFinal(group);
                for (int n = 0; n < 3; n++) {
                    ResidentEntity body = f.people.get(n);
                    helper.assertTrue(f.commands.current(body.id()).orElse(null) == original.get(body.id()), "healthy final holders retain exactly their original orders");
                }
                f.release(group);
            }).thenSucceed();
    }

    @GameTest(template = "formation_long", batch = "formation_transit", timeoutTicks = 2000)
    public static void attackMoveFightsFarBeforeItsStopThenPhysicallyRegroupsForTwentyTicksBeforeResuming(GameTestHelper helper) {
        Scene f = new Scene(helper, 2);
        f.place(f.people.get(0), 4, 9); f.place(f.people.get(1), 4, 12);
        f.people.forEach(f::arm);
        Zombie enemy = EntityType.ZOMBIE.create(f.level);
        helper.assertTrue(enemy != null, "the actual zombie type exists");
        enemy.moveTo(Vec3.atBottomCenterOf(f.at(16, 10).block(f.level))); enemy.setNoAi(true);
        enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(30); enemy.setHealth(30);
        f.level.addFreshEntity(enemy); f.enemies.add(enemy);
        WorldPos stop = f.at(66, 11);
        helper.assertTrue(enemy.position().distanceTo(Vec3.atBottomCenterOf(stop.block(f.level))) > 48,
            "the encounter is more than forty-eight blocks before the unfinished stop");
        UnitGroup group = f.start(stop, true);
        UUID route = group.pointCommandId().orElse(null);
        AtomicLong localAt = new AtomicLong(Long.MIN_VALUE);
        AtomicLong resumedAt = new AtomicLong(Long.MIN_VALUE);
        Map<UUID, WorldPos> homes = new LinkedHashMap<>();
        helper.onEachTick(() -> {
            if (homes.isEmpty() || resumedAt.get() != Long.MIN_VALUE) return;
            boolean resumed = f.handles().values().stream().allMatch(order -> order.destination().cell().getX() > f.at(50, 11).cell().getX());
            if (resumed) {
                helper.assertTrue(localAt.get() != Long.MIN_VALUE && f.level.getGameTime() - localAt.get() >= 20,
                    "route travel resumes only after twenty complete local regroup ticks");
                resumedAt.set(f.level.getGameTime());
            } else if (!enemy.isAlive() && f.people.stream().allMatch(body -> f.commands.current(body.id()).filter(order ->
                    !order.engaged() && !order.yieldingToDanger() && order.status() == ResidentCommands.Status.HOLDING
                        && order.destination().equals(homes.get(body.id()))
                        && Reach.standingIn(body, Stances.at(order.destination()))).isPresent())) {
                localAt.compareAndSet(Long.MIN_VALUE, f.level.getGameTime());
            } else { localAt.set(Long.MIN_VALUE); }
            helper.assertTrue(group.patrolStop() == 0 && group.pointCommandId().filter(route::equals).isPresent(),
                "combat and regroup preserve the same unfinished mandatory stop and authored route");
        });
        f.watchDwell(group);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(enemy.getHealth() < enemy.getMaxHealth()
                    && group.phase() == UnitGroup.Phase.ENGAGING, "the real AM weapon hits an unprovoked enemy beside the squad: " + f.reading(group)))
            .thenExecute(() -> {
                homes.putAll(group.destinations());
                helper.assertTrue(homes.values().stream().allMatch(home -> home.cell().getX() < f.at(20, 11).cell().getX()),
                    "the encounter captures actual local homes rather than the distant stop");
                helper.assertTrue(f.people.stream().anyMatch(body -> body.position().distanceTo(
                    Vec3.atBottomCenterOf(homes.get(body.id()).block(f.level))) >= 8), "combat itself has already moved a helper at least eight blocks");
                f.people.forEach(body -> helper.assertTrue(group.encounterAnchor(body.id()).filter(homes.get(body.id())::equals).isPresent(),
                    "eight blocks of combat displacement never refreshes its fixed local leash"));
            })
            .thenWaitUntil(() -> helper.assertFalse(enemy.isAlive(), "the actual native weapons defeat the attacker"))
            .thenWaitUntil(() -> helper.assertTrue(resumedAt.get() != Long.MIN_VALUE, "the real squad finishes its local twenty-tick regroup"))
            .thenWaitUntil(() -> helper.assertTrue(group.routeComplete(), "the squad then reaches the same original mandatory stop: " + f.reading(group)))
            .thenExecute(() -> { f.assertFinal(group); f.release(group); }).thenSucceed();
    }

    @GameTest(template = "formation_long", batch = "formation_transit", timeoutTicks = 1400)
    public static void anArrivedAttackMoveKeepsItsOriginalTwelveBlockStationsWhenCombatDrawsItAway(GameTestHelper helper) {
        Scene f = new Scene(helper, 2);
        f.people.forEach(f::arm);
        UnitGroup group = f.start(f.at(10, 11), true);
        Map<UUID, ResidentCommands.Order> original = f.handles();
        Map<UUID, WorldPos> stations = new LinkedHashMap<>(group.destinations());
        AtomicBoolean defending = new AtomicBoolean();
        float[] escapedHealth = new float[1];
        Zombie enemy = EntityType.ZOMBIE.create(f.level);
        helper.assertTrue(enemy != null, "the real hostile type creates an enemy");
        enemy.setNoAi(true); enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200); enemy.setHealth(200);
        f.enemies.add(enemy);
        helper.onEachTick(() -> {
            if (!defending.get() || f.closed) return;
            helper.assertFalse(group.transiting(), "combat displacement cannot reopen the completed stop's transit stage");
            f.people.forEach(body -> {
                helper.assertTrue(f.commands.current(body.id()).orElse(null) == original.get(body.id()),
                    "station defense retains the original fixed guard order: " + f.reading(group));
                helper.assertTrue(body.position().distanceTo(Vec3.atBottomCenterOf(stations.get(body.id()).block(f.level))) <= 12.9,
                    "the defender remains inside its original station's twelve-block leash: " + f.reading(group));
            });
        });
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(group.routeComplete() && group.atAuthoredStop(),
                "the real AM squad first completes its full authored stop and dwell: " + f.reading(group)))
            .thenExecute(() -> {
                defending.set(true);
                enemy.moveTo(Vec3.atBottomCenterOf(f.at(23, 11).block(f.level)));
                f.level.addFreshEntity(enemy);
            })
            .thenWaitUntil(() -> helper.assertTrue(enemy.getHealth() < enemy.getMaxHealth()
                    && f.people.stream().anyMatch(body -> !Reach.standingIn(body, Stances.at(stations.get(body.id())))),
                "a real native weapon hits after leaving its actual fixed station: " + f.reading(group)))
            .thenExecute(() -> {
                enemy.moveTo(Vec3.atBottomCenterOf(f.at(38, 11).block(f.level)));
                escapedHealth[0] = enemy.getHealth();
            })
            .thenExecuteAfter(80, () -> {
                helper.assertTrue(enemy.isAlive() && enemy.getHealth() == escapedHealth[0],
                    "the escaping enemy receives no further real weapon damage outside the fixed station's discovery boundary");
                enemy.discard();
            })
            .thenWaitUntil(() -> helper.assertTrue(group.atAuthoredStop(), "the same guards physically return to their original slots: " + f.reading(group)))
            .thenExecute(() -> {
                helper.assertTrue(group.destinations().equals(stations) && group.routeComplete(), "defense preserves the completed route and its original stations");
                f.release(group); defending.set(false);
            }).thenSucceed();
    }

    @GameTest(template = "formation_long", batch = "formation_transit", timeoutTicks = 1500)
    public static void deathOfTheUnarrivedReferenceReassignsOnlyOneSurvivorAndStillVisitsTheAuthoredClick(GameTestHelper helper) {
        lostReference(helper, true);
    }

    @GameTest(template = "formation_long", batch = "formation_transit", timeoutTicks = 1500)
    public static void anIndividualOverrideOfTheReferenceCannotLetTheRemainingSquadSkipTheAuthoredClick(GameTestHelper helper) {
        lostReference(helper, false);
    }

    private static void lostReference(GameTestHelper helper, boolean death) {
        Scene f = new Scene(helper, 3);
        UnitGroup group = f.start(f.at(66, 11), false);
        ResidentEntity reference = f.people.getFirst(), untouched = f.people.get(2);
        ResidentCommands.Order healthy = f.commands.current(untouched.id()).orElse(null);
        AtomicBoolean visited = new AtomicBoolean();
        helper.onEachTick(() -> f.people.stream().filter(body -> body != reference && body.isAlive())
            .filter(body -> Reach.standingAt(body.position(), f.at(66, 11).block(f.level))).findAny().ifPresent(body -> visited.set(true)));
        if (death) reference.hurt(f.level.damageSources().genericKill(), 1000);
        else f.commands.move(reference, f.at(5, 18));
        f.watchDwell(group);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(group.routeComplete(), "the surviving squad finishes the original click: " + f.reading(group)))
            .thenExecute(() -> {
                helper.assertTrue(group.members().size() == 2 && !group.members().contains(reference.id()), "only the dead or explicitly detached member leaves");
                helper.assertTrue(visited.get(), "a real surviving resident physically visits the authored click before completion");
                helper.assertTrue(f.commands.current(untouched.id()).orElse(null) == healthy, "only the one necessary new reference may be reassigned");
                f.assertFinal(group); f.release(group);
            }).thenSucceed();
    }

    private static final class Scene implements GameTestListener {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ResidentCommands commands;
        final UnitGroups groups;
        final LaborFixture labor;
        final List<ResidentEntity> people = new ArrayList<>();
        final List<Entity> enemies = new ArrayList<>();
        ServerPlayer keeper;
        boolean closed;
        Scene(GameTestHelper helper, int count) {
            this.helper = helper; level = helper.getLevel(); colony = Colonies.mint(level.getServer());
            commands = ResidentCommands.of(colony); groups = UnitGroups.of(colony);
            for (int x = 0; x < 80; x++) for (int z = 0; z < 24; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                if (x == 0 || x == 79 || z == 0 || z == 23) for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
            }
            for (int n = 0; n < count; n++) {
                ResidentEntity body = PersonBody.RESIDENT.get().create(level); helper.assertTrue(body != null, "the actual person type creates a body");
                place(body, 3 + n % 4 * 2, 5 + n / 4 * 2); body.setOnGround(true);
                level.addFreshEntity(body);
                helper.assertTrue(colony.hold(PersonContent.ID, Held.Entity.of(body)).isPresent(), "each actual person is held by this colony");
                body.joinColony(colony.id()); people.add(body);
                body.pack().insert(new ItemStack(Items.BREAD, 8));
            }
            labor = new LaborFixture(colony, level); helper.testInfo.addListener(this);
            helper.onEachTick(() -> { if (!closed) labor.tick(); });
        }
        WorldPos at(int x, int z) { return WorldPos.of(level, helper.absolutePos(new BlockPos(x, 1, z))); }
        void place(ResidentEntity body, int x, int z) { body.moveTo(Vec3.atBottomCenterOf(at(x, z).block(level))); body.setOnGround(true); }
        UnitGroup start(WorldPos goal, boolean attackMove) {
            List<Body> bodies = people.stream().map(body -> (Body) body).toList();
            helper.assertTrue(groups.commandSelectionPoints(bodies, List.of(goal), -1, attackMove), "the full real squad accepts its mandatory point");
            UnitGroup group = groups.ofResident(people.getFirst().id()).orElse(null);
            helper.assertTrue(group != null, "the command installs actual group membership"); return group;
        }
        Map<UUID, ResidentCommands.Order> handles() {
            Map<UUID, ResidentCommands.Order> result = new LinkedHashMap<>();
            people.forEach(body -> commands.current(body.id()).ifPresent(order -> result.put(body.id(), order))); return result;
        }
        void watchDwell(UnitGroup group) {
            AtomicLong arrived = new AtomicLong(Long.MIN_VALUE);
            helper.onEachTick(() -> {
                if (closed) return;
                if (group.atAuthoredStop()) arrived.compareAndSet(Long.MIN_VALUE, level.getGameTime());
                else arrived.set(Long.MIN_VALUE);
                if (group.routeComplete()) helper.assertTrue(arrived.get() != Long.MIN_VALUE && level.getGameTime() - arrived.get() >= 20,
                    "a mandatory point completes only after every retained member physically holds its own slot for twenty consecutive ticks");
            });
        }
        void assertFinal(UnitGroup group) {
            helper.assertTrue(group.atAuthoredStop(), "all surviving members really stand in their distinct final slots: " + reading(group));
            helper.assertValueEqual(group.destinations().values().stream().distinct().count(), (long) group.members().size(), "final slots are unique");
        }
        void cage(int x, int z, boolean fill) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0)
                for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x + dx, y, z + dz), fill ? Blocks.STONE : Blocks.AIR);
        }
        void arm(ResidentEntity body) {
            if (keeper == null) {
                keeper = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "formation_test"));
                keeper.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
                keeper.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
                Charters.keep(colony, Charter.founded("formation", keeper.getUUID()));
            }
            keeper.moveTo(body.position().add(0, 0, 1));
            keeper.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            CompoundTag seen = ResidentEquipment.read(keeper, body);
            helper.assertTrue(seen.hasUUID("ticket"), "the keeper has a real equipment ticket");
            CompoundTag action = new CompoundTag(); action.putUUID("resident", body.id()); action.putUUID("ticket", seen.getUUID("ticket"));
            action.putString("kind", "equip"); action.putString("slot", "mainhand"); action.putString("source", "player"); action.putInt("index", 0);
            ResidentEquipment.act(keeper, body, action);
            helper.assertTrue(ResidentEquipment.assigned(body).is(Items.IRON_SWORD), "the actual player-owned sword is assigned through the real equipment action");
        }
        String reading(UnitGroup group) {
            return "phase=" + group.phase() + ",stop=" + group.patrolStop() + ",waiting=" + group.waitingMembers()
                + ",authored=" + group.pointRoute() + ",failed=" + group.failedMembers() + ",members=" + people.stream().filter(Entity::isAlive).map(body ->
                    body.id() + "@" + body.position() + "/" + commands.current(body.id()).map(order -> order.id() + ":" + order.status()
                        + "->" + order.destination().cell())
                        + "/ground=" + body.onGround()).toList();
        }
        void release(UnitGroup group) { group.release(); helper.assertTrue(group.members().stream().allMatch(member -> commands.current(member).isEmpty()), "release revokes every actual group order"); }
        void close() {
            if (closed) return; closed = true;
            try { labor.close(); } finally { people.forEach(Entity::discard); enemies.forEach(Entity::discard); colony.raze(); if (keeper != null) keeper.getInventory().clearContent(); }
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
