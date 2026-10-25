package io.github.izakyl.folkways.plugins.person.equipment;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ReservedInput;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponAdapter;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
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
public final class AmmunitionGameTests {
    private static final String FIXTURE = "folkways_test:ammunition_contract";
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(AmmunitionGameTests.class); }

    @SubscribeEvent public static void declare(Declaring event) {
        Weapons.register(ResourceLocation.fromNamespaceAndPath("folkways_test", "component_ammunition"), new WeaponAdapter() {
            public boolean accepts(ItemStack stack) { return stack.is(Items.BLAZE_ROD) && marker(stack).getBoolean(FIXTURE); }
            public double range() { return 12; }
            public boolean supportsAssignment() { return true; }
            public Optional<WeaponSession> open(Worker worker) { return Optional.empty(); }
            public Optional<Ammunition> ammunition(ItemStack actual, int reserve) {
                return Optional.of(new Ammunition(Goods.specOf(Items.PAPER.getDefaultInstance()),
                    stack -> marker(stack).getString("grade").equals("blue"), reserve, marker(actual).getInt("loaded")));
            }
        });
        Weapons.register(ResourceLocation.fromNamespaceAndPath("folkways_test", "undeclared_ammunition"), new WeaponAdapter() {
            public boolean accepts(ItemStack stack) { return stack.is(Items.STICK) && marker(stack).getBoolean(FIXTURE); }
            public double range() { return 20; }
            public Optional<WeaponSession> open(Worker worker) { return Optional.empty(); }
        });
    }

    private static CompoundTag marker(ItemStack stack) { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(); }
    private static ItemStack marked(net.minecraft.world.item.Item item, String grade, int count) {
        ItemStack stack = new ItemStack(item, count); CompoundTag data = new CompoundTag();
        data.putBoolean(FIXTURE, true); data.putString("grade", grade); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data)); return stack;
    }

    @GameTest(template = "empty_large", batch = "ammunition_contract")
    public static void nativeContractsUseSupportedProjectilesAndCountPaidCrossbowUnitsWhileLegacyAdaptersDeclareNothing(GameTestHelper helper) {
        var bow = Weapons.ammunition(new ItemStack(Items.BOW), 16).orElseThrow();
        helper.assertTrue(bow.accepts(new ItemStack(Items.ARROW)) && !bow.accepts(new ItemStack(Items.PAPER)), "the native declaration checks actual supported projectiles");
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(List.of(new ItemStack(Items.ARROW), new ItemStack(Items.ARROW), new ItemStack(Items.ARROW))));
        helper.assertValueEqual(Weapons.ammunition(crossbow, 8).orElseThrow().loaded(), 1, "three native Multishot copies represent one paid arrow");
        helper.assertTrue(Weapons.ammunition(marked(Items.STICK, "", 1), 8).isEmpty(), "ranged legacy adapters do not acquire guessed ammunition by default");
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "ammunition_contract")
    public static void genericComponentsAndReservationsDetermineActualWarehouseOffers(GameTestHelper helper) {
        try (Scene scene = new Scene(helper, false)) {
            scene.assign(marked(Items.BLAZE_ROD, "", 1));
            scene.store.setItem(0, marked(Items.PAPER, "red", 2)); scene.store.setItem(1, marked(Items.PAPER, "blue", 4));
            var ammo = Weapons.ammunition(scene.body).orElseThrow().demand();
            var demand = new EquipmentPlans.Demand(null, ResourceLocation.withDefaultNamespace("paper"), 8, Optional.of(ammo));
            var offer = scene.plans.offer(scene.body, demand).orElseThrow();
            helper.assertTrue(offer.cell() == 1 && offer.count() == 4, "coarse stock matches do not admit the wrong real component variant");
            for (int reservedCount : new int[]{3, 6}) {
                ReservedInput reserved = new ReservedInput(UUID.randomUUID(), Optional.of(scene.source), Optional.empty(), Goods.specOf(new ItemStack(Items.PAPER)), reservedCount);
                Colony claimed = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[]{Colony.class},
                    (proxy, method, arguments) -> method.getName().equals("reservedInputs") ? List.of(reserved) : method.invoke(scene.colony, arguments));
                var available = new EquipmentPlans(claimed, scene.level.getServer()).offer(scene.body, demand);
                if (reservedCount == 3) {
                    helper.assertTrue(available.isPresent() && available.get().cell() == 1 && available.get().count() == 3,
                        "a physical material obligation crosses component variants and leaves only the unclaimed compatible remainder");
                } else {
                    helper.assertTrue(available.isEmpty(), "another task's physical material obligation excludes the whole finite source");
                }
            }
            helper.assertTrue(scene.store.getItem(0).getCount() == 2 && scene.store.getItem(1).getCount() == 4, "offer inspection and reservation reconciliation never consume real stock");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "ammunition_components", timeoutTicks = 180)
    public static void aChangedActualAmmunitionComponentIsRecheckedAtStagedDraw(GameTestHelper helper) {
        Scene scene = new Scene(helper, false); scene.listen();
        scene.assign(marked(Items.BLAZE_ROD, "", 1)); scene.store.setItem(0, marked(Items.PAPER, "blue", 4));
        scene.plans.urges(scene.worker, scene.colony.view(scene.level));
        helper.startSequence().thenIdle(101).thenExecute(() -> {
            var node = scene.plans.urges(scene.worker, scene.colony.view(scene.level)).getFirst().take().get();
            helper.assertTrue(node.ready(scene.level), "the compatible finite source initially produces a real request");
            scene.store.setItem(0, marked(Items.PAPER, "red", 4));
            BlockPos at = scene.source.block(scene.level); scene.body.moveTo(at.getX() - 1.5, at.getY(), at.getZ() + .5);
            helper.assertTrue(Commit.run(scene.level, node, scene.worker) instanceof Outcome.Failed, "staged draw refuses a changed actual source before debiting it");
            node.released(scene.level, scene.worker, new Ending.Failed(node.id(), LaborRefusal.GOODS_GONE));
            helper.assertValueEqual(scene.store.getItem(0).getCount(), 4, "the incompatible source is still physical warehouse stock");
            helper.assertValueEqual(scene.body.pack().count(Items.PAPER), 0, "no incompatible magazine is copied into the resident pack");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "ammunition_contract")
    public static void automaticAmmunitionCanBeDisabledPersistentlyWithoutCancellingExplicitOutfitWork(GameTestHelper helper) {
        try (Scene scene = new Scene(helper, false)) {
            scene.assign(new ItemStack(Items.BOW));
            scene.act("template_apply", "archer", true); scene.act("refill", "", true);
            scene.act("ammo_auto", "", false);
            helper.assertFalse(scene.plans.ammunitionSnapshot(scene.body).getBoolean("enabled"), "the dedicated control switches off automatic ammunition");
            helper.assertTrue(scene.plans.snapshot(scene.player, scene.body).getCompound("goal").getBoolean("active"), "the separate explicit outfit request is unchanged");
            EquipmentPlans restored = new EquipmentPlans(scene.colony, scene.level.getServer());
            helper.assertFalse(restored.ammunitionSnapshot(scene.body).getBoolean("enabled"), "the user's cancellation persists with the resident after service recreation");
            helper.assertTrue(restored.snapshot(scene.player, scene.body).getCompound("goal").getBoolean("active"), "explicit outfit work persists independently too");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "ammunition_runner", timeoutTicks = 1800)
    public static void anEquippedGuardAutomaticallyRefillsReturnsAndRefillsAgainAfterRealNativeShotsCrossTheThreshold(GameTestHelper helper) {
        Scene scene = new Scene(helper, true); scene.listen();
        scene.assign(new ItemStack(Items.BOW)); scene.body.pack().setItem(1, new ItemStack(Items.ARROW, 3));
        scene.store.setItem(0, new ItemStack(Items.ARROW, 16));
        scene.act("template_save", "", true);
        var choices = scene.plans.snapshot(scene.player, scene.body).getList("templates", Tag.TAG_COMPOUND);
        scene.act("template_apply", choices.getCompound(choices.size() - 1).getString("id"), true);
        var guard = ResidentCommands.of(scene.colony).guard(scene.body, scene.anchor, new Vec3(1, 0, 0));
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(scene.body.pack().count(Items.ARROW) == 8 && scene.store.getItem(0).getCount() == 11,
                "without any explicit refill, the stable guard physically collects exactly five real arrows" + scene.debug()))
            .thenWaitUntil(() -> helper.assertTrue(guard.status() == ResidentCommands.Status.HOLDING
                && Reach.standingAt(scene.body.position(), scene.anchor.block(scene.level)), "automatic refill returns to the unchanged guard anchor" + scene.debug()))
            .thenExecute(() -> {
                helper.assertFalse(scene.plans.snapshot(scene.player, scene.body).getCompound("goal").getBoolean("active"), "the saved outfit plan remains a completed/selected one-shot intention");
                helper.assertTrue(Equipment.locked(scene.body, EquipmentSlot.MAINHAND), "a manual weapon lock never blocks its own ammunition refill");
                scene.shootUntil(7);
            })
            .thenWaitUntil(() -> helper.assertTrue(scene.shooting == null && scene.body.pack().count(Items.ARROW) == 7, "one real native bow shot consumes exactly one physical arrow"))
            .thenIdle(115)
            .thenExecute(() -> {
                helper.assertValueEqual(scene.store.getItem(0).getCount(), 11, "one spent arrow does not cause a fresh warehouse trip above the half-reserve trigger");
                scene.shootUntil(3);
            })
            .thenWaitUntil(() -> helper.assertTrue(scene.shooting == null && scene.body.pack().count(Items.ARROW) == 3, "four more real native shots cross the refill threshold"))
            .thenWaitUntil(() -> helper.assertTrue(scene.body.pack().count(Items.ARROW) == 8 && scene.store.getItem(0).getCount() == 6,
                "after danger clears, the same guard makes a second real finite refill trip" + scene.debug()))
            .thenWaitUntil(() -> helper.assertTrue(guard.status() == ResidentCommands.Status.HOLDING
                && Reach.standingAt(scene.body.position(), scene.anchor.block(scene.level)), "the second replenishment also returns through the same runner" + scene.debug()))
            .thenExecute(() -> {
                helper.assertTrue(ResidentCommands.of(scene.colony).current(scene.body.id()).orElseThrow() == guard, "ammunition maintenance never replaces the resident's original guard handle");
                helper.assertValueEqual(scene.store.getItem(0).getCount() + scene.body.pack().count(Items.ARROW), 14, "the original nineteen arrows lose only the five natively fired units");
                helper.assertTrue(scene.body.pack().contents().stream().filter(stack -> stack.is(Items.ARROW))
                    .allMatch(stack -> PersonalItems.purpose(scene.body, stack).filter("ammo"::equals).isPresent()), "warehouse refills remain real protected personal ammunition");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "ammunition_target", timeoutTicks = 180)
    public static void assignedWeaponsRevokeCapturedAmmoTripsWhenTheUsersReserveChanges(GameTestHelper helper) {
        Scene scene = new Scene(helper, false); scene.listen();
        scene.assign(new ItemStack(Items.BOW)); scene.store.setItem(0, new ItemStack(Items.ARROW, 64));
        scene.plans.urges(scene.worker, scene.colony.view(scene.level));
        helper.startSequence().thenIdle(101).thenExecute(() -> {
            Node eight = scene.plans.urges(scene.worker, scene.colony.view(scene.level)).getFirst().take().get();
            helper.assertTrue(eight.ready(scene.level), "a real assigned weapon creates its request after the stable quiet interval");
            helper.assertValueEqual(eight.spec().needs().getFirst().count(), 8L, "the initial finite request captures eight units");
            CompoundTag settings = scene.body.getPersistentData().getCompound("folkways:resident_equipment");
            settings.putInt("ammoTarget", 32); scene.body.getPersistentData().put("folkways:resident_equipment", settings);
            helper.assertFalse(eight.ready(scene.level), "raising the user's reserve revokes the captured old assigned-ammunition request");
            Node thirtyTwo = scene.plans.urges(scene.worker, scene.colony.view(scene.level)).getFirst().take().get();
            helper.assertValueEqual(thirtyTwo.spec().needs().getFirst().count(), 32L, "the replacement request uses the new actual reserve");
            settings.putInt("ammoTarget", 8);
            helper.assertFalse(thirtyTwo.ready(scene.level), "lowering the target prevents overcollection by the old request");
            helper.assertValueEqual(scene.store.getItem(0).getCount(), 64, "target edits never consume or spawn warehouse stock");
        }).thenSucceed();
    }

    private static final class Scene implements AutoCloseable, GameTestListener {
        final GameTestHelper helper; final ServerLevel level; final Colony colony; final ServerPlayer player;
        final ResidentEntity body; final EquipmentPlans plans; final WorldPos anchor; final WorldPos source;
        final Container store; final Worker worker; final LaborFixture simulation;
        WeaponSession shooting; Zombie dummy; int stopAt; boolean closed;
        Scene(GameTestHelper helper, boolean running) {
            this.helper = helper; level = helper.getLevel();
            for (int x = 0; x < 23; x++) for (int z = 0; z < 12; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            colony = Colonies.mint(level.getServer()); player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "ammo_keeper"));
            player.getInventory().clearContent(); Charters.keep(colony, Charter.founded("ammo contract", player.getUUID()));
            player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            body = PersonBody.RESIDENT.get().create(level); body.setNoAi(!running);
            BlockPos at = helper.absolutePos(new BlockPos(2, 1, 3)); body.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            level.addFreshEntity(body);
            colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow(); body.joinColony(colony.id());
            player.moveTo(body.getX() + 1, body.getY(), body.getZ()); anchor = WorldPos.of(level, at);
            BlockPos warehouse = helper.absolutePos(new BlockPos(15, 1, 3)); level.setBlockAndUpdate(warehouse, Blocks.BARREL.defaultBlockState());
            source = WorldPos.of(level, warehouse); store = Containers.at(level, warehouse).orElseThrow(); colony.stores(EquipmentPlans.ID, level.dimension(), List.of(source));
            plans = EquipmentPlans.of(colony);
            worker = new Worker() {
                public Resident resident() { return body.resident(); } public Mob body() { return body; }
                public Container pack() { return body.pack(); } public List<Store> within() { return List.of(new Store(source, store)); }
                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; } public void spill(ItemStack stack) { body.spawnAtLocation(stack); }
                public Effects effects() { return body.effects(); } public List<Node> route() { return List.of(); } public Placement placement() { return Placement.NOWHERE; }
            };
            simulation = running ? new LaborFixture(colony, level) : null;
            if (running) helper.onEachTick(() -> {
                simulation.tick();
                if (shooting != null) {
                    shooting.tick();
                    if (shooting.state() == WeaponSession.State.READY) {
                        if (body.pack().count(Items.ARROW) > stopAt) {
                            if (!shooting.fire(dummy)) throw new AssertionError("native bow cannot fire its next actual arrow");
                        } else { shooting.close(); shooting = null; dummy.discard(); dummy = null; }
                    }
                }
            });
        }
        void listen() { helper.testInfo.addListener(this); }
        void assign(ItemStack actual) { PersonalItems.mark(body, actual, "mainhand"); body.pack().setItem(0, actual); Equipment.lock(body, EquipmentSlot.MAINHAND, true); }
        void act(String kind, String template, boolean value) {
            CompoundTag seen = ResidentEquipment.read(player, body); CompoundTag action = new CompoundTag();
            action.putUUID("resident", body.id()); action.putUUID("ticket", seen.getUUID("ticket"));
            action.putString("kind", kind); action.putString("scope", "self"); action.putString("template", template); action.putString("name", "Ammo fixture"); action.putBoolean("value", value);
            ResidentEquipment.act(player, body, action);
        }
        void shootUntil(int remaining) {
            stopAt = remaining; dummy = EntityType.ZOMBIE.create(level); dummy.setNoAi(true);
            dummy.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200); dummy.setHealth(200);
            dummy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN)); dummy.moveTo(body.getX() + 6, body.getY(), body.getZ()); level.addFreshEntity(dummy);
            shooting = Weapons.open(worker).orElseThrow();
            helper.assertTrue(shooting.fire(dummy), "the actual equipped native bow begins a permitted real shot");
        }
        String debug() { return " [position=" + body.blockPosition() + ", ammo=" + plans.ammunitionSnapshot(body) + ", doing=" + body.doing() + "]"; }
        public void testStructureLoaded(GameTestInfo info) { } public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
        public void close() { if (closed) return; closed = true; if (shooting != null) shooting.close(); if (dummy != null) dummy.discard(); if (simulation != null) simulation.close(); body.discard(); colony.raze(); player.getInventory().clearContent(); }
    }
}
