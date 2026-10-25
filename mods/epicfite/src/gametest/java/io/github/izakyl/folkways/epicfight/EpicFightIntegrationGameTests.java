package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.resident.Locomotions;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysEpicFight.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EpicFightIntegrationGameTests {
    private static final ResourceLocation BAREHANDED_PATROLLER = ResourceLocation.fromNamespaceAndPath(
        FolkwaysEpicFight.ID, "test_barehanded_patroller");

    @SubscribeEvent
    public static void declareBarehandedFixture(Declaring event) {
        event.residentKind(ResidentKind.growing(BAREHANDED_PATROLLER, Locomotions.named(Locomotions.WALKING))
            .barehanded(Set.of(PatrolContent.VOCATION)));
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(EpicFightIntegrationGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void residentPatchAndWeaponAdapterRegisterAndReturnTheSameItemOnce(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        try {
            ResidentEpicFightPatch patch = ResidentEpicFightPatch.of(scene.resident);
            helper.assertTrue(patch != null, "Epic Fight registers a capability patch for Folkways residents");
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.accepts(scene.resident.pack().getItem(0)),
                "the native iron sword is recognized by the optional adapter");
            helper.assertTrue(patch.isTargetInvulnerable(scene.zombie),
                "a target is protected from Epic Fight hits before a resident owns an active session");

            WeaponSession session = Weapons.open(scene.worker).orElseThrow(
                () -> new AssertionError("the public weapons dispatcher must select the Epic Fight adapter"));
            helper.assertTrue(session instanceof EpicFightWeaponSession, "the selected adapter opens the Epic Fight session");
            helper.assertTrue(scene.resident.getMainHandItem() == scene.sword,
                "the actual packed stack moves into the resident's hand");
            helper.assertTrue(scene.resident.pack().count(Items.IRON_SWORD) == 0,
                "the borrowed weapon is no longer duplicated in the pack");

            session.close();
            session.close();
            helper.assertTrue(session.state() == WeaponSession.State.CLOSED, "close is idempotent and terminal");
            helper.assertTrue(scene.resident.getMainHandItem().isEmpty(), "closing clears the temporary main hand");
            helper.assertValueEqual(scene.resident.pack().count(Items.IRON_SWORD), 1,
                "closing returns exactly one real weapon to resident storage");
            helper.assertValueEqual(scene.resident.pack().getItem(0).getDamageValue(), 9,
                "the actual weapon's durability survives its session lease");
        } finally {
            scene.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void epicFightAttackHitsOneHostileAndCannotHitAgainAfterSessionClose(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        AtomicInteger hits = new AtomicInteger();
        Consumer<LivingDamageEvent.Post> listener = event -> {
            if (event.getEntity() == scene.zombie) { hits.incrementAndGet(); }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            WeaponSession session = Weapons.open(scene.worker).orElseThrow();
            ResidentEpicFightPatch patch = ResidentEpicFightPatch.of(scene.resident);
            float before = scene.zombie.getHealth();
            helper.assertFalse(session.fire(scene.cow), "a neutral cow cannot be selected as a weapon target");
            helper.assertTrue(patch.isTargetInvulnerable(scene.cow), "the Epic Fight target gate rejects a neutral entity");
            helper.assertTrue(session.fire(scene.zombie), "the session starts a real Epic Fight strike on a hostile mob");
            helper.assertFalse(patch.isTargetInvulnerable(scene.zombie), "the active strike admits its authorized hostile target");

            helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(scene.zombie.getHealth() < before,
                    "Epic Fight's advancing attack animation reaches its real collision frame"))
                .thenExecute(() -> {
                    session.close();
                    helper.assertValueEqual(hits.get(), 1,
                        "one accepted Epic Fight collision produces one damage event, without a vanilla duplicate");
                    helper.assertValueEqual(scene.resident.pack().count(Items.IRON_SWORD), 1,
                        "the successful strike returns its actual weapon exactly once");
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertValueEqual(hits.get(), 1, "closing the session leaves no delayed residual hit");
                    scene.close();
                    NeoForge.EVENT_BUS.unregister(listener);
                })
                .thenSucceed();
        } catch (Throwable failure) {
            NeoForge.EVENT_BUS.unregister(listener);
            scene.close();
            throw failure;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void stoppingBeforeTheFirstServerAnimationTickRevokesEveryPendingHit(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        AtomicInteger residentHits = new AtomicInteger();
        Consumer<LivingDamageEvent.Post> listener = event -> {
            if (event.getEntity() == scene.zombie && event.getSource().getEntity() == scene.resident) {
                residentHits.incrementAndGet();
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            WeaponSession session = Weapons.open(scene.worker).orElseThrow();
            ResidentEpicFightPatch patch = ResidentEpicFightPatch.of(scene.resident);
            float before = scene.zombie.getHealth();
            helper.assertTrue(session.fire(scene.zombie), "a hostile strike enters its animation before the world advances");
            helper.assertTrue(scene.resident.getMainHandItem() == scene.sword,
                "the firing session owns the identical real stack borrowed from storage");
            helper.assertFalse(patch.isTargetInvulnerable(scene.zombie), "the active strike initially admits its target");
            session.stop();
            helper.assertTrue(patch.isTargetInvulnerable(scene.zombie), "stop revokes hit permission immediately");
            helper.assertTrue(scene.resident.getMainHandItem() == scene.sword,
                "the stopped session still holds its identical real borrowed stack until close");
            helper.assertValueEqual(scene.resident.pack().count(Items.IRON_SWORD), 0,
                "stop does not duplicate its still-borrowed weapon into storage");
            session.close();
            helper.assertFalse(scene.resident.hasWeaponSession(), "close releases the main-hand session ownership");
            helper.assertTrue(scene.resident.getMainHandItem().isEmpty(), "close clears the borrowed main hand");
            helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> {
                    helper.assertValueEqual(scene.zombie.getHealth(), before,
                        "a cancelled strike cannot land after later animation ticks; lastDamage="
                            + lastDamageSummary(scene.zombie));
                    helper.assertValueEqual(residentHits.get(), 0,
                        "no post-cancellation damage event may name the resident as source");
                    helper.assertValueEqual(scene.resident.pack().count(Items.IRON_SWORD), 1,
                        "cancellation retains and returns the real weapon");
                    // Pack insertion deliberately copies/splits stacks, as it does for every native lease.
                    helper.assertTrue(ItemStack.isSameItemSameComponents(scene.resident.pack().getItem(0), scene.sword),
                        "cancellation returns the borrowed weapon with all its original components");
                    helper.assertValueEqual(scene.sword.getDamageValue(), 9,
                        "cancellation preserves the borrowed weapon durability");
                    helper.assertValueEqual(scene.resident.pack().getItem(0).getDamageValue(), 9,
                        "the returned storage stack retains the unspent durability");
                    scene.close();
                    NeoForge.EVENT_BUS.unregister(listener);
                })
                .thenSucceed();
        } catch (Throwable failure) {
            scene.close();
            NeoForge.EVENT_BUS.unregister(listener);
            throw failure;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void authorizedBarehandedResidentUsesEpicFightWithoutBorrowingOrCreatingItems(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Worker barehanded = new Worker() {
            public Resident resident() { return new Resident(scene.resident.getUUID(), BAREHANDED_PATROLLER); }
            public Mob body() { return scene.resident; }
            public Container pack() { return scene.resident.pack(); }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { scene.resident.spawnAtLocation(stack); }
            public Effects effects() { return scene.body.effects(); }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
        try {
            scene.resident.pack().setItem(0, ItemStack.EMPTY);
            helper.assertTrue(ResidentKinds.barehanded(BAREHANDED_PATROLLER,
                java.util.Optional.of(io.github.izakyl.folkways.core.api.vocation.Vocations.required(PatrolContent.VOCATION))),
                "the fixture explicitly authorizes barehanded patrol combat");
            WeaponSession session = Weapons.open(barehanded).orElseThrow(
                () -> new AssertionError("authorized barehanded combat selects the registered Epic Fight adapter"));
            helper.assertTrue(session instanceof EpicFightWeaponSession, "barehanded combat still receives Epic Fight animation integration");
            helper.assertTrue(scene.resident.hasWeaponSession(), "the empty hand is leased as an active weapon session");
            helper.assertTrue(scene.resident.getMainHandItem().isEmpty(), "the empty-hand lease keeps the resident's main hand empty");
            helper.assertTrue(scene.resident.pack().getItem(0).isEmpty(),
                "opening an empty-hand lease does not create an item in storage");
            float before = scene.zombie.getHealth();
            helper.assertTrue(session.fire(scene.zombie), "the authorized resident starts an Epic Fight fist attack");
            helper.assertValueEqual(scene.zombie.getHealth(), before,
                "the fist strike causes no damage during its animation windup");
            helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(scene.zombie.getHealth() < before,
                    "the fist strike deals damage on its advancing Epic Fight hit frame"))
                .thenExecute(() -> {
                    session.stop();
                    session.close();
                    helper.assertFalse(scene.resident.hasWeaponSession(), "close releases the empty-hand session ownership");
                    helper.assertTrue(scene.resident.getMainHandItem().isEmpty(), "close preserves the empty main hand");
                    helper.assertTrue(scene.resident.pack().getItem(0).isEmpty(), "close creates no phantom inventory item");
                    scene.close();
                })
                .thenSucceed();
        } catch (Throwable failure) {
            scene.close();
            throw failure;
        }
    }

    private static final class Scene implements AutoCloseable {
        final ServerLevel level;
        final ResidentEntity resident;
        final Body body;
        final Zombie zombie;
        final Cow cow;
        final ItemStack sword;
        final Worker worker;
        boolean closed;

        Scene(GameTestHelper helper) {
            level = helper.getLevel();
            BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
            for (int x = 0; x < 7; x++) {
                for (int z = 0; z < 4; z++) { helper.setBlock(new BlockPos(x, 0, z), net.minecraft.world.level.block.Blocks.STONE); }
            }
            ItemStack packedSword = new ItemStack(Items.IRON_SWORD);
            packedSword.setDamageValue(9);
            resident = PersonBody.RESIDENT.get().create(level);
            resident.setNoAi(true);
            resident.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
            body = Bodies.of(resident).orElseThrow();
            resident.pack().insert(packedSword);
            sword = resident.pack().getItem(0);
            zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
            zombie.setNoAi(true);
            zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20 * 60 * 10));
            zombie.moveTo(origin.getX() + 1.7, origin.getY(), origin.getZ() + 0.5);
            cow = net.minecraft.world.entity.EntityType.COW.create(level);
            cow.setNoAi(true);
            cow.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 1.6);
            level.addFreshEntity(resident);
            level.addFreshEntity(zombie);
            level.addFreshEntity(cow);
            worker = new Worker() {
                public Resident resident() { return body.resident(); }
                public Mob body() { return resident; }
                public Container pack() { return resident.pack(); }
                public List<Store> within() { return List.of(); }
                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                public void spill(ItemStack stack) { resident.spawnAtLocation(stack); }
                public Effects effects() { return body.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }

        public void close() {
            if (closed) { return; }
            closed = true;
            resident.discard();
            zombie.discard();
            cow.discard();
        }
    }

    private static String lastDamageSummary(Zombie zombie) {
        var source = zombie.getLastDamageSource();
        return source == null ? "none" : source.getMsgId() + "/source=" + source.getEntity();
    }
}
