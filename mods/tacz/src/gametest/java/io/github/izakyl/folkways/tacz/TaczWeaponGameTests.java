package io.github.izakyl.folkways.tacz;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.init.ModItems;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.plugins.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysTaczMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class TaczWeaponGameTests {
    private static boolean previousNoSave;
    @net.minecraft.gametest.framework.BeforeBatch(batch = "tacz_native")
    public static void beforeNativeTimers(net.minecraft.server.level.ServerLevel level) {
        // Accelerated ticks can autosave repeatedly during one native reload, cancelling the tested session.
        previousNoSave = level.noSave; level.noSave = true;
    }
    @net.minecraft.gametest.framework.AfterBatch(batch = "tacz_native")
    public static void afterNativeTimers(net.minecraft.server.level.ServerLevel level) { level.noSave = previousNoSave; }

    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(TaczWeaponGameTests.class); }

    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void componentIdentityAndAmmoCapabilityKeepCalibersSeparate(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "m1911", FireMode.SEMI, 0);
        ItemStack right = ammo("45acp", 5);
        right.set(DataComponents.CUSTOM_NAME, Component.literal("finite rounds"));
        scene.body.pack().insert(right);
        scene.body.pack().insert(ammo("9mm", 7));
        scene.body.pack().insert(new ItemStack(ModItems.AMMO_BOX.get()));
        scene.body.pack().insert(gun("ak47", FireMode.AUTO, 1));
        var choice = TaczLoadouts.selection(id("m1911"));
        PatrolSupplies.of(scene.colony).loadout(scene.body, choice);
        var keeps = PatrolSupplies.of(scene.colony).keeps(scene.body);
        helper.assertValueEqual(Goods.countIn(scene.body.pack(), TaczGuns.ammunition(id("45acp"))), 5L, "same item, different caliber is separate goods");
        helper.assertTrue(keeps.stream().anyMatch(amount -> Goods.matches(right, amount.spec())), "right rounds are retained");
        helper.assertFalse(keeps.stream().anyMatch(amount -> Goods.matches(ammo("9mm", 1), amount.spec())), "wrong caliber is not retained");
        scene.open();
        helper.assertValueEqual(((IGun) scene.body.getMainHandItem().getItem()).getGunId(scene.body.getMainHandItem()), id("m1911"), "selection equips the exact preferred gun");
        var cap = scene.body.getCapability(Capabilities.ItemHandler.ENTITY, null);
        helper.assertTrue(cap != null, "TACZ can see the actual entity inventory");
        for (int slot = 0; slot < cap.getSlots(); slot++) {
            ItemStack seen = cap.getStackInSlot(slot);
            helper.assertTrue(seen.isEmpty() || seen.is(ModItems.AMMO.get()), "capability excludes guns and creative ammo boxes");
            if (!seen.isEmpty() && ((IAmmo) seen.getItem()).getAmmoId(seen).equals(id("45acp"))) {
                helper.assertValueEqual(cap.extractItem(slot, 2, true).getCount(), 2, "simulation returns bounded rounds");
                helper.assertValueEqual(Goods.countIn(scene.body.pack(), TaczGuns.ammunition(id("45acp"))), 5L, "simulation leaves stock alone");
                helper.assertValueEqual(cap.extractItem(slot, 2, false).get(DataComponents.CUSTOM_NAME), Component.literal("finite rounds"), "actual extraction preserves components");
            }
        }
        helper.assertValueEqual(Goods.countIn(scene.body.pack(), TaczGuns.ammunition(id("45acp"))), 3L, "actual stock decreases once");
        helper.succeed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void nativeReloadConservesLooseMagazineAndChamberRoundsAcrossSave(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "m1911", FireMode.SEMI, 0);
        scene.body.pack().insert(ammo("45acp", 5));
        scene.body.pack().insert(ammo("9mm", 7));
        scene.open();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.session.reload(), "draw completes and native reload starts"))
            .thenWaitUntil(() -> helper.assertTrue(scene.session.state() == WeaponSession.State.READY && TaczGuns.loaded(scene.actual()) == 5,
                "native entity tick finishes finite reload"))
            .thenExecute(() -> {
                helper.assertValueEqual(scene.total("45acp"), 5, "loose plus magazine plus chamber is conserved");
                helper.assertValueEqual(Goods.countIn(scene.body.pack(), TaczGuns.ammunition(id("9mm"))), 7L, "wrong caliber survives");
                helper.assertValueEqual(scene.shots.get(), 0, "reload never shoots");
                CompoundTag saved = scene.body.saveWithoutId(new CompoundTag());
                helper.assertValueEqual(scene.session.state(), WeaponSession.State.CLOSED, "saving closes native reload before pack serialization");
                scene.body.load(saved);
                helper.assertValueEqual(scene.total("45acp"), 5, "saved actual gun retains all loaded rounds");
                helper.assertValueEqual(scene.body.pack().count(ModItems.MODERN_KINETIC_GUN.get()), 1, "one actual gun survives");
                helper.assertValueEqual(scene.actual().get(DataComponents.CUSTOM_NAME), Component.literal("actual TACZ gun"), "gun components survive");
            }).thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void semiAutomaticGunUsesRealBulletsAndFiniteRounds(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "m1911", FireMode.SEMI, 3);
        scene.open();
        helper.assertTrue(scene.session.continuousEngagement() && scene.session.fire(scene.target), "one draw owns a continuing engagement");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.shots.get() == 3, "all three finite rounds are fired"))
            .thenExecute(() -> scene.waitUntil = System.currentTimeMillis() + 500)
            .thenWaitUntil(() -> helper.assertTrue(System.currentTimeMillis() >= scene.waitUntil, "wait for native millisecond timers")).thenExecute(() -> {
                helper.assertTrue(scene.target.getHealth() < scene.target.getMaxHealth(), "real TACZ bullet damage reaches target");
                helper.assertValueEqual(scene.total("45acp"), 0, "no rounds are manufactured");
                helper.assertValueEqual(scene.session.state(), WeaponSession.State.STOPPING, "empty gun ends engagement");
                scene.session.close();
                helper.assertTrue(Weapons.open(worker(scene.body)).isEmpty(), "empty gun without ammo is unusable");
                scene.body.pack().insert(new ItemStack(Items.IRON_SWORD));
                WeaponSession fallback = Weapons.open(worker(scene.body)).orElseThrow();
                helper.assertTrue(scene.body.getMainHandItem().is(Items.IRON_SWORD), "usable physical melee fallback");
                fallback.close();
            }).thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void automaticGunKeepsOneHandLoanAndHonorsNativeCooldown(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "ak47", FireMode.AUTO, 6);
        scene.open(); scene.session.fire(scene.target);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.shots.get() >= 4, "automatic engagement repeats native shooting"))
            .thenExecute(() -> {
                helper.assertTrue(scene.body.ownsWeaponHand(scene.session), "same loan spans multiple discharges");
                helper.assertValueEqual(scene.total("762x39") + scene.shots.get(), 6, "finite automatic rounds are conserved");
                long shotTimestamp = IGunOperator.fromLivingEntity(scene.body).getDataHolder().shootTimestamp;
                scene.session.close(); scene.open();
                helper.assertValueEqual(IGunOperator.fromLivingEntity(scene.body).getDataHolder().shootTimestamp, shotTimestamp, "reopening does not reset native cooldown");
                helper.assertTrue(scene.body.hasWeaponSession(), "one new loan exists");
            }).thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void boltActionUsesNativeBoltAndDoesNotCreateAmmo(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "kar98", FireMode.SEMI, 3);
        scene.open(); scene.session.fire(scene.target);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.shots.get() >= 2, "native bolt feeds the next round"))
            .thenExecute(() -> helper.assertValueEqual(scene.total("792x57") + scene.shots.get(), 3, "bolt and chamber conserve rounds"))
            .thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void burstFinishesWithoutRedrawingBetweenItsRounds(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "m16a4", FireMode.BURST, 3);
        scene.open(); scene.session.fire(scene.target);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.shots.get() == 3, "native scheduled burst fires every finite round"))
            .thenExecute(() -> helper.assertValueEqual(scene.total("556x45"), 0, "burst consumes exactly three rounds"))
            .thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void oldBurstCannotReviveWhenSameActualGunIsImmediatelyReequipped(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "m16a4", FireMode.BURST, 6);
        scene.firstShot = () -> {
            WeaponSession old = scene.session;
            old.close(); scene.open(); scene.session.fire(scene.target);
            helper.assertValueEqual(old.state(), WeaponSession.State.CLOSED, "old generation closed");
            scene.advance = false;
        };
        scene.open(); scene.session.fire(scene.target);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.shots.get() > 0, "first native discharge occurs"))
            .thenExecute(() -> scene.waitUntil = System.currentTimeMillis() + 1250)
            .thenWaitUntil(() -> helper.assertTrue(System.currentTimeMillis() >= scene.waitUntil, "wait for native millisecond timers")).thenExecute(() -> {
                helper.assertValueEqual(scene.shots.get(), 1, "old delayed burst cannot fire under the new generation's permission");
                helper.assertValueEqual(scene.total("556x45"), 5, "cancelled delayed rounds remain in actual gun");
            }).thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void retreatDuringBurstCancelsDelayedDischarge(GameTestHelper helper) { cancelBurst(helper, "retreat"); }
    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void savingDuringBurstCancelsDelayedDischarge(GameTestHelper helper) { cancelBurst(helper, "save"); }
    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void unloadingDuringBurstCancelsDelayedDischarge(GameTestHelper helper) { cancelBurst(helper, "unload"); }
    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void deathDuringBurstCancelsDelayedDischarge(GameTestHelper helper) { cancelBurst(helper, "death"); }
    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void lostTargetDuringBurstCancelsDelayedDischarge(GameTestHelper helper) { cancelBurst(helper, "target"); }
    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void targetBecomingAlliedDuringBurstCancelsDelayedDischarge(GameTestHelper helper) { cancelBurst(helper, "allied"); }

    private static void cancelBurst(GameTestHelper helper, String how) {
        Fixture scene = new Fixture(helper, "m16a4", FireMode.BURST, 6);
        scene.firstShot = () -> {
            switch (how) {
                case "retreat" -> io.github.izakyl.folkways.plugins.combat.command.ResidentCommands.of(scene.colony)
                    .retreat(scene.body, io.github.izakyl.folkways.core.api.terms.WorldSpaces.at(scene.body));
                case "save" -> scene.body.load(scene.body.saveWithoutId(new CompoundTag()));
                case "unload" -> scene.body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                case "death" -> scene.body.hurt(helper.getLevel().damageSources().genericKill(), 1000);
                case "target" -> scene.target.discard();
                case "allied" -> {
                    var board = helper.getLevel().getScoreboard();
                    var team = board.addPlayerTeam("tacz_" + scene.body.getUUID().toString().substring(0, 8));
                    board.addPlayerToTeam(scene.body.getScoreboardName(), team); board.addPlayerToTeam(scene.target.getScoreboardName(), team);
                }
                default -> throw new IllegalArgumentException(how);
            }
        };
        scene.open(); scene.session.fire(scene.target);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.shots.get() == 1, "first real burst round fires"))
            .thenExecute(() -> scene.waitUntil = System.currentTimeMillis() + 1250)
            .thenWaitUntil(() -> helper.assertTrue(System.currentTimeMillis() >= scene.waitUntil, "wait for native millisecond timers")).thenExecute(() -> helper.assertValueEqual(scene.shots.get(), 1, "interrupted burst never creates a delayed round: " + how))
            .thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void cancellingReloadAndReopeningPreservesTheFiniteInventory(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "m1911", FireMode.SEMI, 0);
        scene.body.pack().insert(ammo("45acp", 5)); scene.open();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.session.reload(), "native reload starts"))
            .thenExecute(() -> scene.waitUntil = System.currentTimeMillis() + 250)
            .thenWaitUntil(() -> helper.assertTrue(System.currentTimeMillis() >= scene.waitUntil, "wait for native millisecond timers")).thenExecute(() -> {
                scene.session.close();
                helper.assertValueEqual(scene.total("45acp"), 5, "interrupted reload preserves all actual rounds");
                helper.assertTrue(!IGunOperator.fromLivingEntity(scene.body).getDataHolder().reloadStateType.isReloading(), "transient native reload cleared");
                scene.open();
            }).thenExecute(() -> scene.waitUntil = System.currentTimeMillis() + 2500)
            .thenWaitUntil(() -> helper.assertTrue(System.currentTimeMillis() >= scene.waitUntil, "wait for native millisecond timers")).thenExecute(() -> {
                helper.assertValueEqual(scene.total("45acp"), 5, "old reload cannot feed after reopening");
                helper.assertValueEqual(scene.shots.get(), 0, "cancelled reload produces no shot");
            }).thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void alliedResidentInLineOfFireHoldsFire(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "m1911", FireMode.SEMI, 3);
        ResidentEntity ally = PersonBody.RESIDENT.get().create(helper.getLevel());
        ally.setNoAi(true); ally.moveTo(scene.body.getX() + 3, scene.body.getY(), scene.body.getZ());
        helper.getLevel().addFreshEntity(ally); scene.other = ally;
        ally.joinColony(scene.colony.id());
        scene.open();
        helper.assertFalse(scene.session.fire(scene.target), "ally intersects the firing corridor");
        helper.assertValueEqual(scene.total("45acp"), 3, "holding fire spends nothing");
        helper.succeed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void burstRechecksNewlyBlockedLineBeforeEveryRound(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "m16a4", FireMode.BURST, 6);
        scene.firstShot = () -> {
            ResidentEntity ally = PersonBody.RESIDENT.get().create(helper.getLevel());
            ally.setNoAi(true); ally.moveTo(scene.body.getX() + 3, scene.body.getY(), scene.body.getZ());
            scene.colony.hold(PersonContent.ID, Held.Entity.of(ally)).orElseThrow();
            ally.joinColony(scene.colony.id()); helper.getLevel().addFreshEntity(ally); scene.other = ally;
        };
        scene.open(); scene.session.fire(scene.target);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.shots.get() > 0, "first shot fires before the ally enters"))
            .thenExecute(() -> scene.waitUntil = System.currentTimeMillis() + 1250)
            .thenWaitUntil(() -> helper.assertTrue(System.currentTimeMillis() >= scene.waitUntil, "wait for native millisecond timers")).thenExecute(() -> helper.assertValueEqual(scene.shots.get(), 1, "delayed rounds recheck the firing corridor"))
            .thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void flyingBulletKeepsColonyProtectionAfterShooterLeaves(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "m1911", FireMode.SEMI, 2);
        scene.firstShot = () -> scene.body.leaveColony();
        scene.open(); scene.session.fire(scene.target);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.bullet != null, "native bullet exists"))
            .thenExecute(() -> {
                helper.assertTrue(scene.body.colonyId().isEmpty(), "shooter already left");
                helper.assertFalse(TaczDischargeGuard.protectedAlly(scene.bullet, scene.body), "departed shooter is no longer a colony resident");
                scene.target.getPersistentData().putUUID("folkways_colony_pet", scene.colony.id());
                helper.assertTrue(TaczDischargeGuard.protectedAlly(scene.bullet, scene.target), "in-flight colony identity protects a colony pet");
                var hit = new com.tacz.guns.api.event.common.EntityHurtByGunEvent.Pre(scene.bullet, scene.target, scene.body,
                    id("m1911"), id("m1911"), 10, null, false, 1, net.neoforged.fml.LogicalSide.SERVER);
                NeoForge.EVENT_BUS.post(hit);
                helper.assertTrue(hit.isCanceled(), "native pre-damage event protects the pet");
                net.neoforged.neoforge.common.ModConfigSpec.BooleanValue protection = TaczConfig.SPEC.getValues().get("protectColonyAllies");
                boolean original = protection.get();
                try {
                    protection.set(false);
                    var allowed = new com.tacz.guns.api.event.common.EntityHurtByGunEvent.Pre(scene.bullet, scene.target, scene.body,
                        id("m1911"), id("m1911"), 10, null, false, 1, net.neoforged.fml.LogicalSide.SERVER);
                    NeoForge.EVENT_BUS.post(allowed);
                    helper.assertFalse(allowed.isCanceled(), "server option can disable in-flight ally protection");
                } finally { protection.set(original); }
            }).thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void colonyRoundsCannotBreakGlassOutsideWorkNodes(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "m1911", FireMode.SEMI, 2);
        scene.firstShot = () -> scene.session.stop(); scene.open(); scene.session.fire(scene.target);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.bullet != null, "native projectile exists"))
            .thenExecute(() -> {
                BlockPos glass = scene.body.blockPosition().above(3);
                helper.getLevel().setBlockAndUpdate(glass, Blocks.GLASS.defaultBlockState());
                var hit = new com.tacz.guns.api.event.server.AmmoHitBlockEvent(helper.getLevel(),
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(glass), net.minecraft.core.Direction.WEST, glass, false),
                    Blocks.GLASS.defaultBlockState(), scene.bullet);
                NeoForge.EVENT_BUS.post(hit);
                helper.assertTrue(hit.isCanceled(), "block effects are stopped before TACZ mutates glass");
                helper.assertTrue(helper.getLevel().getBlockState(glass).is(Blocks.GLASS), "glass remains intact");
            }).thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void unsupportedGunsAndWrongCaliberFallBackWithoutInventingAmmo(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, "m1911", FireMode.SEMI, 0);
        scene.body.pack().insert(ammo("9mm", 20));
        helper.assertTrue(Weapons.open(worker(scene.body)).isEmpty(), "wrong caliber cannot make an empty gun usable");
        ItemStack gun = scene.actual(); ((IGun) gun.getItem()).setDummyAmmoAmount(gun, 100);
        helper.assertFalse(TaczGuns.supported(gun), "virtual ammo is outside v1");
        helper.assertFalse(TaczGuns.supported(gun("rpg7", FireMode.SEMI, 1)), "explosive guns are outside v1");
        helper.assertFalse(TaczGuns.supported(gun("missing", FireMode.SEMI, 1)), "missing gun pack is inactive");
        helper.succeed();
    }

    static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("tacz", path); }
    static ItemStack ammo(String id, int count) {
        ItemStack stack = new ItemStack(ModItems.AMMO.get(), count); ((IAmmo) stack.getItem()).setAmmoId(stack, id(id)); return stack;
    }
    static ItemStack gun(String id, FireMode mode, int count) {
        ItemStack stack = new ItemStack(ModItems.MODERN_KINETIC_GUN.get());
        IGun gun = (IGun) stack.getItem(); gun.setGunId(stack, id(id)); gun.setFireMode(stack, mode);
        gun.setCurrentAmmoCount(stack, Math.max(0, count - 1)); gun.setBulletInBarrel(stack, count > 0);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("actual TACZ gun")); return stack;
    }
    static Worker worker(ResidentEntity body) {
        return new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body; }
            public Container pack() { return body.pack(); }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { body.spawnAtLocation(stack); }
            public Effects effects() { return body.effects(); }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }
    static final net.minecraft.server.level.TicketType<java.util.UUID> TICKING =
        net.minecraft.server.level.TicketType.create("folkways_tacz_test", java.util.Comparator.<java.util.UUID>naturalOrder());

    static final class Fixture implements GameTestListener {
        final ResidentEntity body;
        final Zombie target;
        final Colony colony;
        final AtomicInteger shots = new AtomicInteger();
        final Consumer<EntityJoinLevelEvent> listener;
        WeaponSession session;
        Runnable firstShot;
        EntityKineticBullet bullet;
        Entity other;
        long waitUntil;
        boolean advance = true;
        boolean closed;
        Fixture(GameTestHelper helper, String gun, FireMode mode, int count) {
            for (int x = 0; x < 12; x++) { for (int z = 0; z < 4; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); } }
            body = PersonBody.RESIDENT.get().create(helper.getLevel()); body.setNoAi(true);
            target = EntityType.ZOMBIE.create(helper.getLevel()); target.setNoAi(true);
            target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); target.setHealth(1000);
            target.getAttribute(Attributes.ARMOR).setBaseValue(0);
            BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
            body.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            target.moveTo(at.getX() + 6.5, at.getY(), at.getZ() + .5);
            // Large combined suites extend beyond spawn's ticking area. TACZ's own entity tick must run.
            helper.getLevel().getChunkSource().addRegionTicket(TICKING, body.chunkPosition(), 2, body.getUUID());
            helper.getLevel().addFreshEntity(body); helper.getLevel().addFreshEntity(target);
            colony = Colonies.mint(helper.getLevel().getServer());
            colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow(); body.joinColony(colony.id()); colony.entered(body);
            body.pack().insert(gun(gun, mode, count));
            listener = event -> {
                if (event.getEntity() instanceof EntityKineticBullet created && created.getOwner() == body) {
                    bullet = created;
                    if (shots.incrementAndGet() == 1 && firstShot != null) { firstShot.run(); }
                }
            };
            NeoForge.EVENT_BUS.addListener(listener);
            long started = System.currentTimeMillis();
            helper.onEachTick(() -> {
                if (System.currentTimeMillis() - started > 20_000) {
                    helper.fail("native timer deadline: shots=" + shots + ", state=" + (session == null ? "none" : session.state())
                        + ", health=" + body.getHealth() + ", total=" + TaczGuns.loaded(actual()));
                }
                if (advance && session != null) { session.tick(); }
            });
            helper.testInfo.addListener(this);
        }
        void open() { session = Weapons.open(worker(body)).orElseThrow(); }
        ItemStack actual() {
            return body.hasWeaponSession() ? body.getMainHandItem() : body.pack().contents().stream()
                .filter(stack -> stack.is(ModItems.MODERN_KINETIC_GUN.get())).findFirst().orElse(ItemStack.EMPTY);
        }
        int total(String ammo) { return (int) Goods.countIn(body.pack(), TaczGuns.ammunition(id(ammo))) + TaczGuns.loaded(actual()); }
        void close() {
            if (closed) { return; } closed = true;
            NeoForge.EVENT_BUS.unregister(listener);
            if (session != null) { session.close(); }
            ((net.minecraft.server.level.ServerLevel) body.level()).getChunkSource().removeRegionTicket(TICKING, body.chunkPosition(), 2, body.getUUID());
            body.discard(); target.discard(); if (other != null) { other.discard(); }
            colony.raze();
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
