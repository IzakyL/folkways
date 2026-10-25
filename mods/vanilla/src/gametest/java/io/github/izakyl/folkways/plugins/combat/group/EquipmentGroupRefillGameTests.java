package io.github.izakyl.folkways.plugins.combat.group;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.equipment.EquipmentPlans;
import io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
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

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EquipmentGroupRefillGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(EquipmentGroupRefillGameTests.class); }

    @GameTest(template = "empty_large", batch = "equipment_group_refill", timeoutTicks = 1600)
    public static void aCompletedPointKeepsItsActualOrdersThroughALongWarehouseRoundTrip(GameTestHelper helper) {
        Fixture f = new Fixture(helper, false);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(f.group.routeComplete() && f.group.phase() == UnitGroup.Phase.HOLDING,
                "both actual members first complete their single point and its dwell"))
            .thenExecute(f::refill)
            .thenWaitUntil(() -> helper.assertTrue(f.returnedAt >= 0, "the same runner fetches and returns the real warehouse helmet"))
            .thenExecute(() -> {
                helper.assertTrue(f.awayTicks > 100, "the physical round trip exceeds the old blocked-member window");
                f.assertOriginal();
                helper.assertTrue(f.group.routeComplete(), "refilling does not reopen or replace a completed point route");
                f.assertHelmet();
            })
            .thenIdle(22)
            .thenExecute(() -> {
                f.assertOriginal();
                helper.assertTrue(f.group.phase() == UnitGroup.Phase.HOLDING, "the complete squad still holds its original point");
                f.release();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "equipment_group_refill", timeoutTicks = 1800)
    public static void aPatrolStopWaitsForTheSameOrdersToReturnAndDwellTwentyFreshTicks(GameTestHelper helper) {
        Fixture f = new Fixture(helper, true);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(f.group.patrolStop() == 0 && f.group.phase() == UnitGroup.Phase.HOLDING,
                "both actual members reach the first mandatory patrol stop"))
            .thenExecute(f::refill)
            .thenWaitUntil(() -> helper.assertTrue(f.returnedAt >= 0, "the borrowing member physically returns to its original slot"))
            .thenExecute(() -> {
                helper.assertTrue(f.awayTicks > 100, "the real refill excursion exercises the blocked-member boundary");
                f.assertOriginal();
                f.assertHelmet();
            })
            .thenWaitUntil(() -> helper.assertTrue(f.group.patrolStop() == 1, "the route advances only after a fresh complete dwell"))
            .thenExecute(() -> {
                helper.assertTrue(f.level.getGameTime() - f.returnedAt >= 20,
                    "time spent away never counts toward the twenty-tick assembled dwell");
                helper.assertTrue(f.group.pointCommandId().orElseThrow().equals(f.routeId)
                    && f.group.commandId().orElseThrow().equals(f.commandId), "the next leg belongs to the same authored command");
                f.watching = false;
            })
            .thenWaitUntil(() -> helper.assertTrue(f.group.patrolStop() == 1 && f.group.phase() == UnitGroup.Phase.HOLDING,
                "the same squad actually walks to its next patrol formation"))
            .thenExecute(f::release).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "equipment_group_refill", timeoutTicks = 1000)
    public static void aNewGroupRetreatRevokesTheRefillPauseAndTakesTheActualRunner(GameTestHelper helper) {
        Fixture f = new Fixture(helper, false);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(f.group.routeComplete() && f.group.phase() == UnitGroup.Phase.HOLDING,
                "the completed point is a stable real order before refilling"))
            .thenExecute(f::refill)
            .thenWaitUntil(() -> helper.assertTrue(EquipmentPlans.lendsRunner(f.colony, f.borrower.id())
                && f.borrower.position().distanceToSqr(Vec3.atBottomCenterOf(f.original.get(f.borrower.id()).destination().block(f.level))) > 16
                && f.store.getItem(0).is(Items.IRON_HELMET), "a real pending pickup owns the runner before reaching the source"))
            .thenExecute(() -> {
                f.watching = false;
                f.group.retreat(new Deployment(f.at(5, 1, 8), f.at(5, 1, 12), new Vec3(1, 0, 0), new Deployment.Formation(2)));
                helper.assertFalse(EquipmentPlans.lendsRunner(f.colony, f.borrower.id()), "retreat immediately invalidates the old order's lease");
                helper.assertTrue(!f.group.commandId().orElseThrow().equals(f.commandId) && f.group.pointRoute().isEmpty(),
                    "a refill pause cannot hide the newly authored group command");
                f.act("refill_cancel", "", "");
            })
            .thenWaitUntil(() -> helper.assertTrue(f.group.phase() == UnitGroup.Phase.HOLDING
                    && f.people.stream().allMatch(body -> f.commands.current(body.id()).filter(order -> order.mode() == ResidentCommands.Mode.RETREAT
                        && Reach.standingIn(body.mob(), Stances.at(order.destination()))).isPresent()),
                "both actual retreat orders take the same runners and physically reach the new formation"))
            .thenExecute(() -> {
                helper.assertTrue(f.store.getItem(0).is(Items.IRON_HELMET) && f.borrower.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                    "revoking the pickup preserves the warehouse's one actual item");
                f.release();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "equipment_group_refill", timeoutTicks = 1000)
    public static void anIndividualMoveLeavesTheRefillingGroupBeforeItsOldPauseCanRun(GameTestHelper helper) {
        Fixture f = new Fixture(helper, false);
        AtomicReference<ResidentCommands.Order> moving = new AtomicReference<>();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(f.group.routeComplete() && f.group.phase() == UnitGroup.Phase.HOLDING,
                "the actual squad completes its initial point"))
            .thenExecute(f::refill)
            .thenWaitUntil(() -> helper.assertTrue(EquipmentPlans.lendsRunner(f.colony, f.borrower.id())
                && f.borrower.position().distanceToSqr(Vec3.atBottomCenterOf(f.original.get(f.borrower.id()).destination().block(f.level))) > 16
                && f.store.getItem(0).is(Items.IRON_HELMET), "the real pickup is underway before the personal replacement"))
            .thenExecute(() -> {
                f.watching = false;
                moving.set(f.commands.move(f.borrower, f.at(6, 1, 9)));
                helper.assertFalse(EquipmentPlans.lendsRunner(f.colony, f.borrower.id()), "the old lease is invalid before the next squad tick");
                f.act("refill_cancel", "", "");
            })
            .thenWaitUntil(() -> helper.assertTrue(!f.group.members().contains(f.borrower.id())
                && moving.get().status() == ResidentCommands.Status.ARRIVED,
                "personal movement detaches first and physically completes through the same runner"))
            .thenExecute(() -> {
                helper.assertTrue(f.store.getItem(0).is(Items.IRON_HELMET) && f.borrower.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                    "the obsolete pickup cannot debit its old warehouse source");
                helper.assertTrue(UnitGroups.of(f.colony).ofResident(f.people.get(1).id()).orElseThrow() == f.group,
                    "the other resident keeps the real squad membership");
                f.release();
            }).thenSucceed();
    }

    private static final class Fixture implements GameTestListener {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ServerPlayer player;
        final List<Body> people = new ArrayList<>();
        final ResidentEntity borrower;
        final ResidentCommands commands;
        final EquipmentPlans plans;
        final UnitGroup group;
        final Container store;
        final WorldPos storeAt;
        final LaborFixture simulation;
        final Map<UUID, ResidentCommands.Order> original = new LinkedHashMap<>();
        UUID routeId;
        UUID commandId;
        WorldPos captain;
        int travelTicks;
        int awayTicks;
        int leaseCount;
        UUID leaseId;
        long leaseStarted;
        long leaseDeadline;
        long returnedAt = -1;
        boolean sawLoan;
        boolean watching;
        boolean closed;

        Fixture(GameTestHelper helper, boolean patrol) {
            this.helper = helper; level = helper.getLevel();
            for (int x = 0; x < 22; x++) for (int z = 0; z < 22; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "group_refill"));
            player.getInventory().clearContent();
            colony = Colonies.mint(level.getServer()); Charters.keep(colony, Charter.founded("group refill", player.getUUID()));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            for (int n = 0; n < 2; n++) {
                ResidentEntity person = PersonBody.RESIDENT.get().create(level);
                BlockPos cell = at(3, 1, 3 + n * 2).block(level);
                person.moveTo(cell.getX() + .5, cell.getY(), cell.getZ() + .5);
                person.getRandom().setSeed(42L + n);
                colony.hold(PersonContent.ID, Held.Entity.of(person)).orElseThrow(); person.joinColony(colony.id());
                level.addFreshEntity(person); people.add(person);
            }
            borrower = (ResidentEntity) people.getFirst().mob();
            player.moveTo(borrower.getX(), borrower.getY(), borrower.getZ() - 1);
            BlockPos warehouse = at(20, 1, 17).block(level); level.setBlockAndUpdate(warehouse, Blocks.BARREL.defaultBlockState());
            store = Containers.at(level, warehouse).orElseThrow();
            storeAt = WorldPos.of(level, warehouse);
            colony.stores(EquipmentPlans.ID, level.dimension(), List.of(storeAt));
            plans = EquipmentPlans.of(colony); commands = ResidentCommands.of(colony);
            ItemStack helmet = new ItemStack(Items.IRON_HELMET); helmet.setDamageValue(21);
            helmet.set(DataComponents.CUSTOM_NAME, Component.literal("the one distant group helmet"));
            borrower.setItemSlot(EquipmentSlot.HEAD, helmet);
            act("template_save", "", "Group refill helmet");
            var choices = plans.snapshot(player, borrower).getList("templates", Tag.TAG_COMPOUND);
            String custom = choices.getCompound(choices.size() - 1).getString("id");
            borrower.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY); store.setItem(0, helmet);
            act("template_apply", custom, "");
            UnitGroups groups = UnitGroups.of(colony);
            List<WorldPos> points = patrol ? List.of(at(3, 1, 4), at(8, 1, 4)) : List.of(at(3, 1, 4));
            helper.assertTrue(groups.commandSelectionPoints(people, points, patrol ? 0 : -1, true), "the real point command is accepted");
            group = groups.ofResident(borrower.id()).orElseThrow();
            simulation = new LaborFixture(colony, level);
            helper.testInfo.addListener(this);
            helper.onEachTick(() -> { if (!closed) { simulation.tick(); observe(); } });
        }

        WorldPos at(int x, int y, int z) { return WorldPos.of(level, helper.absolutePos(new BlockPos(x, y, z))); }

        void act(String kind, String template, String name) {
            CompoundTag snapshot = ResidentEquipment.read(player, borrower);
            helper.assertTrue(snapshot.hasUUID("ticket"), "the keeper has a real equipment page ticket");
            CompoundTag action = new CompoundTag(); action.putUUID("resident", borrower.id()); action.putUUID("ticket", snapshot.getUUID("ticket"));
            action.putString("kind", kind); action.putString("template", template); action.putString("scope", "self"); action.putString("name", name);
            ResidentEquipment.act(player, borrower, action);
        }

        void refill() {
            people.forEach(body -> original.put(body.id(), commands.current(body.id()).orElseThrow()));
            routeId = group.pointCommandId().orElseThrow(); commandId = group.commandId().orElseThrow();
            captain = group.captain().orElseThrow();
            watching = true;
            act("refill", "", "");
            helper.assertTrue(plans.snapshot(player, borrower).getCompound("goal").getBoolean("active"), "the explicit equipment request is active");
        }

        void observe() {
            if (!watching) return;
            long now = level.getGameTime();
            boolean lent = EquipmentPlans.lendsRunner(colony, borrower.id());
            if (lent) {
                Lease lease = lease();
                if (!lease.id().equals(leaseId)) {
                    if (leaseId != null) { report("replaced", now, true); }
                    leaseId = lease.id(); leaseDeadline = lease.deadline(); leaseStarted = now; leaseCount++;
                    travelTicks = pointRoute().travellingTicks();
                    report("started", now, true);
                }
                sawLoan = true;
                helper.assertValueEqual(pointRoute().travellingTicks(), travelTicks,
                    "one valid runner loan consumes none of the point route's travel budget; lease=" + leaseId);
            } else if (leaseId != null) {
                report(now >= leaseDeadline ? "expired" : "ended", now, false);
                leaseId = null;
            }
            if (now % 40 == 0) { report("position", now, lent); }
            boolean home = original.values().stream().allMatch(order -> order.status() == ResidentCommands.Status.HOLDING
                && Reach.standingIn(people.stream().filter(body -> body.id().equals(order.resident())).findFirst().orElseThrow().mob(), Stances.at(order.destination())));
            if (sawLoan && !home) awayTicks++;
            if (sawLoan && !lent && home && borrower.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && returnedAt < 0) {
                returnedAt = level.getGameTime();
            }
            if (returnedAt < 0 || level.getGameTime() - returnedAt < 20) assertOriginal();
        }

        private record Lease(UUID id, long deadline) { }

        // Observation only: the real service and runner create, time out and settle every request.
        private Lease lease() {
            try {
                var requests = EquipmentPlans.class.getDeclaredField("pending"); requests.setAccessible(true);
                Node request = (Node) ((Map<?, ?>) requests.get(plans)).get(borrower.id());
                var deadline = request.getClass().getDeclaredField("deadline"); deadline.setAccessible(true);
                return new Lease(request.id(), deadline.getLong(request));
            } catch (ReflectiveOperationException broken) { throw new IllegalStateException(broken); }
        }

        private void report(String event, long now, boolean lent) {
            var goal = plans.snapshot(player, borrower).getCompound("goal");
            org.slf4j.LoggerFactory.getLogger("folkways-equipment-group-test").info(
                "event={} body={} tick={} lease={} attempt={} elapsed={} deadline={} lent={} feet={} source={} distance={} stock={} helmet={} order={} doing={} goal={} active={} phase={} stop={} travel={} away={}",
                event, borrower.id(), now, leaseId, leaseCount, now - leaseStarted, leaseDeadline, lent,
                borrower.position(), storeAt, WorldSpaces.world(level, storeAt).map(at -> at.distanceTo(borrower.position())).orElse(-1.0),
                store.getItem(0).getCount(), borrower.getItemBySlot(EquipmentSlot.HEAD),
                commands.current(borrower.id()).map(order -> order.id() + "/" + order.status()).orElse("none"),
                borrower.doing().map(doing -> doing.what().toString()).orElse("idle"), goal.getString("status"), goal.getBoolean("active"),
                group.phase(), group.patrolStop(), pointRoute().travellingTicks(), awayTicks);
        }

        PointRoute pointRoute() {
            try {
                var field = UnitGroup.class.getDeclaredField("points"); field.setAccessible(true);
                return (PointRoute) field.get(group);
            } catch (ReflectiveOperationException broken) { throw new IllegalStateException(broken); }
        }

        void assertOriginal() {
            helper.assertTrue(group.commandId().orElseThrow().equals(commandId) && group.pointCommandId().orElseThrow().equals(routeId)
                && group.captain().orElseThrow().equals(captain) && group.patrolStop() == 0,
                "refilling preserves the group, authored route, current stop and logical captain");
            original.forEach((resident, order) -> helper.assertTrue(commands.current(resident).orElse(null) == order
                && group.destinations().get(resident).equals(order.destination()), "the refill must never replace or recover an original group order"));
            helper.assertTrue(group.failedMembers().isEmpty() && group.phase() != UnitGroup.Phase.FAILED_WAITING,
                "the deliberate refill excursion is not a failed or blocked member");
        }

        void assertHelmet() {
            ItemStack worn = borrower.getItemBySlot(EquipmentSlot.HEAD);
            helper.assertTrue(worn.is(Items.IRON_HELMET) && worn.getDamageValue() == 21
                && worn.getHoverName().getString().equals("the one distant group helmet") && store.isEmpty()
                && people.stream().mapToInt(body -> body.pack().count(Items.IRON_HELMET)).sum() == 0,
                "the warehouse's one actual component-bearing helmet is worn once, without a pack copy");
        }

        void release() {
            watching = false; group.release();
            helper.assertTrue(people.stream().allMatch(body -> commands.current(body.id()).isEmpty()), "one group release returns both runners to ordinary work");
        }

        private void close() {
            if (closed) return;
            closed = true; watching = false; simulation.close();
            people.forEach(body -> body.mob().discard()); colony.raze(); player.getInventory().clearContent();
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
