package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class CombatInventoryGameTests {
    private static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways_test", "combat_stock");
    private static final Map<WorldPos, Probe> STORES = new HashMap<>();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        Containers.register(BeaconBlockEntity.class,
            (level, beacon) -> Optional.ofNullable(STORES.get(WorldPos.of(level, beacon.getBlockPos()))));
        event.register(CombatInventoryGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void unchangedNearbyObstaclesKeepTheCurrentWalkingPath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        var body = PersonBody.RESIDENT.get().create(level);
        BlockPos feet = helper.absolutePos(new BlockPos(2, 1, 2));
        body.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
        body.setNoAi(true);
        body.setOnGround(true);
        level.addFreshEntity(body);
        var boxes = new java.util.ArrayList<net.minecraft.world.phys.AABB>();
        boxes.add(new net.minecraft.world.phys.AABB(feet.offset(0, 0, 3)));
        Runnable remove = io.github.izakyl.folkways.core.world.api.Structures.install(
            (world, area) -> world == level ? List.copyOf(boxes) : List.of());
        io.github.izakyl.folkways.core.testing.Cleanup.on(helper, () -> {
            remove.run();
            body.discard();
        });
        var walk = new io.github.izakyl.folkways.vanilla.person.walk.OnFoot(1,
            () -> "folkways.refusal.unreachable");
        var goals = java.util.Set.of(WorldPos.of(level, feet.offset(7, 0, 0)));
        walk.setOut(level, body, goals);
        var original = body.getNavigation().getPath();
        helper.assertTrue(original != null, "the initial route exists");
        for (int tick = 0; tick < 10; tick++) {
            walk.step(level, body, goals);
        }
        helper.assertTrue(body.getNavigation().getPath() == original,
            "an unchanged nearby obstacle does not restart the route");
        boxes.clear();
        for (int tick = 0; tick < 10; tick++) {
            walk.step(level, body, goals);
        }
        helper.assertTrue(body.getNavigation().getPath() != original,
            "removing the nearby obstacle recomputes the route");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 1200)
    public static void realChunkUnloadDetachesResidentStockAfterTrackingStops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        BlockPos feet = helper.absolutePos(new BlockPos(2, 1, 2)).offset(100000, 0, 100000);
        var chunk = new net.minecraft.world.level.ChunkPos(feet);
        level.setChunkForced(chunk.x, chunk.z, true);
        level.getChunk(chunk.x, chunk.z);
        level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
        var body = PersonBody.RESIDENT.get().create(level);
        body.setNoAi(true);
        BlockPos start = helper.absolutePos(new BlockPos(2, 1, 2));
        body.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5);
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        body.pack().setItem(0, new ItemStack(Items.ARROW, 32));
        InventoryObservations.publish(colony, level);
        body.onRemovedFromLevel();
        helper.assertTrue(colony.inventory().snapshot().residents().get(body.id()).state()
            != InventorySnapshot.State.UNLOADED, "a tracking-only exit keeps colony stock online");
        body.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
        io.github.izakyl.folkways.core.testing.Cleanup.on(helper, () -> {
            level.setChunkForced(chunk.x, chunk.z, false);
            if (!body.isRemoved()) {
                body.discard();
            }
        });
        helper.runAfterDelay(5, () -> level.setChunkForced(chunk.x, chunk.z, false));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(body.getRemovalReason()
                == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK,
                "the real chunk system must unload the resident");
            helper.assertValueEqual(colony.inventory().snapshot().residents().get(body.id()).state(),
                InventorySnapshot.State.UNLOADED, "unloaded resident stock is no longer online");
            helper.assertTrue(level.getEntity(body.id()) == null, "the old entity leaves the level index");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void planningUsesObservationsAndDefersIncompleteStock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var body = PersonBody.RESIDENT.get().create(level);
        BlockPos feet = helper.absolutePos(new BlockPos(2, 1, 2));
        body.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        BlockPos cell = helper.absolutePos(new BlockPos(5, 1, 2));
        level.setBlockAndUpdate(cell, Blocks.BEACON.defaultBlockState());
        WorldPos at = WorldPos.of(level, cell);
        Probe store = new Probe(9);
        Probe pack = new Probe(body.pack().getContainerSize());
        STORES.put(at, store);
        try {
            var field = body.pack().getClass().getDeclaredField("container");
            field.setAccessible(true);
            field.set(body.pack(), pack);
            colony.stores(OWNER, level.dimension(), List.of(at));
            var supplies = PatrolSupplies.of(colony);
            var entry = LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 4, 1);
            var profile = supplies.profiles().create("Observation");
            profile = new LoadoutProfile(profile.id(), profile.name(), List.of(entry));
            supplies.profiles().replace(profile);
            supplies.profiles().assign(List.of(body.id()), Optional.of(profile.id()));
            store.setItem(0, new ItemStack(Items.ARROW, 4));
            colony.inventoryFeedback().changed(Stash.at(at));
            InventoryObservations.publish(colony, level);
            var first = ProfileShortages.read(colony, profile, level.getServer());
            helper.assertTrue(first.complete() && first.unavailableStock().get(entry.id()) == 0,
                "observed stock covers the resident's deficit");
            int storeReads = store.reads;
            int packReads = pack.reads;
            var worker = WeaponHandGameTests.worker(body);
            for (int pass = 0; pass < 16; pass++) {
                ProfileShortages.read(colony, profile, level.getServer());
                supplies.keeps(body);
                supplies.resupply(worker, Optional.empty(), Long.MIN_VALUE, Long.MIN_VALUE);
                ResidentWeapons.ranged(worker);
                ObservationGear.assigned(body);
            }
            helper.assertValueEqual(store.reads, storeReads, "unchanged passes do not read the store");
            helper.assertValueEqual(pack.reads, packReads, "unchanged passes do not read the pack");
            store.setItem(0, ItemStack.EMPTY);
            colony.inventoryFeedback().changed(Stash.at(at));
            body.pack().setItem(0, new ItemStack(Items.ARROW, 2));
            InventoryObservations.publish(colony, level);
            var changed = ProfileShortages.read(colony, profile, level.getServer());
            helper.assertTrue(changed.complete() && changed.missing().get(entry.id()) == 2
                && changed.unavailableStock().get(entry.id()) == 2,
                "store and pack notifications update the next evaluation");
            var bow = LoadoutProfile.Entry.weapon(ItemSpec.of(Items.BOW));
            profile = new LoadoutProfile(profile.id(), profile.name(), List.of(entry, bow));
            supplies.profiles().replace(profile);
            body.pack().setItem(1, new ItemStack(Items.BOW));
            InventoryObservations.publish(colony, level);
            helper.assertTrue(ResidentWeapons.ranged(worker), "the assigned bow determines planning");
            var observed = colony.inventory().snapshot().residents().get(body.id());
            colony.inventoryFeedback().staleResident(body.id(), observed.version());
            helper.assertTrue(colony.inventory().snapshot().residents().get(body.id()).state()
                != InventorySnapshot.State.OBSERVED, "the resident observation is incomplete");
            helper.assertTrue(ResidentWeapons.ranged(worker), "profile planning does not depend on resident observation");
            WeaponAttack attack = ResidentWeapons.open(worker).orElseThrow();
            helper.assertTrue(body.getMainHandItem().is(Items.BOW), "an armed resident can still open a attack");
            attack.stop();
            body.holsterWeapon();
            InventoryObservations.publish(colony, level);
            body.pack().setItem(0, ItemStack.EMPTY);
            helper.assertTrue(ResidentWeapons.ranged(worker), "removing ammunition does not change profile planning");
            InventoryObservations.publish(colony, level);
            helper.assertTrue(ResidentWeapons.open(worker).isEmpty(), "execution rejects a bow without ammunition");
            helper.assertTrue(colony.inventory().snapshot().residents().get(body.id()).state()
                != InventorySnapshot.State.OBSERVED, "failed execution marks the resident stock changed");
            InventoryObservations.publish(colony, level);
            var shortage = ProfileShortages.read(colony, profile, level.getServer());
            helper.assertTrue(shortage.complete() && shortage.missing().get(entry.id()) == 4,
                "the profile supplies detect the ammunition deficit after publication");
            body.pack().setItem(0, new ItemStack(Items.ARROW, 2));
            InventoryObservations.publish(colony, level);
            WeaponAttack refilled = ResidentWeapons.open(worker).orElseThrow();
            helper.assertTrue(body.getMainHandItem().is(Items.BOW), "a refilled pack opens the real weapon");
            refilled.stop();
            body.holsterWeapon();
            WorldPos unknown = WorldPos.of(level, helper.absolutePos(new BlockPos(7, 1, 2)));
            colony.stores(OWNER, level.dimension(), List.of(at, unknown));
            var pending = ProfileShortages.read(colony, profile, level.getServer());
            helper.assertFalse(pending.complete(), "unobserved membership cannot establish a shortage");
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        } finally {
            STORES.remove(at);
            body.discard();
            colony.raze();
        }
        helper.succeed();
    }

    private static final class Probe extends SimpleContainer {
        private int reads;

        private Probe(int slots) {
            super(slots);
        }

        @Override
        public ItemStack getItem(int slot) {
            reads++;
            return super.getItem(slot);
        }
    }
}
