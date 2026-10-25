package io.github.izakyl.folkways.plugins.person.equipment;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
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
public final class DefaultEquipmentGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(DefaultEquipmentGameTests.class); }

    @GameTest(template = "empty_large", batch = "equipment_defaults")
    public static void colonyDefaultsAreOffUntilAnAuthorizedRevisionCheckedPolicyIsSaved(GameTestHelper helper) {
        try (Scene scene = new Scene(helper, false)) {
            helper.assertFalse(scene.plans.defaultSnapshot().getBoolean("enabled"), "existing colonies start with no automatic outfit policy");
            CompoundTag desired = scene.settings(true, "bow", 16); desired.putBoolean("head", true);
            ServerPlayer outsider = FakePlayerFactory.get(scene.level, new GameProfile(UUID.randomUUID(), "default_outsider"));
            helper.assertValueEqual(scene.plans.updateDefaults(outsider, desired), "forbidden", "another player cannot change this colony's shared policy");
            helper.assertValueEqual(scene.plans.updateDefaults(scene.player, desired), "ok", "the charter keeper may save the colony policy");
            helper.assertValueEqual(scene.plans.updateDefaults(scene.player, desired), "stale", "an older configuration revision cannot overwrite a newer policy");
            EquipmentPlans restored = new EquipmentPlans(scene.colony, scene.level.getServer());
            helper.assertTrue(restored.defaultSnapshot().getBoolean("head") && restored.defaultSnapshot().getInt("ammo") == 16,
                "service recreation restores the item-ID policy without an inventory copy");
            helper.assertTrue(scene.body.pack().isEmpty() && scene.store.isEmpty(), "saving or reading defaults never creates weapons, armor or ammunition");
            helper.assertFalse(scene.colony.kept(DefaultEquipment.ID).toString().contains("minecraft:bow"), "persistence stores the bounded weapon choice, not a serialized physical stack");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "equipment_default_protection", timeoutTicks = 180)
    public static void defaultsRespectEmptyManualLocksPersonalAssignmentsAndExplicitResidentGoals(GameTestHelper helper) {
        Scene scene = new Scene(helper, false); scene.listen();
        CompoundTag desired = scene.settings(true, "bow", 8); desired.putBoolean("head", true);
        scene.plans.updateDefaults(scene.player, desired);
        ItemStack sword = new ItemStack(Items.IRON_SWORD); PersonalItems.mark(scene.body, sword, "mainhand"); scene.body.pack().setItem(0, sword);
        Equipment.lock(scene.body, EquipmentSlot.HEAD, true);
        scene.store.setItem(0, new ItemStack(Items.IRON_HELMET)); scene.store.setItem(1, new ItemStack(Items.BOW));
        scene.plans.urges(scene.worker, scene.colony.view(scene.level));
        helper.startSequence().thenIdle(101).thenExecute(() -> {
            helper.assertTrue(scene.plans.urges(scene.worker, scene.colony.view(scene.level)).isEmpty(), "neither a locked empty helmet slot nor personal sword is replaced by the colony default");
            Equipment.lock(scene.body, EquipmentSlot.HEAD, false);
            CompoundTag explicit = new CompoundTag(); explicit.putString("kind", "template_apply"); explicit.putString("template", "archer");
            helper.assertValueEqual(scene.plans.act(scene.player, scene.body, explicit), "ok", "an explicit personal target takes priority even before its refill is activated");
            helper.assertTrue(scene.plans.urges(scene.worker, scene.colony.view(scene.level)).isEmpty(), "a selected but inactive personal target suppresses inherited equipment work");
            helper.assertTrue(scene.store.getItem(0).getCount() == 1 && scene.store.getItem(1).getCount() == 1 && scene.body.pack().count(Items.IRON_SWORD) == 1,
                "all rejected and overridden requests leave the original physical items intact");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "equipment_default_revision", timeoutTicks = 180)
    public static void changingOrDisablingDefaultsRevokesTheCapturedFiniteTripWithoutTakingItsSource(GameTestHelper helper) {
        Scene scene = new Scene(helper, false); scene.listen();
        CompoundTag settings = scene.settings(true, "none", 8); settings.putBoolean("head", true);
        scene.plans.updateDefaults(scene.player, settings); scene.store.setItem(0, new ItemStack(Items.IRON_HELMET));
        scene.plans.urges(scene.worker, scene.colony.view(scene.level));
        helper.startSequence().thenIdle(101).thenExecute(() -> {
            Node old = scene.plans.urges(scene.worker, scene.colony.view(scene.level)).getFirst().take().get();
            helper.assertTrue(old.ready(scene.level), "the inherited request captures a real source after quiet stabilization");
            CompoundTag disabled = scene.plans.defaultSnapshot(); disabled.putBoolean("enabled", false);
            helper.assertValueEqual(scene.plans.updateDefaults(scene.player, disabled), "ok", "disabling policy saves a new revision");
            helper.assertFalse(old.ready(scene.level), "a previously captured inherited request cannot execute after the policy changes");
            helper.assertTrue(scene.store.getItem(0).getCount() == 1 && scene.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "revocation consumes nothing and does not manufacture the missing helmet");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "equipment_default_no_station", timeoutTicks = 100)
    public static void missingStationIsVisibleAndProductionBackpressureNeverCreatesFreeEquipment(GameTestHelper helper) {
        Scene scene = new Scene(helper, true); scene.listen();
        for (int index = 0; index < 4; index++) scene.add(false);
        CompoundTag settings = scene.settings(true, "none", 8); settings.putBoolean("head", true); scene.plans.updateDefaults(scene.player, settings);
        scene.store.setItem(0, new ItemStack(Items.IRON_INGOT, 25));
        helper.startSequence().thenIdle(45).thenExecute(() -> {
            CompoundTag state = scene.plans.defaultSnapshot();
            helper.assertTrue(state.getInt("production") >= 0 && state.getInt("production") <= 2
                && state.getInt("planned") >= 0 && state.getInt("planned") <= 2, "a colony-wide shortage never exceeds two finite planned or submitted production requests: " + state);
            helper.assertValueEqual(state.getInt("production"), 0, "without a registered workbench no production Delivery is actually submitted");
            helper.assertFalse(state.getString("status").endsWith("working"), "a missing workshop is not reported as manufacturing in progress");
            helper.assertTrue(state.getList("parts", Tag.TAG_COMPOUND).stream().map(tag -> (CompoundTag) tag)
                .anyMatch(part -> part.getString("reason").equals("no_station")), "the missing registered crafting table is exposed on a real production part: " + state);
            helper.assertValueEqual(scene.store.getItem(0).getCount(), 25, "without a registered workshop the original iron remains real stock");
            helper.assertTrue(scene.residents.stream().allMatch(body -> body.getItemBySlot(EquipmentSlot.HEAD).isEmpty()), "no default policy bypasses manufacturing to conjure a helmet");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "equipment_default_manufacturing", timeoutTicks = 2200)
    public static void realCraftingConsumesFiveIngotsAndTheRunnerEquipsOneHelmetForCurrentThenNewResidents(GameTestHelper helper) {
        Scene scene = new Scene(helper, true); scene.listen();
        scene.manufacturingOnly = true; scene.residents.forEach(scene::manufacturingWorker);
        // This test budgets exactly two helmets. Unrelated automatic vocation kits must not spend its iron.
        helper.assertTrue(io.github.izakyl.folkways.front.engine.colony.ColonyFront.of(scene.colony).setSetting(
            io.github.izakyl.folkways.front.api.CoreSettings.CORE, io.github.izakyl.folkways.front.api.CoreSettings.TOOL_KEY,
            new io.github.izakyl.folkways.front.engine.colony.ColonySettings.Value.Items(List.of())), "the manufacturing fixture isolates its exact ten-ingot material budget from unrelated vocation tool orders");
        helper.assertTrue(scene.body.licences().of(WaresContent.trade()).isPresent(), "the worker retains the real crafting vocation and still executes native recipes");        BlockPos table = helper.absolutePos(new BlockPos(8, 1, 4)); scene.level.setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
        var registration = scene.colony.hold(WaresContent.STATION, new Held.Block(WorldPos.of(scene.level, table)));
        helper.assertTrue(registration.isPresent(), "the real Wares crafting-table block entity registers as a block site: " + scene.level.getBlockEntity(table));
        scene.store.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        CompoundTag settings = scene.settings(true, "none", 8); settings.putBoolean("head", true); scene.plans.updateDefaults(scene.player, settings);
        final ResidentEntity[] newcomer = {null};
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(scene.body.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "an actual recipe, finite material draws and the normal equipment runner produce the current resident's helmet" + scene.debug()))
            .thenExecute(() -> {
                helper.assertValueEqual(scene.ironTotal(), 5, "one physical iron helmet consumes exactly five of the ten real ingots");
                helper.assertTrue(PersonalItems.belongsTo(scene.body, scene.body.getItemBySlot(EquipmentSlot.HEAD))
                    && !Equipment.locked(scene.body, EquipmentSlot.HEAD), "automatically acquired native armor is personal property without a manual lock");
                newcomer[0] = scene.add(false);
            })
            .thenWaitUntil(() -> helper.assertTrue(newcomer[0].getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "a later colony member inherits the same policy through real production" + scene.debug()))
            .thenExecute(() -> {
                helper.assertValueEqual(scene.ironTotal(), 0, "the second real recipe consumes the remaining five ingots exactly once");
                CompoundTag disabled = scene.plans.defaultSnapshot(); disabled.putBoolean("enabled", false); scene.plans.updateDefaults(scene.player, disabled);
            }).thenIdle(40).thenExecute(() -> {
                helper.assertTrue(scene.body.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && newcomer[0].getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "turning defaults off preserves both already owned physical helmets");
                helper.assertValueEqual(scene.residents.stream().mapToInt(body -> body.pack().count(Items.IRON_HELMET)).sum(), 0, "the two worn helmets have no inventory duplicates");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "equipment_default_existing_bow")
    public static void anExistingUnlockedAssignedBowAdoptsTheDefaultReserveWithoutBeingReissued(GameTestHelper helper) {
        try (Scene scene = new Scene(helper, false)) {
            ItemStack bow = new ItemStack(Items.BOW); PersonalItems.mark(scene.body, bow, "mainhand"); scene.body.pack().setItem(0, bow);
            scene.body.setDoing(io.github.izakyl.folkways.core.api.work.Doing.heading(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways", "strolling"), Optional.empty(), io.github.izakyl.folkways.core.api.work.Doing.Wares.NONE));
            helper.assertTrue(scene.plans.safe(scene.body), "idle strolling lends its runner for equipment maintenance");
            scene.body.setDoing(io.github.izakyl.folkways.core.api.work.Doing.heading(EquipmentPlans.ID, Optional.empty(), io.github.izakyl.folkways.core.api.work.Doing.Wares.NONE));
            helper.assertTrue(scene.plans.safe(scene.body), "walking toward the captured equipment request remains safe during its real warehouse trip");
            scene.body.setDoing(io.github.izakyl.folkways.core.api.work.Doing.heading(WaresContent.CRAFTING, Optional.empty(), io.github.izakyl.folkways.core.api.work.Doing.Wares.NONE));
            helper.assertFalse(scene.plans.safe(scene.body), "walking toward ordinary production is never mistaken for idle maintenance");
            scene.body.clearDoing();
            ResidentEquipment.target(scene.body, 8);
            helper.assertValueEqual(scene.plans.updateDefaults(scene.player, scene.settings(true, "bow", 32)), "ok", "a real existing bow may inherit a new colony reserve");
            scene.plans.urges(scene.worker, scene.colony.view(scene.level));
            helper.assertValueEqual(ResidentEquipment.ammoTarget(scene.body), 32, "the reserve is configured even though MAINHAND has no missing weapon demand");
            helper.assertValueEqual(scene.plans.ammunitionSnapshot(scene.body).getInt("target"), 32, "subsequent continuous ammunition maintenance reads the configured reserve");
            helper.assertTrue(scene.body.pack().getItem(0) == bow && scene.body.pack().count(Items.BOW) == 1, "updating the reserve never replaces or copies the actual existing bow");
            Equipment.lock(scene.body, EquipmentSlot.MAINHAND, true); ResidentEquipment.target(scene.body, 16);
            scene.plans.urges(scene.worker, scene.colony.view(scene.level));
            helper.assertValueEqual(ResidentEquipment.ammoTarget(scene.body), 16, "a later manual weapon lock protects its personal reserve from inherited policy");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "equipment_default_borrowed", timeoutTicks = 700)
    public static void anIdenticalBorrowedHelmetAndTemporaryHandNeverSettleTheDefaultAndReturningTheLoanAllowsRealAcquisition(GameTestHelper helper) {
        Scene scene = new Scene(helper, true); scene.listen();
        UUID helmetLoan = UUID.randomUUID(), handLoan = UUID.randomUUID();
        ItemStack helmet = new ItemStack(Items.IRON_HELMET); helmet.setDamageValue(17);
        ItemStack bow = new ItemStack(Items.BOW); bow.setDamageValue(23);
        scene.body.setItemSlot(EquipmentSlot.HEAD, helmet); scene.body.setItemSlot(EquipmentSlot.MAINHAND, bow);
        TaskTools.keep(helmetLoan, scene.body.id(), actual -> ItemStack.isSameItemSameComponents(actual, helmet));
        TaskTools.keep(handLoan, scene.body.id(), actual -> ItemStack.isSameItemSameComponents(actual, bow));
        ItemStack ownedHelmet = new ItemStack(Items.IRON_HELMET); ownedHelmet.setDamageValue(31); scene.store.setItem(0, ownedHelmet);
        scene.store.setItem(1, new ItemStack(Items.BOW)); scene.store.setItem(2, new ItemStack(Items.ARROW, 8));
        CompoundTag desired = scene.settings(true, "bow", 8); desired.putBoolean("head", true); scene.plans.updateDefaults(scene.player, desired);
        helper.startSequence().thenIdle(80).thenExecute(() -> {
            helper.assertTrue(ItemStack.matches(scene.body.getItemBySlot(EquipmentSlot.HEAD), helmet)
                    && scene.body.pack().countMatching(actual -> ItemStack.isSameItemSameComponents(actual, bow))
                        + (ItemStack.matches(scene.body.getMainHandItem(), bow) ? 1 : 0) == 1,
                "the borrowed helmet remains worn and the one physical temporary bow remains in its native hand or the normal stray-hand return pack: " + scene.debug());
            helper.assertTrue(scene.colony.kept(DefaultEquipment.ID).getList("settled", Tag.TAG_COMPOUND).isEmpty(),
                "a borrowed same-item helmet or temporary mainhand never settles permanent personal ownership");
            helper.assertTrue(scene.plans.defaultSnapshot().getInt("busy") > 0, "the visible default state reports temporary borrowed occupancy as busy even while other slots have outstanding demands: " + scene.plans.defaultSnapshot());
            // Return each original loan to real registered storage before releasing its ownership hook.
            scene.body.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY); scene.store.setItem(4, helmet);
            ItemStack returnedBow = ItemStack.EMPTY;
            if (ItemStack.matches(scene.body.getMainHandItem(), bow)) {
                returnedBow = scene.body.getMainHandItem(); scene.body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            } else {
                for (int cell = 0; cell < scene.body.pack().getContainerSize(); cell++) {
                    if (ItemStack.matches(scene.body.pack().getItem(cell), bow)) { returnedBow = scene.body.pack().removeItem(cell, 1); break; }
                }
            }
            helper.assertTrue(!returnedBow.isEmpty(), "the normal hand sweep returned the finite loan to a real pack cell before its warehouse return");
            scene.store.setItem(5, returnedBow);
            TaskTools.release(helmetLoan); TaskTools.release(handLoan);
        }).thenWaitUntil(() -> helper.assertTrue(scene.body.getItemBySlot(EquipmentSlot.HEAD).getDamageValue() == 31
                && PersonalItems.belongsTo(scene.body, scene.body.getItemBySlot(EquipmentSlot.HEAD))
                && !ResidentEquipment.assigned(scene.body).isEmpty(), "after real loan return the same resident acquires its actual personal default helmet and bow" + scene.debug()))
            .thenExecute(() -> {
                helper.assertTrue(ItemStack.matches(scene.store.getItem(4), helmet) && ItemStack.matches(scene.store.getItem(5), bow),
                    "the returned finite loan objects stay in storage while separate personal equipment is acquired");
                helper.assertTrue(scene.store.getItem(0).isEmpty() && scene.store.getItem(1).isEmpty(), "only the two original personal equipment sources leave the warehouse");
            }).thenSucceed();
    }
    @GameTest(template = "empty_large", batch = "equipment_default_origin", timeoutTicks = 1000)
    public static void onlyTheActualDefaultIssuedWeaponMayFollowALaterColonyChoiceAndManualTakeoverEndsItsOrigin(GameTestHelper helper) {
        Scene scene = new Scene(helper, true); scene.listen();
        scene.store.setItem(0, new ItemStack(Items.IRON_SWORD)); scene.store.setItem(1, new ItemStack(Items.BOW)); scene.store.setItem(2, new ItemStack(Items.ARROW, 8));
        scene.plans.updateDefaults(scene.player, scene.settings(true, "melee", 8));
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(ResidentEquipment.assigned(scene.body).is(Items.IRON_SWORD), "the first default actually acquires the finite source sword" + scene.debug()))
            .thenExecute(() -> {
                helper.assertTrue(DefaultEquipment.issuedTo(scene.body, ResidentEquipment.assigned(scene.body), EquipmentSlot.MAINHAND), "the physical issued sword carries its unique default origin");
                scene.plans.updateDefaults(scene.player, scene.settings(true, "bow", 8));
            })
            .thenWaitUntil(() -> helper.assertTrue(ResidentEquipment.assigned(scene.body).is(Items.BOW), "a later colony bow choice may replace its own unlocked default-issued sword" + scene.debug()))
            .thenExecute(() -> {
                helper.assertTrue(scene.body.pack().count(Items.IRON_SWORD) == 1 && scene.body.pack().count(Items.BOW) == 1, "the old sword remains one real personal spare and the new bow is not copied");
                for (boolean locked : new boolean[]{true, false}) {
                    CompoundTag seen = ResidentEquipment.read(scene.player, scene.body), action = new CompoundTag();
                    action.putUUID("resident", scene.body.id()); action.putUUID("ticket", seen.getUUID("ticket"));
                    action.putString("kind", "lock"); action.putString("slot", "mainhand"); action.putBoolean("value", locked);
                    ResidentEquipment.act(scene.player, scene.body, action);
                }
                helper.assertFalse(DefaultEquipment.issuedTo(scene.body, ResidentEquipment.assigned(scene.body), EquipmentSlot.MAINHAND), "manual takeover removes default origin even when the item ID and physical bow stay identical");
                scene.plans.updateDefaults(scene.player, scene.settings(true, "melee", 8));
            }).thenIdle(150).thenExecute(() -> {
                helper.assertTrue(ResidentEquipment.assigned(scene.body).is(Items.BOW) && !Equipment.locked(scene.body, EquipmentSlot.MAINHAND), "unlocking a manually taken-over bow never makes it an old default weapon again");
                helper.assertValueEqual(scene.body.pack().count(Items.IRON_SWORD), 1, "the colony policy never consumes or duplicates the preserved old sword");
            }).thenSucceed();
    }
    private static final class Scene implements AutoCloseable, GameTestListener {
        final GameTestHelper helper; final ServerLevel level; final Colony colony; final ServerPlayer player;
        final ResidentEntity body; final EquipmentPlans plans; final WorldPos source; final Container store; final Worker worker;
        final List<ResidentEntity> residents = new ArrayList<>(); final List<String> trace = new ArrayList<>(); final LaborFixture simulation; boolean manufacturingOnly; boolean closed;
        Scene(GameTestHelper helper, boolean running) {
            this.helper = helper; level = helper.getLevel();
            for (int x = 0; x < 23; x++) for (int z = 0; z < 12; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            colony = Colonies.mint(level.getServer()); player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "default_keeper"));
            player.getInventory().clearContent(); Charters.keep(colony, Charter.founded("equipment defaults", player.getUUID()));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id())); body = add(!running); player.moveTo(body.getX() + 1, body.getY(), body.getZ());
            BlockPos warehouse = helper.absolutePos(new BlockPos(14, 1, 3)); level.setBlockAndUpdate(warehouse, Blocks.BARREL.defaultBlockState());
            source = WorldPos.of(level, warehouse); store = Containers.at(level, warehouse).orElseThrow(); colony.stores(EquipmentPlans.ID, level.dimension(), List.of(source)); plans = EquipmentPlans.of(colony);
            worker = new Worker() {
                public Resident resident() { return body.resident(); } public Mob body() { return body; }
                public Container pack() { return body.pack(); } public List<Store> within() { return List.of(new Store(source, store)); }
                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; } public void spill(ItemStack stack) { body.spawnAtLocation(stack); }
                public Effects effects() { return body.effects(); } public List<Node> route() { return List.of(); } public Placement placement() { return Placement.NOWHERE; }
            };
            simulation = running ? new LaborFixture(colony, level) : null;
            if (running) helper.onEachTick(() -> {
                if (closed) return; simulation.tick();
                if (level.getGameTime() % 100 == 0) {
                    trace.add("t=" + level.getGameTime() + " " + physicalState());
                    if (trace.size() > 4) trace.removeFirst();
                }
            });
        }
        ResidentEntity add(boolean noAi) {
            ResidentEntity resident = PersonBody.RESIDENT.get().create(level); resident.setNoAi(noAi);
            BlockPos at = helper.absolutePos(new BlockPos(2, 1, 3 + residents.size())); resident.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow(); resident.joinColony(colony.id());
            if (manufacturingOnly) manufacturingWorker(resident);
            level.addFreshEntity(resident); residents.add(resident); return resident;
        }
        void manufacturingWorker(ResidentEntity resident) {
            for (var vocation : io.github.izakyl.folkways.core.api.vocation.Vocations.all()) {
                resident.licences().trade(vocation).ifPresent(trade -> resident.licences().allow(trade,
                    vocation.id().equals(WaresContent.CRAFTING)
                        || vocation.id().equals(io.github.izakyl.folkways.core.api.vocation.Vocations.HAULING)));
            }
            resident.pack().insert(new ItemStack(Items.BREAD, 16));
        }        CompoundTag settings(boolean enabled, String weapon, int ammo) {
            CompoundTag result = plans.defaultSnapshot(); result.putBoolean("enabled", enabled); result.putString("weapon", weapon); result.putInt("ammo", ammo); return result;
        }
        int ironTotal() { return (int) io.github.izakyl.folkways.core.api.terms.Goods.countIn(store, io.github.izakyl.folkways.core.api.terms.Goods.specOf(new ItemStack(Items.IRON_INGOT)))
            + residents.stream().mapToInt(body -> body.pack().count(Items.IRON_INGOT)).sum(); }
        String physicalState() {
            return "stock=" + contents(store) + ", people=" + residents.stream().map(resident ->
                "{id=" + resident.id() + ",at=" + resident.blockPosition() + ",pack=" + contents(resident.pack())
                    + ",native=" + ResidentEquipment.SLOTS.stream().map(slot -> slot.getName() + ":" + resident.getItemBySlot(slot).saveOptional(level.registryAccess())).toList()
                    + ",doing=" + resident.doing() + ",balk=" + resident.balk() + "}").toList()
                + ",claims=" + colony.reservedInputs(level).stream().filter(claim -> relevant(claim.goods())).limit(32).toList()
                + ",stalls=" + colony.stallsIn(level.dimension()).stream().filter(stall -> stall.goods().filter(this::relevant).isPresent()).limit(8).toList();
        }
        boolean relevant(io.github.izakyl.folkways.core.api.terms.ItemSpec goods) {
            return io.github.izakyl.folkways.core.api.terms.Goods.matches(new ItemStack(Items.IRON_INGOT), goods)
                || io.github.izakyl.folkways.core.api.terms.Goods.matches(new ItemStack(Items.IRON_HELMET), goods);
        }
        List<String> contents(Container inventory) {
            List<String> cells = new ArrayList<>();
            for (int cell = 0; cell < inventory.getContainerSize(); cell++) if (!inventory.getItem(cell).isEmpty()) {
                cells.add(cell + ":" + inventory.getItem(cell).saveOptional(level.registryAccess()));
            }
            return cells;
        }
        String debug() { return " [defaults=" + plans.defaultSnapshot() + ", " + physicalState() + ", lastFourSamples=" + trace + "]"; }
        void listen() { helper.testInfo.addListener(this); }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); } public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
        public void close() { if (closed) return; closed = true; if (simulation != null) simulation.close(); residents.forEach(resident -> { TaskTools.forget(resident.id()); resident.discard(); }); colony.raze(); player.getInventory().clearContent(); }
    }
}
