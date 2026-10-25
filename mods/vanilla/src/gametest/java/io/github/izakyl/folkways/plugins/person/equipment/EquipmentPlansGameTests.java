package io.github.izakyl.folkways.plugins.person.equipment;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ReservedInput;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroups;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EquipmentPlansGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(EquipmentPlansGameTests.class); }

    @GameTest(template = "empty_large", batch = "equipment_plans")
    public static void sharedCustomTemplatesPersistOnlyItemIdsAndApplyingNeverCreatesItemsOrStartsWork(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ItemStack sword = new ItemStack(Items.IRON_SWORD); sword.setDamageValue(31);
            sword.set(DataComponents.CUSTOM_NAME, Component.literal("unrepeatable heirloom"));
            PersonalItems.mark(f.body, sword, "mainhand"); f.body.pack().setItem(0, sword);
            f.act("template_save", "", "self", "Shared guard");
            var templates = f.plans.snapshot(f.player, f.body).getList("templates", Tag.TAG_COMPOUND);
            CompoundTag saved = templates.getCompound(templates.size() - 1);
            helper.assertValueEqual(saved.getCompound("targets").getString("mainhand"), "minecraft:iron_sword", "the shared template names only an item ID");
            helper.assertFalse(f.colony.kept(EquipmentPlans.ID).toString().contains("unrepeatable heirloom"), "template persistence never copies native item components");
            helper.assertTrue(f.plans.snapshot(f.player, f.body).getBoolean("shared"), "templates explicitly belong to the colony");
            f.act("template_apply", saved.getString("id"), "self", "");
            helper.assertFalse(f.plans.snapshot(f.player, f.body).getCompound("goal").getBoolean("active"), "applying stores intent but never activates warehouse work");
            helper.assertTrue(f.plans.urges(f.worker, f.colony.view(f.level)).isEmpty() && ItemStack.matches(f.body.pack().getItem(0), sword), "application neither draws inventory nor queues work");
            EquipmentPlans restored = new EquipmentPlans(f.colony, f.level.getServer());
            helper.assertValueEqual(restored.snapshot(f.player, f.body).getCompound("goal").getString("id"), saved.getString("id"), "resident targets and shared template identity survive service recreation");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "equipment_plans")
    public static void anExplicitRefillInstallsTheActualAvailableGarmentAndKeepsItsOldItemAsSpare(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ItemStack old = new ItemStack(Items.LEATHER_HELMET); old.setDamageValue(8);
            f.body.setItemSlot(EquipmentSlot.HEAD, old);
            ItemStack incoming = new ItemStack(Items.IRON_HELMET); incoming.setDamageValue(27);
            incoming.set(DataComponents.CUSTOM_NAME, Component.literal("one real warehouse helmet"));
            f.store.setItem(0, incoming.copy());
            f.act("template_apply", "melee_guard", "self", "");
            helper.assertTrue(f.store.getItem(0).getCount() == 1 && f.plans.urges(f.worker, f.colony.view(f.level)).isEmpty(), "selecting a template leaves real stock untouched");
            f.act("refill", "", "self", "");
            EquipmentRefillNode node = f.next();
            helper.assertTrue(Commit.run(f.level, node, f.worker) instanceof Outcome.Done, "the real staged source draw and garment installation commit together");
            node.released(f.level, f.worker, Ending.DONE);
            ItemStack worn = f.body.getItemBySlot(EquipmentSlot.HEAD);
            helper.assertTrue(worn.is(Items.IRON_HELMET) && worn.getDamageValue() == 27
                && worn.getHoverName().getString().equals("one real warehouse helmet"), "the installed physical garment retains exact source components");
            helper.assertTrue(f.store.getItem(0).isEmpty() && !Equipment.locked(f.body, EquipmentSlot.HEAD), "one source item leaves storage and automation does not manufacture a manual lock");
            helper.assertTrue(f.body.pack().contents().stream().anyMatch(stack -> stack.is(Items.LEATHER_HELMET)
                && stack.getDamageValue() == 8 && PersonalItems.purpose(f.body, stack).filter("spare"::equals).isPresent()), "old equipment remains real protected spare stock in the same resident pack");
            helper.assertTrue(f.plans.urges(f.worker, f.colony.view(f.level)).isEmpty(), "other missing pieces do not create equipment from absent stock");
            helper.assertValueEqual(f.plans.snapshot(f.player, f.body).getString("status"), "folkways.equipment.plan.status.missing", "partial completion reports the remaining shortage");
            CompoundTag report = f.plans.snapshot(f.player, f.body);
            helper.assertValueEqual(report.getList("preview", Tag.TAG_COMPOUND).getCompound(0).getString("status"), "folkways.equipment.plan.status.missing", "the last batch result follows each member's real later status without another warehouse scan");
            var gaps = report.getList("preview", Tag.TAG_COMPOUND).getCompound(0).getList("missing", Tag.TAG_COMPOUND);
            helper.assertValueEqual(gaps.size(), 5, "the completed native helmet disappears from the batch member's live six-slot deficit list");
            helper.assertTrue(java.util.stream.IntStream.range(0, gaps.size()).mapToObj(gaps::getCompound)
                .noneMatch(gap -> gap.getString("slot").equals("head")), "the dynamic result never freezes an already filled slot as missing");
            helper.assertValueEqual(report.getString("previewKind"), "refill", "batch feedback names its original operation");
            helper.assertValueEqual(report.getString("previewScope"), "self", "batch feedback names its original scope rather than the client's newer selection");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "equipment_plans")
    public static void carriedPersonalEquipmentIsUsedFirstWhileManualLocksRemainAuthoritative(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ItemStack sword = new ItemStack(Items.IRON_SWORD); sword.setDamageValue(15);
            PersonalItems.mark(f.body, sword, "spare"); f.body.pack().setItem(0, sword);
            f.store.setItem(0, new ItemStack(Items.IRON_SWORD));
            f.act("template_apply", "melee_guard", "self", ""); f.act("refill", "", "self", "");
            EquipmentRefillNode node = f.next();
            helper.assertTrue(node.offer().store().isEmpty(), "a real carried spare is preferred before warehouse stock");
            helper.assertTrue(Commit.run(f.level, node, f.worker) instanceof Outcome.Done, "the resident can assign its existing physical spare through the same runner path");
            node.released(f.level, f.worker, Ending.DONE);
            helper.assertTrue(ResidentEquipment.assigned(f.body).getDamageValue() == 15 && f.store.getItem(0).is(Items.IRON_SWORD), "the source spare is assigned and the warehouse sword is not taken");
            Equipment.lock(f.body, EquipmentSlot.HEAD, true); f.store.setItem(1, new ItemStack(Items.IRON_HELMET));
            helper.assertTrue(f.plans.urges(f.worker, f.colony.view(f.level)).isEmpty(), "a locked empty helmet slot is never filled automatically");
            helper.assertValueEqual(f.plans.snapshot(f.player, f.body).getString("status"), "folkways.equipment.plan.status.locked", "the blocked manual choice is observable");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "equipment_plans")
    public static void changingTargetsOrIssuingMovementInvalidatesTheOldPhysicalPickup(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.store.setItem(0, new ItemStack(Items.IRON_HELMET));
            f.act("template_apply", "melee_guard", "self", ""); f.act("refill", "", "self", "");
            EquipmentRefillNode obsolete = f.next();
            f.act("template_apply", "archer", "self", "");
            helper.assertFalse(obsolete.ready(f.level), "a newer saved goal immediately revokes the old transient pickup");
            helper.assertTrue(Commit.run(f.level, obsolete, f.worker) instanceof Outcome.Failed && f.store.getItem(0).is(Items.IRON_HELMET), "a stale node cannot debit its former physical source");
            f.store.setItem(1, new ItemStack(Items.BOW));
            f.act("refill", "", "self", ""); EquipmentRefillNode next = f.next();
            var order = ResidentCommands.of(f.colony).move(f.body, WorldPos.of(f.level, f.body.blockPosition().east(5)));
            helper.assertFalse(next.ready(f.level), "a new movement order revokes a pickup captured while idle");
            helper.assertTrue(f.plans.urges(f.worker, f.colony.view(f.level)).isEmpty()
                && ResidentCommands.of(f.colony).current(f.body.id()).orElseThrow() == order, "refill waits without replacing the command or its route");
            helper.assertTrue(f.store.getItem(1).is(Items.BOW), "moving never teleports the bow out of storage");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "equipment_plans")
    public static void fullPacksRefuseReplacementAndRefundTheExactDrawnSource(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            for (int cell = 0; cell < f.body.pack().getContainerSize(); cell++) f.body.pack().setItem(cell, new ItemStack(Items.STONE, 64));
            ItemStack old = new ItemStack(Items.LEATHER_HELMET); old.setDamageValue(12); f.body.setItemSlot(EquipmentSlot.HEAD, old);
            ItemStack incoming = new ItemStack(Items.IRON_HELMET); incoming.setDamageValue(19); f.store.setItem(0, incoming.copy());
            f.act("template_apply", "melee_guard", "self", ""); f.act("refill", "", "self", "");
            EquipmentRefillNode node = f.next();
            helper.assertTrue(Commit.run(f.level, node, f.worker) instanceof Outcome.Failed, "replacement cannot discard an old item when no real spare cell exists");
            node.released(f.level, f.worker, new Ending.Failed(node.id(), LaborRefusal.NOWHERE_TO_PUT_IT));
            helper.assertTrue(ItemStack.matches(f.store.getItem(0), incoming) && f.body.getItemBySlot(EquipmentSlot.HEAD) == old,
                "the staged draw returns its exact original components and the real old helmet stays worn");
            helper.assertValueEqual(f.plans.snapshot(f.player, f.body).getString("status"), "folkways.equipment.plan.status.no_room", "capacity failure explains why the real request waits");
            helper.assertValueEqual(f.body.pack().count(Items.STONE), 18 * 64, "the original shared eighteen-cell inventory is unchanged");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "equipment_plans")
    public static void reservationSnapshotsExcludeOtherWorkAndAnActiveRequestSurvivesReloadWithoutAReservationCopy(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.store.setItem(0, new ItemStack(Items.IRON_HELMET));
            ReservedInput claim = new ReservedInput(UUID.randomUUID(), Optional.of(f.storeAt), Optional.empty(),
                Goods.specOf(f.store.getItem(0)), 1);
            Colony claimed = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[]{Colony.class},
                (proxy, method, arguments) -> method.getName().equals("reservedInputs") ? List.of(claim) : method.invoke(f.colony, arguments));
            EquipmentPlans plans = new EquipmentPlans(claimed, f.level.getServer());
            CompoundTag apply = new CompoundTag(); apply.putString("kind", "template_apply"); apply.putString("template", "melee_guard"); apply.putString("scope", "self");
            plans.act(f.player, f.body, apply);
            CompoundTag refill = new CompoundTag(); refill.putString("kind", "refill"); refill.putString("scope", "self"); plans.act(f.player, f.body, refill);
            helper.assertTrue(plans.urges(f.worker, f.colony.view(f.level)).isEmpty(), "an actual reserved-input snapshot excludes the only physical warehouse helmet");
            helper.assertTrue(f.store.getItem(0).is(Items.IRON_HELMET), "preview and claim reconciliation never debit inventory");
            EquipmentPlans restored = new EquipmentPlans(f.colony, f.level.getServer());
            helper.assertTrue(restored.snapshot(f.player, f.body).getCompound("goal").getBoolean("active"), "the pending intention survives service reload");
            helper.assertTrue(restored.snapshot(f.player, f.body).getCompound("goal").getBoolean("builtin"), "a reloaded built-in goal retains its localized template identity");
            helper.assertFalse(f.colony.kept(EquipmentPlans.ID).toString().contains(claim.node().toString()), "no live reservation or task handle is copied into saved targets");
            helper.assertTrue(!restored.urges(f.worker, f.colony.view(f.level)).isEmpty(), "after a fresh unreserved snapshot, a new live pickup is reconstructed from real stock");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "equipment_plans")
    public static void actualGroupApplicationNamesAllMembersAndSkipsUnloadedResidentsIndividually(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ResidentEntity other = f.add();
            try {
                UnitGroups.of(f.colony).create(List.of(f.body, other));
                other.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                var scope = f.plans.snapshot(f.player, f.body).getCompound("scopes").getCompound("group");
                helper.assertValueEqual(scope.getInt("count"), 2, "group scope is actual UnitGroup membership rather than a shortcut or one visible row");
                f.act("template_apply", "archer", "group", "");
                var preview = f.plans.snapshot(f.player, f.body).getList("preview", Tag.TAG_COMPOUND);
                helper.assertValueEqual(preview.size(), 2, "batch feedback lists every actual member");
                helper.assertTrue(java.util.stream.IntStream.range(0, preview.size()).mapToObj(preview::getCompound)
                    .anyMatch(row -> row.getUUID("resident").equals(other.id()) && row.getString("status").endsWith("unloaded")), "an unloaded resident receives explicit feedback instead of a remote equipment mutation");
                helper.assertTrue(f.plans.snapshot(f.player, f.body).getCompound("goal").getString("id").equals("archer"), "the separately authorized loaded member receives the target");
            } finally { other.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "equipment_plans_runner", timeoutTicks = 1300)
    public static void aRealGuardLendsTheRunnerToRefillReturnsThenYieldsToANewMoveAndRealFear(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.body.setNoAi(false);
        f.body.getRandom().setSeed(42L);
        for (int x = 0; x < 22; x++) for (int z = 0; z < 12; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        BlockPos anchorCell = f.body.blockPosition();
        WorldPos anchor = WorldPos.of(f.level, anchorCell);
        BlockPos warehouseCell = helper.absolutePos(new BlockPos(15, 1, 2));
        f.level.setBlockAndUpdate(warehouseCell, Blocks.BARREL.defaultBlockState());
        Container warehouse = Containers.at(f.level, warehouseCell).orElseThrow();
        f.colony.stores(EquipmentPlans.ID, f.level.dimension(), List.of(WorldPos.of(f.level, warehouseCell)));
        ItemStack actualHelmet = new ItemStack(Items.IRON_HELMET);
        actualHelmet.setDamageValue(21);
        actualHelmet.set(DataComponents.CUSTOM_NAME, Component.literal("one remote physical helmet"));
        f.body.setItemSlot(EquipmentSlot.HEAD, actualHelmet);
        f.act("template_save", "", "self", "Runner helmet");
        var choices = f.plans.snapshot(f.player, f.body).getList("templates", Tag.TAG_COMPOUND);
        String custom = choices.getCompound(choices.size() - 1).getString("id");
        f.body.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        warehouse.setItem(0, actualHelmet);
        f.act("template_apply", custom, "self", "");
        var commands = ResidentCommands.of(f.colony);
        var guard = commands.guard(f.body, anchor, new net.minecraft.world.phys.Vec3(1, 0, 0));
        var simulation = new io.github.izakyl.folkways.core.engine.labor.LaborFixture(f.colony, f.level);
        java.util.concurrent.atomic.AtomicReference<ResidentCommands.Order> replacement = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ResidentCommands.Order> nextGuard = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<net.minecraft.world.entity.monster.Zombie> enemy = new java.util.concurrent.atomic.AtomicReference<>();
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) { }
            private void close() { simulation.close(); if (enemy.get() != null) enemy.get().discard(); f.close(); }
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { close(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { close(); }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo original, net.minecraft.gametest.framework.GameTestInfo rerun, net.minecraft.gametest.framework.GameTestRunner runner) { }
        });
        helper.onEachTick(() -> {
            simulation.tick();
            if (f.level.getGameTime() % 40 == 0) {
                var goal = f.plans.snapshot(f.player, f.body).getCompound("goal");
                org.slf4j.LoggerFactory.getLogger("folkways-equipment-test").info(
                    "runner position={} doing={} currentOrder={} goal={} active={} lease={} warehouseHelmet={}",
                    f.body.blockPosition(), f.body.doing().map(doing -> doing.toward().orElse(doing.what()).toString()).orElse("idle"),
                    commands.current(f.body.id()).map(order -> order.mode() + "/" + order.status()).orElse("none"),
                    goal.getString("status"), goal.getBoolean("active"), EquipmentPlans.lendsRunner(f.colony, f.body.id()), warehouse.getItem(0).getCount());
            }
        });
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(guard.status() == ResidentCommands.Status.HOLDING,
                "the actual runner first reaches and holds the original guard slot"))
            .thenExecute(() -> {
                f.act("refill", "", "self", "");
                helper.assertTrue(f.plans.snapshot(f.player, f.body).getCompound("goal").getBoolean("active"),
                    "the explicit refill ticket activates its finite request");
            })
            .thenWaitUntil(() -> helper.assertTrue(f.body.doing().filter(doing -> doing.what().equals(EquipmentPlans.ID)).isPresent()
                && f.body.getX() > anchorCell.getX() + 3 && warehouse.getItem(0).is(Items.IRON_HELMET),
                "the same runner physically leaves the anchor while the remote warehouse still owns its item"))
            .thenExecute(() -> helper.assertTrue(commands.current(f.body.id()).orElseThrow() == guard
                && EquipmentPlans.lendsRunner(f.colony, f.body.id()), "leaving the anchor keeps the captured original guard lease valid"))
            .thenWaitUntil(() -> helper.assertTrue(f.body.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && warehouse.isEmpty(),
                "real travel and staged draw move the one remote helmet into its native slot"))
            .thenWaitUntil(() -> helper.assertTrue(guard.status() == ResidentCommands.Status.HOLDING
                && io.github.izakyl.folkways.core.api.terms.Reach.standingAt(f.body.position(), anchorCell),
                "the unchanged guard command takes the same runner back to its original anchor"))
            .thenExecute(() -> {
                helper.assertTrue(commands.current(f.body.id()).orElseThrow() == guard
                    && f.body.getItemBySlot(EquipmentSlot.HEAD).getDamageValue() == 21, "the original handle and physical durability survive the round trip");
                ItemStack returned = f.body.getItemBySlot(EquipmentSlot.HEAD);
                f.body.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY); ResidentEquipment.released(returned); warehouse.setItem(0, returned);
                f.act("refill", "", "self", "");
            })
            .thenWaitUntil(() -> helper.assertTrue(f.body.doing().filter(doing -> doing.what().equals(EquipmentPlans.ID)).isPresent()
                && f.body.getX() > anchorCell.getX() + 3, "a second real pickup has physically started"))
            .thenExecute(() -> {
                replacement.set(commands.move(f.body, WorldPos.of(f.level, helper.absolutePos(new BlockPos(4, 1, 6)))));
                helper.assertFalse(EquipmentPlans.lendsRunner(f.colony, f.body.id()), "new movement immediately revokes the old guard pickup lease");
                f.act("refill_cancel", "", "self", "");
            })
            .thenWaitUntil(() -> helper.assertTrue(replacement.get().status() == ResidentCommands.Status.ARRIVED,
                "the movement replacement actually completes through the existing runner"))
            .thenExecute(() -> {
                helper.assertTrue(warehouse.getItem(0).is(Items.IRON_HELMET) && f.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                    "the cancelled old pickup never steals the remote physical item");
                nextGuard.set(commands.guard(f.body, WorldPos.of(f.level, f.body.blockPosition()), new net.minecraft.world.phys.Vec3(1, 0, 0)));
            })
            .thenWaitUntil(() -> helper.assertTrue(nextGuard.get().status() == ResidentCommands.Status.HOLDING,
                "a fresh guard settles before another explicit refill"))
            .thenExecute(() -> {
                f.act("refill", "", "self", "");
                helper.assertTrue(f.plans.snapshot(f.player, f.body).getCompound("goal").getBoolean("active"),
                    "the explicit refill ticket activates its finite request");
            })
            .thenWaitUntil(() -> helper.assertTrue(f.body.doing().filter(doing -> doing.what().equals(EquipmentPlans.ID)).isPresent()
                && f.body.getX() > helper.absolutePos(new BlockPos(6, 1, 6)).getX(), "a third refill owns the runner before real danger arrives"))
            .thenExecute(() -> {
                var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(f.level); zombie.setNoAi(true);
                zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
                zombie.moveTo(f.body.getX() + 2, f.body.getY(), f.body.getZ()); zombie.setTarget(f.body); f.level.addFreshEntity(zombie); enemy.set(zombie);
                f.body.setHealth(f.body.getMaxHealth() * .3F); f.body.hurt(f.level.damageSources().mobAttack(zombie), .01F);
                helper.assertFalse(EquipmentPlans.lendsRunner(f.colony, f.body.id()), "an actual hostile pursuer immediately invalidates equipment's runner lease");
            })
            .thenWaitUntil(() -> helper.assertTrue(f.body.doing().filter(doing -> doing.toward().orElse(doing.what())
                .equals(io.github.izakyl.folkways.plugins.person.fear.FearContent.FLEEING)).isPresent(),
                "fear takes the same real runner before equipment collection can continue"))
            .thenExecute(() -> {
                helper.assertTrue(warehouse.getItem(0).is(Items.IRON_HELMET) && f.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
                    "danger does not teleport, consume or duplicate the waiting warehouse helmet");
                helper.assertTrue(commands.current(f.body.id()).orElseThrow() == nextGuard.get(), "danger preserves the guard's original return handle");
                f.act("refill_cancel", "", "self", "");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "equipment_plans")
    public static void batchFeedbackCannotClaimOldTargetsForRejectedMembersOrFollowANewerKeepersGoal(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ResidentEntity other = f.add();
            UnitGroups.of(f.colony).create(List.of(f.body, other));
            java.util.concurrent.atomic.AtomicBoolean denyOther = new java.util.concurrent.atomic.AtomicBoolean();
            Colony access = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[]{Colony.class},
                (proxy, method, arguments) -> method.getName().equals("holdingOf") && denyOther.get()
                    && arguments[0].equals(other.id()) ? Optional.empty() : method.invoke(f.colony, arguments));
            EquipmentPlans plans = new EquipmentPlans(access, f.level.getServer());
            CompoundTag apply = new CompoundTag(); apply.putString("kind", "template_apply"); apply.putString("scope", "self");
            apply.putString("template", "melee_guard");
            helper.assertValueEqual(plans.act(f.player, other, apply), "ok", "the later-rejected resident already owns an older melee target");
            denyOther.set(true); apply.putString("scope", "group"); apply.putString("template", "archer");
            helper.assertValueEqual(plans.act(f.player, f.body, apply), "partial", "the new actual-group action rejects that resident independently");
            denyOther.set(false);
            helper.assertValueEqual(plans.snapshot(f.player, other).getCompound("goal").getString("id"), "melee_guard", "restored access exposes the old unchanged target as a different intention");
            var report = plans.snapshot(f.player, f.body).getList("preview", Tag.TAG_COMPOUND);
            CompoundTag rejected = java.util.stream.IntStream.range(0, report.size()).mapToObj(report::getCompound)
                .filter(row -> row.getUUID("resident").equals(other.id())).findFirst().orElseThrow();
            helper.assertFalse(rejected.getBoolean("accepted"), "the batch records that this member was never accepted");
            helper.assertValueEqual(rejected.getString("status"), "folkways.equipment.plan.status.forbidden", "regaining access cannot disguise an older target as this batch's success");
            helper.assertTrue(rejected.getList("missing", Tag.TAG_COMPOUND).isEmpty(), "rejected rows do not borrow deficit data from an unrelated older goal");
            ServerPlayer keeper = FakePlayerFactory.get(f.level, new GameProfile(UUID.randomUUID(), "second_equipment_keeper"));
            Charters.keep(f.colony, Charters.of(f.level.getServer(), f.colony).with(keeper.getUUID()));
            keeper.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(f.colony.id()));
            apply.putString("scope", "self"); apply.putString("template", "melee_guard");
            helper.assertValueEqual(plans.act(keeper, f.body, apply), "ok", "a separately authorized keeper changes the accepted member's target later");
            var newer = plans.snapshot(f.player, f.body).getList("preview", Tag.TAG_COMPOUND);
            CompoundTag replaced = java.util.stream.IntStream.range(0, newer.size()).mapToObj(newer::getCompound)
                .filter(row -> row.getUUID("resident").equals(f.body.id())).findFirst().orElseThrow();
            helper.assertTrue(replaced.getBoolean("accepted"), "the original batch did accept this member");
            helper.assertValueEqual(replaced.getString("status"), "folkways.equipment.plan.status.replaced", "later goal identity/revision is reported as replacement instead of another operation's success");
            helper.assertTrue(replaced.getList("missing", Tag.TAG_COMPOUND).isEmpty(), "the old operation never displays a newer target's deficits");
        }
        helper.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ServerPlayer player;
        final ResidentEntity body;
        final EquipmentPlans plans;
        final WorldPos storeAt;
        final Container store;
        final Worker worker;
        final List<ResidentEntity> residents = new ArrayList<>();
        Fixture(GameTestHelper helper) {
            this.helper = helper; level = helper.getLevel();
            for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "equipment_planner")); player.getInventory().clearContent();
            colony = Colonies.mint(level.getServer()); Charters.keep(colony, Charter.founded("plan tests", player.getUUID()));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            body = add(); player.moveTo(body.getX() + 1, body.getY(), body.getZ());
            BlockPos at = helper.absolutePos(new BlockPos(3, 1, 2)); level.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
            storeAt = WorldPos.of(level, at); store = Containers.at(level, at).orElseThrow();
            colony.stores(EquipmentPlans.ID, level.dimension(), List.of(storeAt)); plans = EquipmentPlans.of(colony);
            worker = new Worker() {
                public Resident resident() { return body.resident(); }
                public Mob body() { return body; }
                public Container pack() { return body.pack(); }
                public List<Store> within() { return List.of(new Store(storeAt, store)); }
                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                public void spill(ItemStack stack) { if (!stack.isEmpty()) throw new AssertionError("unexpected physical spill " + stack); }
                public Effects effects() { return body.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }
        ResidentEntity add() {
            ResidentEntity resident = PersonBody.RESIDENT.get().create(level); resident.setNoAi(true);
            BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2)); resident.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow(); resident.joinColony(colony.id()); level.addFreshEntity(resident); residents.add(resident); return resident;
        }
        void act(String kind, String template, String scope, String name) {
            CompoundTag snapshot = ResidentEquipment.read(player, body);
            CompoundTag action = new CompoundTag(); action.putUUID("resident", body.id()); action.putUUID("ticket", snapshot.getUUID("ticket"));
            action.putString("kind", kind); action.putString("template", template); action.putString("scope", scope); action.putString("name", name);
            ResidentEquipment.act(player, body, action);
        }
        EquipmentRefillNode next() { return (EquipmentRefillNode) plans.urges(worker, colony.view(level)).getFirst().take().get(); }
        @Override public void close() { residents.forEach(ResidentEntity::discard); colony.raze(); player.getInventory().clearContent(); }
    }
}
