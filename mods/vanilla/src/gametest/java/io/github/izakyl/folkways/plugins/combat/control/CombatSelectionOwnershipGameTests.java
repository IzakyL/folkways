package io.github.izakyl.folkways.plugins.combat.control;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
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
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.group.Deployment;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroup;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroups;
import io.github.izakyl.folkways.plugins.combat.net.SelectionOrders;
import io.github.izakyl.folkways.plugins.combat.ui.CombatDesk;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
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

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CombatSelectionOwnershipGameTests {
    private static final ResourceLocation WORK = ResourceLocation.fromNamespaceAndPath("folkways_test", "selection_work");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CombatSelectionOwnershipGameTests.class); }

    @GameTest(template = "empty_large")
    public static void exactGroupReplacementExpandsShrinksAndTogglesWithoutClearingSelection(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body a = f.resident(2), b = f.resident(4), c = f.resident(6), d = f.resident(8);
        var own = f.ownership;
        own.select(f.first, List.of(a.id(), b.id(), c.id()));
        own.bindGroup(f.first, 0);
        own.select(f.first, List.of(a.id(), b.id(), c.id(), d.id()));
        own.bindGroup(f.first, 0);
        helper.assertValueEqual(Set.copyOf(own.snapshot(f.first).groups().get(0)), Set.of(a.id(), b.id(), c.id(), d.id()),
            "ABCD exactly replaces ABC instead of keeping the old group size");
        own.select(f.first, List.of(a.id(), b.id()));
        own.bindGroup(f.first, 0);
        helper.assertValueEqual(Set.copyOf(own.snapshot(f.first).groups().get(0)), Set.of(a.id(), b.id()),
            "AB exactly replaces ABCD");
        helper.assertFalse(own.owns(f.first, c) || own.owns(f.first, d), "removed members with no other reference are released individually");
        own.select(f.first, List.of(a.id(), b.id(), c.id()));
        own.bindGroup(f.first, 0);
        own.bindGroup(f.first, 0);
        helper.assertTrue(own.snapshot(f.first).groups().get(0).isEmpty(), "an identical set toggles the shortcut off");
        helper.assertValueEqual(Set.copyOf(own.snapshot(f.first).selection()), Set.of(a.id(), b.id(), c.id()),
            "toggling a shortcut preserves its visible selection");
        helper.assertTrue(own.owns(f.first, c), "an ungrouped but still selected member remains controlled");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void actualCommandEntryTracksOwnershipAndTheBookPageCannotBypassAnotherKeeper(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body a = f.resident(2), b = f.resident(4);
        List<UUID> members = List.of(a.id(), b.id());
        helper.assertFalse(SelectionOrders.submit(f.first, f.colony.id(), members, List.of(f.at(12)), -1),
            "a loaded resident is not commandable until this player has selected it");
        f.ownership.select(f.first, members);
        helper.assertTrue(SelectionOrders.submit(f.first, f.colony.id(), members, List.of(f.at(12)), -1),
            "the authorized selected squad accepts an actual move through the network service");
        UnitGroup group = UnitGroups.of(f.colony).ofResident(a.id()).orElseThrow();
        helper.assertFalse(CombatDesk.of(f.colony).act(f.second, "group", group.id().toString()),
            "another keeper cannot use the book page to release a leased RTS squad");
        helper.assertFalse(SelectionOrders.submit(f.second, f.colony.id(), members, List.of(f.at(14)), -1),
            "another keeper cannot forge the first keeper's selection in a move packet");
        f.ownership.bindGroup(f.first, 0);
        f.ownership.select(f.first, List.of());
        helper.assertTrue(ResidentCommands.of(f.colony).current(a.id()).isPresent(), "a shortcut preserves the accepted move after deselection");
        f.ownership.recallGroup(f.first, 0);
        f.ownership.bindGroup(f.first, 0);
        f.ownership.select(f.first, List.of());
        helper.assertTrue(ResidentCommands.of(f.colony).current(a.id()).isEmpty()
                && UnitGroups.of(f.colony).ofResident(a.id()).isEmpty(),
            "the actual command entry registered its generation so the last reference releases its hold and membership");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void selectionAndOverlappingShortcutsRetainExclusiveOwnershipUntilBothAreCleared(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body a = f.resident(2), b = f.resident(4);
        var own = f.ownership;
        helper.assertTrue(own.select(f.first, List.of(a.id(), b.id(), a.id(), UUID.randomUUID())), "selection accepts loaded people only");
        own.bindGroup(f.first, 0);
        own.bindGroup(f.first, 1);
        own.select(f.first, List.of());
        helper.assertTrue(own.owns(f.first, a) && own.owns(f.first, b), "hidden shortcut members retain ownership");
        helper.assertFalse(own.canSelect(f.second, a), "another keeper cannot claim a shortcut member");
        own.select(f.second, List.of(a.id()));
        helper.assertTrue(own.snapshot(f.second).selection().isEmpty(), "a competing player's forged selection is rejected");
        own.recallGroup(f.first, 0);
        long before = own.snapshot(f.first).revision();
        own.bindGroup(f.first, 0);
        var snapshot = own.snapshot(f.first);
        helper.assertTrue(snapshot.groups().get(0).isEmpty() && snapshot.selection().size() == 2,
            "binding the exact same set toggles only that shortcut");
        helper.assertTrue(snapshot.revision() > before, "a server state change advances its acknowledgement revision");
        own.select(f.first, List.of());
        helper.assertTrue(own.owns(f.first, a), "the overlapping second shortcut still retains its member");
        own.recallGroup(f.first, 1);
        own.bindGroup(f.first, 1);
        own.select(f.first, List.of());
        helper.assertFalse(own.owns(f.first, a), "clearing selection and all containing shortcuts releases the person");
        own.select(f.second, List.of(a.id()));
        helper.assertTrue(own.selected(f.second, a), "the next keeper can acquire a fully released person");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void lossOfAuthorityAndEntityUnloadReleaseEveryContainingShortcut(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(2);
        f.ownership.select(f.first, List.of(body.id()));
        f.ownership.bindGroup(f.first, 0);
        Charters.keep(f.colony, Charter.founded("new keeper", f.second.getUUID()));
        helper.assertFalse(f.ownership.owns(f.first, body), "loss of actual charter authority invalidates ownership");
        f.ownership.select(f.second, List.of(body.id()));
        f.ownership.bindGroup(f.second, 2);
        body.mob().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        var snapshot = f.ownership.snapshot(f.second);
        helper.assertTrue(snapshot.selection().isEmpty() && snapshot.groups().get(2).isEmpty(),
            "unloading removes the UUID from selection and every shortcut, rather than reclaiming a reloaded entity");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 140)
    public static void expiredTemporaryInputSelectionCannotBeResurrectedByHeartbeat(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.renew = false;
        Body body = f.resident(2);
        f.ownership.select(f.first, List.of(body.id()));
        long revision = f.ownership.snapshot(f.first).revision();
        helper.runAfterDelay(CombatSelectionOwnership.LEASE_TICKS + 1, () -> {
            helper.assertFalse(f.ownership.heartbeat(f.first), "a heartbeat cannot resurrect an expired selection union");
            var snapshot = f.ownership.snapshot(f.first);
            helper.assertTrue(snapshot.revision() > revision && snapshot.selection().isEmpty()
                && snapshot.groups().stream().allMatch(List::isEmpty), "expiry publishes a new empty authoritative state");
            helper.assertTrue(ResidentCommands.of(f.colony).current(body.id()).isEmpty()
                && UnitGroups.of(f.colony).ofResident(body.id()).isEmpty(), "temporary input never invents a task or survives its input lease");
            f.ownership.select(f.second, List.of(body.id()));
            helper.assertTrue(f.ownership.owns(f.second, body), "expired ownership is immediately claimable by another keeper");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 180)
    public static void suspendedCommandWithoutShortcutOrBookContinuesPastItsInputLease(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.renew = false;
        Body body = f.resident(2), temporary = f.resident(4);
        f.ownership.select(f.first, List.of(body.id(), temporary.id()));
        helper.assertTrue(SelectionOrders.submit(f.first, f.colony.id(), List.of(body.id()), List.of(f.at(8)), -1),
            "the real selection command creates and tracks an actual squad without requiring a numeric shortcut");
        UnitGroup group = UnitGroups.of(f.colony).ofResident(body.id()).orElseThrow();
        UUID generation = group.commandId().orElseThrow();
        helper.assertTrue(f.ownership.suspend(f.first), "leaving interaction suspends its existing references");
        f.first.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        helper.assertTrue(SelectionOrders.continuing(f.first, f.colony.id()).isPresent()
                && SelectionOrders.authorized(f.first, f.colony.id()).isEmpty(),
            "a real keeper retains continuation authority while new input still requires the book");
        helper.assertFalse(f.ownership.select(f.first, List.of(temporary.id())) || f.ownership.bindGroup(f.first, 0)
                || f.ownership.recallGroup(f.first, 0) || f.ownership.releaseSelected(f.first, List.of(body.id()))
                || SelectionOrders.submit(f.first, f.colony.id(), List.of(body.id()), List.of(f.at(10)), -1)
                || SelectionOrders.attack(f.first, f.colony.id(), List.of(body.id()), UUID.randomUUID())
                || SelectionOrders.stop(f.first, f.colony.id(), List.of(body.id())),
            "putting the book away cannot issue new selection, group, release, move, attack or stop input");
        helper.runAfterDelay(CombatSelectionOwnership.LEASE_TICKS + 30, () -> {
            helper.assertTrue(UnitGroups.of(f.colony).ofResident(body.id()).orElseThrow() == group
                    && group.commandId().filter(generation::equals).isPresent()
                    && ResidentCommands.of(f.colony).current(body.id()).isPresent(),
                "the same actual squad and accepted command continue without a book, shortcut or heartbeat");
            var state = f.ownership.snapshot(f.first);
            helper.assertTrue(state.selection().equals(List.of(body.id())) && state.groups().stream().allMatch(List::isEmpty),
                "suspension preserves the task's original selection and discards only the uncommanded temporary resident");
            helper.assertTrue(f.ownership.owns(f.first, body) && !f.ownership.owns(f.first, temporary)
                    && !f.ownership.canSelect(f.second, body) && f.ownership.canSelect(f.second, temporary),
                "background tasks retain exclusive ownership while discarded temporary selection is claimable");
            helper.assertTrue(body.mob().getX() > f.at(2).block(f.level).getX() + 1.5,
                "the ordinary runner actually keeps moving after interaction was suspended");
            Charters.keep(f.colony, Charter.founded("new keeper", f.second.getUUID()));
            f.ownership.select(f.second, List.of(body.id()));
            helper.assertTrue(!f.ownership.owns(f.first, body)
                    && ResidentCommands.of(f.colony).current(body.id()).isEmpty(),
                "real charter loss still cancels the former keeper's accepted task and releases its handle");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 180)
    public static void inputExpirySuspendsAnAcceptedTaskButReleasesItsTemporarySelection(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.renew = false;
        Body body = f.resident(2), temporary = f.resident(4);
        f.ownership.select(f.first, List.of(body.id(), temporary.id()));
        helper.assertTrue(SelectionOrders.submit(f.first, f.colony.id(), List.of(body.id()), List.of(f.at(8)), -1),
            "one selected resident receives an actual tracked task");
        UnitGroup group = UnitGroups.of(f.colony).ofResident(body.id()).orElseThrow();
        UUID generation = group.commandId().orElseThrow();
        f.first.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        helper.runAfterDelay(CombatSelectionOwnership.LEASE_TICKS + 10, () -> {
            helper.assertTrue(f.ownership.snapshot(f.first).selection().equals(List.of(body.id()))
                    && f.ownership.owns(f.first, body) && !f.ownership.owns(f.first, temporary)
                    && group.commandId().filter(generation::equals).isPresent(),
                "expiry performs the same pruning as suspension without creating a permanent mission reference");
            helper.assertTrue(f.ownership.heartbeat(f.first), "a continuing keeper can acknowledge its background task without a book");
            helper.assertTrue(f.ownership.snapshot(f.first).selection().equals(List.of(body.id())),
                "a heartbeat cannot restore the expired temporary selection or reactivate suspended input");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 180)
    public static void suspendedShortcutWithoutCommandAlsoSurvivesInputExpiryUntilExplicitRelease(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.renew = false;
        Body body = f.resident(2);
        f.ownership.select(f.first, List.of(body.id()));
        f.ownership.bindGroup(f.first, 0);
        f.ownership.suspend(f.first);
        f.first.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        helper.runAfterDelay(CombatSelectionOwnership.LEASE_TICKS + 10, () -> {
            var state = f.ownership.snapshot(f.first);
            helper.assertTrue(state.selection().isEmpty() && state.groups().get(0).equals(List.of(body.id()))
                    && f.ownership.owns(f.first, body) && !f.ownership.canSelect(f.second, body),
                "the pre-existing shortcut reference survives suspension without a task or heartbeat");
            f.first.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
            helper.assertTrue(f.ownership.recallGroup(f.first, 0)
                    && f.ownership.releaseSelected(f.first, List.of(body.id())) && !f.ownership.owns(f.first, body),
                "a fresh explicit release with the book removes all resumed selection and shortcut references");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large")
    public static void shrinkingAResumedSquadStillReleasesOnlyTheRemovedMembers(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body a = f.resident(2), b = f.resident(4), c = f.resident(6);
        List<UUID> abc = List.of(a.id(), b.id(), c.id());
        f.ownership.select(f.first, abc);
        helper.assertTrue(SelectionOrders.submit(f.first, f.colony.id(), abc, List.of(f.at(12)), -1), "ABC accepts a tracked task");
        UnitGroup group = UnitGroups.of(f.colony).ofResident(a.id()).orElseThrow();
        UUID generation = group.commandId().orElseThrow();
        f.ownership.suspend(f.first);
        f.ownership.select(f.first, List.of(a.id(), b.id()));
        helper.assertTrue(group.members().equals(Set.of(a.id(), b.id())) && group.commandId().filter(generation::equals).isPresent()
                && !f.ownership.owns(f.first, c) && ResidentCommands.of(f.colony).current(c.id()).isEmpty(),
            "ABC to AB still releases C instead of retaining an independent mission reference");
        helper.assertTrue(f.ownership.owns(f.first, a) && f.ownership.owns(f.first, b), "the retained members keep their same actual task");
        f.ownership.bindGroup(f.first, 0);
        f.ownership.bindGroup(f.first, 0);
        helper.assertTrue(f.ownership.snapshot(f.first).groups().get(0).isEmpty()
                && ResidentCommands.of(f.colony).current(a.id()).isPresent(), "exact-set shortcut toggle still preserves the selection reference");
        f.ownership.select(f.first, List.of());
        helper.assertTrue(ResidentCommands.of(f.colony).current(a.id()).isEmpty()
                && ResidentCommands.of(f.colony).current(b.id()).isEmpty(), "ordinary explicit deselection keeps the original last-reference release rule");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 600)
    public static void completedRtsPointHoldReturnsToWorkOnlyAfterSelectionAndShortcutRelease(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(2);
        body.pack().insert(new ItemStack(Items.IRON_AXE));
        Job job = new Job(f.at(2), body);
        f.colony.submit(WORK, f.level.dimension(), Grown.of(job), (id, how) -> { });
        AtomicReference<UnitGroup> group = new AtomicReference<>();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(job.working, "ordinary work actually began before RTS takeover"))
            .thenExecute(() -> {
                f.ownership.select(f.first, List.of(body.id()));
                f.ownership.bindGroup(f.first, 0);
                UnitGroups.of(f.colony).appendSelectionPoint(List.of(body), f.at(8));
                group.set(UnitGroups.of(f.colony).ofResident(body.id()).orElseThrow());
                f.ownership.trackGroup(f.first, group.get());
            })
            .thenWaitUntil(() -> helper.assertTrue(group.get().phase() == UnitGroup.Phase.HOLDING
                && group.get().captain().filter(f.at(8)::equals).isPresent(), "the real RTS open queue reaches and holds its endpoint"))
            .thenExecute(() -> f.ownership.select(f.first, List.of()))
            .thenIdle(15)
            .thenExecute(() -> {
                helper.assertTrue(ResidentCommands.of(f.colony).current(body.id()).isPresent() && !job.working,
                    "the shortcut retains both labour reservation and its tracked endpoint hold");
                f.ownership.recallGroup(f.first, 0);
                f.ownership.bindGroup(f.first, 0);
                f.ownership.select(f.first, List.of());
                helper.assertTrue(ResidentCommands.of(f.colony).current(body.id()).isEmpty()
                    && UnitGroups.of(f.colony).ofResident(body.id()).isEmpty(), "full release removes the RTS membership and actual hold handle");
            })
            .thenWaitUntil(() -> helper.assertTrue(job.working, "the same Core runner returns to ordinary work after the RTS hold is released"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void trackedAttackReleaseUsesItsExactHandleAndPreservesANewerReplacement(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(2);
        var enemy = net.minecraft.world.entity.EntityType.ZOMBIE.create(f.level);
        enemy.setNoAi(true);
        BlockPos feet = f.at(6).block(f.level);
        enemy.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
        f.level.addFreshEntity(enemy);
        f.enemies.add(enemy);
        var commands = ResidentCommands.of(f.colony);
        f.ownership.select(f.first, List.of(body.id()));
        var attacks = commands.attack(List.of(body), enemy, f.first);
        f.ownership.trackAttack(f.first, attacks);
        f.ownership.select(f.first, List.of());
        helper.assertTrue(commands.current(body.id()).isEmpty(), "clearing the union releases the tracked attack handle");
        f.ownership.select(f.first, List.of(body.id()));
        var oldAttacks = commands.attack(List.of(body), enemy, f.first);
        f.ownership.trackAttack(f.first, oldAttacks);
        var replacement = commands.hold(body, f.at(10));
        f.ownership.suspend(f.first);
        helper.assertTrue(commands.current(body.id()).orElseThrow() == replacement,
            "suspending an old attack handle cannot retain or close a newer independent command");
        helper.assertFalse(f.ownership.owns(f.first, body), "a replaced exact attack handle cannot keep selection alive during suspension");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void staleRtsTrackingCannotCancelAnIndividualOrBookPageReplacement(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(2);
        var commands = ResidentCommands.of(f.colony);
        var groups = UnitGroups.of(f.colony);
        f.ownership.select(f.first, List.of(body.id()));
        groups.commandSelectionPoints(List.of(body), List.of(f.at(8)), -1);
        UnitGroup original = groups.ofResident(body.id()).orElseThrow();
        f.ownership.trackGroup(f.first, original);
        var replacement = commands.hold(body, f.at(12));
        f.ownership.clearContext(f.first);
        helper.assertTrue(commands.current(body.id()).orElseThrow() == replacement,
            "stale group tracking closes only its original handle and preserves a newer individual order");

        f.ownership.select(f.first, List.of(body.id()));
        groups.commandSelectionPoints(List.of(body), List.of(f.at(8)), -1);
        UnitGroup reused = groups.ofResident(body.id()).orElseThrow();
        UUID generation = reused.pointCommandId().orElseThrow();
        f.ownership.trackGroup(f.first, reused);
        groups.appendSelectionPoint(List.of(body), f.at(10));
        helper.assertValueEqual(reused.pointCommandId().orElseThrow(), generation, "append preserves the authored RTS generation");
        reused.guard(new Deployment(f.at(12), f.at(12), new Vec3(1, 0, 0), new Deployment.Formation(2)));
        var pageReplacement = commands.current(body.id()).orElseThrow();
        f.ownership.suspend(f.first);
        helper.assertTrue(commands.current(body.id()).orElseThrow() == pageReplacement
            && groups.ofResident(body.id()).orElseThrow() == reused,
            "a book-page deployment on the same group replaces the RTS generation and survives old-session suspension");
        helper.assertFalse(f.ownership.owns(f.first, body), "a stale RTS generation cannot keep the replacement page command selected");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 600)
    public static void reservationInterruptsRealLabourKeepsGoodsAndAllowsCommandThenResumesAfterFullRelease(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Body body = f.resident(2);
        body.pack().insert(new ItemStack(Items.IRON_INGOT, 8));
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        axe.setDamageValue(7);
        body.pack().insert(axe);
        Job job = new Job(f.at(2), body);
        f.colony.submit(WORK, f.level.dimension(), Grown.of(job), (id, how) -> { });
        AtomicReference<ResidentCommands.Order> command = new AtomicReference<>();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(job.working, "the actual labour runner has begun the ordinary tool-using job"))
            .thenExecute(() -> {
                f.ownership.select(f.first, List.of(body.id()));
                f.ownership.bindGroup(f.first, 0);
            })
            .thenWaitUntil(() -> helper.assertValueEqual(job.releases, 1, "reservation releases active work exactly once"))
            .thenIdle(15)
            .thenExecute(() -> {
                helper.assertFalse(job.working, "the reserved body cannot retake ordinary planned work");
                f.assertGoods(body);
                command.set(ResidentCommands.of(f.colony).move(body, f.at(8)));
            })
            .thenWaitUntil(() -> helper.assertValueEqual(command.get().status(), ResidentCommands.Status.ARRIVED,
                "an explicit command executes through the same runner while selection is reserved"))
            .thenExecute(() -> f.ownership.select(f.first, List.of()))
            .thenIdle(15)
            .thenExecute(() -> {
                helper.assertFalse(job.working, "clearing visible selection does not release a shortcut member");
                f.ownership.recallGroup(f.first, 0);
                f.ownership.bindGroup(f.first, 0);
                helper.assertFalse(f.ownership.snapshot(f.first).selection().isEmpty(), "shortcut toggle preserves selection");
                f.ownership.select(f.first, List.of());
            })
            .thenWaitUntil(() -> helper.assertTrue(job.working, "ordinary work resumes only after selection and shortcuts are both clear"))
            .thenExecute(() -> {
                helper.assertValueEqual(job.releases, 1, "ordinary work was released once through all reservation ticks");
                f.assertGoods(body);
            })
            .thenSucceed();
    }

    private static final class Job implements Node {
        final NodeSpec spec;
        final UUID worker;
        boolean working;
        int releases;
        Job(WorldPos at, Body body) {
            worker = body.id();
            Workload.Continuous load = (level, who) -> { working = true; return true; };
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
        final ServerPlayer first, second;
        final CombatSelectionOwnership ownership;
        final LaborFixture labour;
        final List<Body> people = new ArrayList<>();
        final List<Entity> enemies = new ArrayList<>();
        boolean renew = true;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            first = player("selection_one");
            second = player("selection_two");
            Charters.keep(colony, Charter.founded("selection test", first.getUUID()).with(second.getUUID()));
            ownership = CombatSelectionOwnership.of(colony);
            labour = new LaborFixture(colony, level);
            for (int x = 0; x < 22; x++) for (int z = 0; z < 12; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            helper.testInfo.addListener(this);
            helper.onEachTick(() -> {
                if (renew) { ownership.heartbeat(first); ownership.heartbeat(second); }
                labour.tick();
            });
        }

        ServerPlayer player(String name) {
            ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), name));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
            BlockPos feet = helper.absolutePos(new BlockPos(10, 1, 10));
            player.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
            return player;
        }

        WorldPos at(int x) { return WorldPos.of(level, helper.absolutePos(new BlockPos(x, 1, 2))); }
        Body resident(int x) {
            Body body = PersonBody.RESIDENT.get().create(level);
            BlockPos feet = at(x).block(level);
            body.mob().moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
            colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
            body.joinColony(colony.id());
            level.addFreshEntity(body.mob());
            people.add(body);
            return body;
        }

        void assertGoods(Body body) {
            helper.assertValueEqual(body.pack().count(Items.IRON_INGOT), 8, "reservation preserves carried materials");
            helper.assertValueEqual(body.pack().count(Items.IRON_AXE), 1, "the actual borrowed tool has exactly one owner");
            helper.assertTrue(body.pack().contents().stream().filter(stack -> stack.is(Items.IRON_AXE))
                .allMatch(stack -> stack.getDamageValue() == 7), "the borrowed tool retains its component state");
        }

        void close() {
            labour.close();
            people.forEach(body -> body.mob().discard());
            enemies.forEach(Entity::discard);
            colony.raze();
            first.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            second.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
