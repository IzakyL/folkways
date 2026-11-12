package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.combat.weapon.Weapons;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_cookery")
@PrefixGameTestTemplate(false)
public final class KitchenDeliveryGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(KitchenDeliveryGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void temporaryToolOverPresentationKeepsOnlyTheRealItems(GameTestHelper helper) {
        temporaryHand(helper, true, false, false);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void temporaryToolOverEmptyHandKeepsOnlyTheRealItems(GameTestHelper helper) {
        temporaryHand(helper, false, false, false);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void temporaryToolOverUnownedWeaponKeepsOnlyTheRealItems(GameTestHelper helper) {
        temporaryHand(helper, false, true, false);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void temporaryToolDuringAttackDoesNotRestoreTheHolsteredWeapon(GameTestHelper helper) {
        temporaryHand(helper, false, true, true);
    }

    private static void temporaryHand(GameTestHelper helper, boolean presentation, boolean drawn, boolean active) {
        ResidentEntity body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setNoAi(true);
        body.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 1, 1))));
        body.pack().insert(new ItemStack(Items.IRON_SWORD));
        body.pack().insert(new ItemStack(Items.STICK));
        WeaponAttack attack = null;
        if (presentation) {
            body.presentHeldItem(new ItemStack(Items.IRON_SWORD));
        } else if (active) {
            attack = Weapons.open(TestWorker.of(body)).orElseThrow();
        } else if (drawn) {
            helper.assertTrue(body.drawWeapon(0), "the real weapon is drawn without an attack");
        }
        ItemStack previous = body.getMainHandItem();
        ItemStack hand = new ItemStack(Items.STICK);
        try {
            KitchenDelivery.Result result = KitchenDelivery.run(helper.getLevel(), body.blockPosition(),
                TestWorker.of(body), hand, () -> {
                    helper.assertTrue(body.getMainHandItem() == hand, "the station receives the supplied tool");
                    body.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOWL));
                    return true;
                });
            helper.assertTrue(result.success(), "the station action succeeds");
            helper.assertValueEqual(result.outputs().size(), 1, "the returned container is delivered once");
            helper.assertTrue(result.outputs().getFirst().is(Items.BOWL), "the output is the returned container");
            helper.assertValueEqual(body.pack().count(Items.BOWL), 0, "the output is not also holstered");
            helper.assertValueEqual(body.pack().count(Items.STICK), 1, "the supplied tool is not duplicated");
            helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), drawn && !active ? 0 : 1,
                "a drawn weapon is either restored or holstered exactly once");
            if (active) {
                helper.assertValueEqual(attack.state(), WeaponAttack.State.STOPPED, "the kitchen action ends combat");
                helper.assertTrue(body.getMainHandItem().isEmpty(), "the holstered weapon is not restored as a copy");
            } else {
                helper.assertTrue(body.getMainHandItem() == previous, "the previous hand is restored");
                helper.assertValueEqual(body.weaponDrawn(), drawn, "the restored hand retains its physical role");
            }
            body.tick();
            helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), 1, "one real sword remains after a tick");
            helper.assertValueEqual(body.pack().count(Items.STICK), 1, "one real tool remains after a tick");
            helper.assertValueEqual(body.getMainHandItem().isEmpty(), !presentation,
                "only a presentation copy remains in the idle hand");
            helper.assertFalse(body.weaponDrawn(), "the idle hand has no unowned real item");
            body.clearHandPresentation();
            helper.assertTrue(body.getMainHandItem().isEmpty(), "the preserved presentation flag permits clearing");
        } finally {
            body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void rotatedStationDropsOutsideTheWorkersAreaAreDeliveredOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos storage = helper.absolutePos(new BlockPos(2, 1, 2));
        UUID id = UUID.randomUUID();
        Vec3 origin = Vec3.atLowerCornerOf(storage.offset(-24, 0, -8));
        Body person = PersonBody.RESIDENT.get().create(level);
        WorldSpaces.Frame frame = new WorldSpaces.Frame() {
            public UUID id() { return id; }
            public Level level() { return level; }
            public BlockPos storageOrigin() { return storage; }
            public Vec3 toWorld(Vec3 local) { return origin.add(-local.z, local.y, local.x); }
            public Vec3 toLocal(Vec3 world) {
                Vec3 delta = world.subtract(origin);
                return new Vec3(delta.z, delta.y, -delta.x);
            }
        };
        Runnable remove = WorldSpaces.install(new WorldSpaces.Source() {
            public Optional<WorldSpaces.Frame> frame(Level host, UUID key) {
                return host == level && key.equals(id) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> containing(Level host, BlockPos cell) {
                return host == level && cell.equals(storage) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> aboard(Entity entity) {
                return entity == person.mob() ? Optional.of(frame) : Optional.empty();
            }
        });
        Vec3 world = WorldSpaces.world(level, WorldPos.of(level, storage)).orElseThrow().add(0, 0.25, 0);
        person.mob().setPos(world.add(4, -0.25, 0));
        Vec3 stored = Vec3.atBottomCenterOf(storage).add(0, 0.25, 0);
        Vec3 held = person.mob().position().add(0, 0.25, 0);
        var projected = new ItemEntity(level, world.x, world.y, world.z, new ItemStack(Items.CHICKEN, 3));
        var local = new ItemEntity(level, stored.x, stored.y, stored.z, new ItemStack(Items.BONE));
        var carried = new ItemEntity(level, held.x, held.y, held.z, new ItemStack(Items.BOWL));
        ItemStack previous = new ItemStack(Items.APPLE, 7);
        ItemStack hand = new ItemStack(Items.STICK);
        person.mob().setItemInHand(InteractionHand.MAIN_HAND, previous);
        try {
            helper.assertTrue(!person.mob().getBoundingBox().inflate(1).contains(world),
                "the transformed station drop is outside the worker's capture area");
            helper.assertTrue(!new AABB(storage).inflate(1).minmax(person.mob().getBoundingBox().inflate(1)).contains(world),
                "the old storage-to-worker bounding box misses this transformed station drop");
            KitchenDelivery.Result result = KitchenDelivery.run(level, storage, TestWorker.of(person), hand, () -> {
                helper.assertTrue(person.mob().getMainHandItem() == hand, "the real API receives its supplied hand");
                level.addFreshEntity(projected);
                level.addFreshEntity(local);
                level.addFreshEntity(carried);
                return true;
            });
            helper.assertTrue(result.success(), "the interaction result is preserved");
            helper.assertValueEqual(result.outputs().size(), 3, "the three actual drops are each delivered once");
            helper.assertValueEqual(result.outputs().stream().filter(stack -> stack.is(Items.CHICKEN))
                .mapToInt(ItemStack::getCount).sum(), 3, "the rotated world-space station output is retained");
            helper.assertTrue(result.outputs().stream().anyMatch(stack -> stack.is(Items.BONE))
                    && result.outputs().stream().anyMatch(stack -> stack.is(Items.BOWL)),
                "station storage drops and worker container drops are also retained");
            helper.assertTrue(List.of(projected, local, carried).stream()
                    .allMatch(drop -> level.getEntity(drop.getUUID()) == null),
                "captured products do not also remain as loose items");
            helper.assertTrue(person.mob().getMainHandItem() == previous, "the worker's previous hand is restored");
            helper.succeed();
        } finally {
            remove.run();
            List.of(projected, local, carried).forEach(ItemEntity::discard);
            person.mob().discard();
        }
    }

}
