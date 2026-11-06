package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.engine.inventory.InventoryObservations;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Locomotions;
import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.pasture.PastureContent;
import io.github.izakyl.folkways.plugins.pasture.PastureHorses;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class ResidentMountGameTests {
    private static final TicketType<UUID> TICKING =
        TicketType.create("folkways_mount_test", Comparator.<UUID>naturalOrder());

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentMountGameTests.class);
    }

    @GameTest(template = "empty_large")
    public static void profileClaimsHorseAndEquipmentAndExcludesCartOwners(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        LoadoutProfile profile = scene.profile(List.of(LoadoutProfile.Entry.mount(),
            LoadoutProfile.Entry.horseArmor(ItemSpec.of(Items.IRON_HORSE_ARMOR))));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.body.pack().insert(new ItemStack(Items.IRON_HORSE_ARMOR));
        scene.refresh();
        helper.assertTrue(ResidentMounts.horse(scene.body).orElseThrow() == horse, "profile claims the pasture horse");
        helper.assertTrue(horse.isSaddled() && horse.getItemBySlot(EquipmentSlot.BODY).is(Items.IRON_HORSE_ARMOR),
            "physical saddle and horse armor leave the pack for the horse");
        helper.assertTrue(scene.supplies.deficits(scene.body, profile).values().stream().allMatch(count -> count == 0),
            "mounted equipment satisfies the profile once");
        helper.assertTrue(PastureHorses.claim(scene.colony, helper.getLevel(),
            ResourceLocation.fromNamespaceAndPath("folkways_carts", "carts")).isEmpty(),
            "the shared cart claim cannot take the rider's horse");
        ResidentMounts restored = new ResidentMounts(scene.colony);
        helper.assertTrue(restored.horse(helper.getLevel(), scene.body.id()).orElseThrow() == horse,
            "the saved ownership ledger restores the same horse");
        var profiles = new LoadoutProfiles(scene.colony, id -> { });
        helper.assertTrue(profiles.assigned(scene.body.id()).orElseThrow().entries().equals(profile.entries()),
            "mount and horse armor entries survive profile serialization");
        Locomotions.override(scene.body, null);
        restored.reconcile(helper.getLevel(), scene.body, profiles.assigned(scene.body.id()));
        helper.assertTrue(scene.body.locomotion().id().getPath().equals("riding"),
            "restoring ownership reinstalls the riding gait");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void shortagesSeparateHorsesSaddlesAndHorseArmor(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        var saddle = LoadoutProfile.Entry.mount();
        var armor = LoadoutProfile.Entry.horseArmor(ItemSpec.of(Items.DIAMOND_HORSE_ARMOR));
        LoadoutProfile profile = scene.profile(List.of(saddle, armor));
        InventoryObservations.publish(scene.colony, helper.getLevel());
        ProfileShortages shortage = ProfileShortages.read(scene.colony, profile, helper.getLevel().getServer());
        helper.assertTrue(shortage.missingHorses() == 1 && shortage.unavailableHorses() == 1,
            "no tame pasture horse is a distinct shortage");
        helper.assertTrue(shortage.missing().get(saddle.id()) == 1 && shortage.missing().get(armor.id()) == 1,
            "the saddle and horse armor are physical stock shortages");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void droppingMountReturnsEquipmentAndReleasesHorse(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        LoadoutProfile profile = scene.profile(List.of(LoadoutProfile.Entry.mount(),
            LoadoutProfile.Entry.horseArmor(ItemSpec.of(Items.DIAMOND_HORSE_ARMOR))));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.body.pack().insert(new ItemStack(Items.DIAMOND_HORSE_ARMOR));
        scene.refresh();
        helper.assertTrue(ResidentMounts.board(scene.body), "resident mounts its assigned horse");
        scene.supplies.profiles().replace(
            new LoadoutProfile(profile.id(), profile.name(), List.of()));
        helper.assertTrue(!scene.body.isPassenger() && !horse.isSaddled()
            && horse.getItemBySlot(EquipmentSlot.BODY).isEmpty(), "profile removal dismounts and strips supplied tack");
        helper.assertTrue(scene.body.pack().count(Items.SADDLE) == 1
            && scene.body.pack().count(Items.DIAMOND_HORSE_ARMOR) == 1, "tack returns to the pack for storage");
        helper.assertTrue(!horse.getTags().contains(PastureContent.CLAIMED)
            && scene.colony.holdingOf(horse.getUUID()).isEmpty() && !horse.isNoAi(),
            "the horse is released where it stands");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 500)
    public static void travelRidesToLowDoorwayThenWalksToWork(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount()));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.refresh();
        for (int z = 0; z < 9; z++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(17, y, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(17, 1, 4), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        helper.setBlock(new BlockPos(17, 2, 4), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        for (int x = 1; x < 17; x++) {
            helper.setBlock(new BlockPos(x, 0, 4), Blocks.DIRT_PATH);
        }
        WorldPos destination = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(22, 1, 4)));
        for (int x = 0; x < 25; x++) {
            for (int y = 1; y <= 5; y++) {
                helper.setBlock(new BlockPos(x, y, 0), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 8), Blocks.STONE);
            }
        }
        for (int x = 8; x <= 11; x++) {
            for (int z = 1; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        boolean[] mounted = {false};
        boolean[] climbed = {false};
        boolean[] worked = {false};
        Node node = new Node() {
            private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), PersonContent.ID,
                new WorkSite.AtBlock(destination), Stances.at(destination), Workload.Once.of(5)).done();

            public NodeSpec spec() {
                return spec;
            }

            public boolean directTravel() {
                return true;
            }

            public Outcome commit(ServerLevel level, Worker who) {
                helper.assertFalse(who.body().isPassenger(), "work stances are taken on foot");
                worked[0] = true;
                return Outcome.done();
            }
        };
        scene.colony.submit(PersonContent.ID, helper.getLevel().dimension(), Grown.of(node), (id, how) -> { });
        scene.labor = new LaborFixture(scene.colony, helper.getLevel());
        helper.onEachTick(() -> {
            scene.labor.tick();
            mounted[0] |= scene.body.getVehicle() == horse;
            climbed[0] |= horse.getY() > helper.absolutePos(new BlockPos(8, 1, 4)).getY() + .8;
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(worked[0],
                "resident reaches the indoor work: body=" + scene.body.position() + " horse=" + horse.position()
                    + " riding=" + scene.body.isPassenger() + " parked=" + horse.isNoAi()
                    + " horsePath=" + horse.getNavigation().getPath() + " path=" + scene.body.getNavigation().getPath()))
            .thenExecute(() -> {
                helper.assertTrue(mounted[0] && horse.getX() > helper.absolutePos(new BlockPos(10, 1, 4)).getX(),
                    "the far leg was ridden with the horse moving");
                helper.assertTrue(climbed[0], "the horse steps up and down the raised path");
                helper.assertTrue(horse.getX() < helper.absolutePos(new BlockPos(17, 1, 4)).getX(),
                    "horse waits outside the low narrow doorway");
                helper.assertTrue(horse.isNoAi() && horse.getTags().contains(PastureContent.CLAIMED),
                    "the parked horse cannot wander or return to pen stock");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "mount_parking", timeoutTicks = 800)
    public static void ridersAtTheSameWorkshopParkApartAndLeaveTheDoorClear(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<ResidentEntity> riders = new ArrayList<>();
        List<Horse> horses = new ArrayList<>();
        LoadoutProfile profile = scene.profile(List.of(LoadoutProfile.Entry.mount()));
        for (int i = 0; i < 3; i++) {
            ResidentEntity rider = i == 0 ? scene.body : PersonBody.RESIDENT.get().create(helper.getLevel());
            if (i > 0) {
                rider.moveTo(helper.absolutePos(new BlockPos(2, 1, 2 + 2 * i)).getBottomCenter());
                helper.getLevel().addFreshEntity(rider);
                scene.colony.hold(PersonContent.ID, Held.Entity.of(rider)).orElseThrow();
                rider.joinColony(scene.colony.id());
                scene.entities.add(rider);
            }
            Horse horse = scene.horse();
            horse.moveTo(helper.absolutePos(new BlockPos(4, 1, 2 + 2 * i)).getBottomCenter());
            rider.pack().insert(new ItemStack(Items.SADDLE));
            scene.supplies.profiles().assign(List.of(rider.id()), Optional.of(profile.id()));
            scene.supplies.mounts().reconcile(helper.getLevel(), rider, Optional.of(profile));
            riders.add(rider);
            horses.add((Horse) ResidentMounts.horse(rider).orElseThrow());
        }
        for (int x = 17; x < 25; x++) {
            for (int y = 1; y <= 5; y++) {
                helper.setBlock(new BlockPos(x, y, 0), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 8), Blocks.STONE);
            }
        }
        for (int z = 0; z < 9; z++) {
            for (int y = 1; y <= 5; y++) {
                helper.setBlock(new BlockPos(24, y, z), Blocks.STONE);
            }
        }
        for (int x = 0; x < 17; x++) {
            for (int z = -5; z <= 13; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos door = helper.absolutePos(new BlockPos(17, 1, 4));
        for (int z = 0; z < 9; z++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(17, y, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(17, 1, 4), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        helper.setBlock(new BlockPos(17, 2, 4), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        for (int x = 1; x < 17; x++) {
            helper.setBlock(new BlockPos(x, 0, 4), Blocks.DIRT_PATH);
        }
        WorldPos destination = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(22, 1, 4)));
        Set<UUID> mounted = new LinkedHashSet<>();
        Set<UUID> worked = new LinkedHashSet<>();
        List<Node> nodes = riders.stream().<Node>map(rider -> new Node() {
            private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), PersonContent.ID,
                new WorkSite.AtBlock(destination), Stances.at(destination), Workload.Once.of(5)).done();

            public NodeSpec spec() {
                return spec;
            }

            public Optional<UUID> worker() {
                return Optional.of(rider.id());
            }

            public boolean directTravel() {
                return true;
            }

            public Outcome commit(ServerLevel level, Worker who) {
                helper.assertFalse(who.body().isPassenger(), "each rider walks to the indoor work cell");
                worked.add(who.resident().id());
                return Outcome.done();
            }
        }).toList();
        scene.colony.submit(PersonContent.ID, helper.getLevel().dimension(), new Grown(nodes, List.of()),
            (id, how) -> { });
        scene.labor = new LaborFixture(scene.colony, helper.getLevel());
        helper.onEachTick(() -> {
            scene.labor.tick();
            riders.stream().filter(ResidentMounts::riding).forEach(rider -> mounted.add(rider.id()));
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(worked.size() == 3,
                "all three riders reach the same workshop: worked=" + worked.size()))
            .thenExecute(() -> {
                helper.assertTrue(mounted.size() == 3, "all three workers fetched and rode their own horse");
                double minimum = Double.MAX_VALUE;
                for (int i = 0; i < horses.size(); i++) {
                    Horse horse = horses.get(i);
                    helper.assertTrue(horse.isNoAi() && !horse.isVehicle()
                            && (horse.getX() < door.getX() || horse.getZ() < door.getZ() - 4
                                || horse.getZ() > door.getZ() + 4),
                        "every horse stays parked outside the doorway: " + horse.position());
                    helper.assertFalse(horse.blockPosition().equals(door), "no horse occupies the door cell");
                    BlockPos feet = BlockPos.containing(horse.getX(), horse.getY() + .5, horse.getZ());
                    helper.assertFalse(helper.getLevel().getBlockState(feet.below()).is(Blocks.DIRT_PATH),
                        "free yard space lets every horse leave the approach lane, including a horse on a short floor");
                    for (int j = 0; j < i; j++) {
                        double gap = horse.position().subtract(horses.get(j).position()).horizontalDistance();
                        minimum = Math.min(minimum, gap);
                        helper.assertTrue(gap >= 2.0, "parked horses must stay at least 2 blocks apart: gap=" + gap
                            + " first=" + horse.position() + " second=" + horses.get(j).position());
                    }
                }
                com.mojang.logging.LogUtils.getLogger().info("mounted parking: riders=3 minimumGap={}", minimum);
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "mount_parking_fallback", timeoutTicks = 500)
    public static void noParkingSpaceFallsBackToDismountingInTheApproachLane(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount()));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.refresh();
        for (int x = 0; x < 25; x++) {
            for (int y = 1; y <= 5; y++) {
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE);
            }
        }
        for (int z = 2; z <= 6; z++) {
            for (int y = 1; y <= 5; y++) {
                helper.setBlock(new BlockPos(0, y, z), Blocks.STONE);
                helper.setBlock(new BlockPos(24, y, z), Blocks.STONE);
            }
        }
        for (int z = 3; z <= 5; z++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(17, y, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(17, 1, 4), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        helper.setBlock(new BlockPos(17, 2, 4), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        WorldPos destination = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(22, 1, 4)));
        Conveyance leg = scene.body.locomotion().along(1, LaborRefusal.NO_WAY_THERE);
        leg.setOut(helper.getLevel(), scene.body, Set.of(destination));
        boolean[] mounted = {false};
        boolean[] arrived = {false};
        helper.onEachTick(() -> {
            mounted[0] |= scene.body.getVehicle() == horse;
            arrived[0] |= leg.step(helper.getLevel(), scene.body, Set.of(destination)) instanceof Going.Arrived;
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(arrived[0],
                "the rider walks through the door even when the approach has no parking bay"))
            .thenExecute(() -> {
                helper.assertTrue(mounted[0] && !scene.body.isPassenger() && horse.isNoAi(),
                    "the resident rode, got down and left its horse parked");
                helper.assertTrue(horse.getX() < helper.absolutePos(new BlockPos(17, 1, 4)).getX()
                        && Math.abs(horse.getZ() - helper.absolutePos(new BlockPos(14, 1, 4)).getZ() - .5) < .1,
                    "without a free bay the horse stays in its original approach lane");
                leg.release(helper.getLevel(), scene.body);
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void mountedMeleeHitsWithNativeWeaponSession(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount(),
            LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD))));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.body.pack().insert(new ItemStack(Items.IRON_SWORD));
        scene.refresh();
        Zombie target = EntityType.ZOMBIE.create(helper.getLevel());
        target.moveTo(horse.position().add(2, 0, 0));
        target.setNoAi(true);
        target.setInvulnerable(false);
        helper.getLevel().addFreshEntity(target);
        scene.entities.add(target);
        helper.assertTrue(ResidentMounts.board(scene.body), "resident boards for the mounted weapon test");
        WeaponSession session = Weapons.select(WeaponSessionGameTests.worker(scene.body), target, null);
        helper.assertTrue(scene.body.getVehicle() == horse, "weapon selection retains the existing mount");
        helper.assertTrue(session.fire(target), "the sword starts its native attack while riding");
        helper.onEachTick(session::tick);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(target.getHealth() < target.getMaxHealth(),
            "mounted melee damages the hostile target"))
            .thenExecute(() -> {
                helper.assertTrue(scene.body.getVehicle() == horse, "the attack stays in the saddle");
                session.close();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void deadHorseIsReleasedAndAnotherPastureHorseIsClaimed(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse first = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount()));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.refresh();
        first.kill();
        helper.assertTrue(!scene.supplies.mounts().assigned(scene.body.id())
            && scene.colony.holdingOf(first.getUUID()).isEmpty(), "death ends the old horse holding");
        Horse next = scene.horse();
        scene.refresh();
        helper.assertTrue(ResidentMounts.horse(scene.body).orElseThrow() == next && next.isSaddled(),
            "the resident reclaims a free pasture horse and reuses its saddle");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 500)
    public static void profileLogisticsSuppliesTackFromColonyStock(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount(),
            LoadoutProfile.Entry.horseArmor(ItemSpec.of(Items.IRON_HORSE_ARMOR))));
        helper.setBlock(new BlockPos(10, 1, 4), Blocks.BARREL);
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(10, 1, 4)));
        scene.colony.stores(PersonContent.ID, helper.getLevel().dimension(), List.of(at));
        var stock = Containers.at(helper.getLevel(), at.cell()).orElseThrow();
        stock.setItem(0, new ItemStack(Items.SADDLE));
        stock.setItem(1, new ItemStack(Items.IRON_HORSE_ARMOR));
        scene.labor = new LaborFixture(scene.colony, helper.getLevel());
        helper.onEachTick(scene.labor::tick);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(horse.isSaddled()
            && horse.getItemBySlot(EquipmentSlot.BODY).is(Items.IRON_HORSE_ARMOR),
            "the planner delivers real tack to the rider and equips its horse"))
            .thenExecute(() -> helper.assertTrue(stock.isEmpty(), "the two real items leave colony stock once"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 160)
    public static void mountedBowShootsFromSaddle(GameTestHelper helper) {
        ranged(helper, Items.BOW);
    }

    @GameTest(template = "empty_large", timeoutTicks = 160)
    public static void mountedCrossbowLoadsAndShootsFromSaddle(GameTestHelper helper) {
        ranged(helper, Items.CROSSBOW);
    }

    private static void ranged(GameTestHelper helper, Item weapon) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount(), LoadoutProfile.Entry.weapon(ItemSpec.of(weapon))));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.body.pack().insert(new ItemStack(weapon));
        scene.body.pack().insert(new ItemStack(Items.ARROW, 3));
        scene.refresh();
        Zombie target = EntityType.ZOMBIE.create(helper.getLevel());
        target.setNoAi(true);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        target.moveTo(horse.position().add(6, 0, 0));
        helper.getLevel().addFreshEntity(target);
        scene.entities.add(target);
        helper.assertTrue(ResidentMounts.board(scene.body), "resident boards for the mounted weapon test");
        WeaponSession session = Weapons.select(WeaponSessionGameTests.worker(scene.body), target, null);
        helper.assertTrue(session.fire(target), "the mounted weapon begins its native draw or reload");
        helper.onEachTick(session::tick);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(target.getHealth() < target.getMaxHealth(),
            "a native projectile from the saddle hits the target"))
            .thenExecute(() -> {
                helper.assertTrue(scene.body.getVehicle() == horse, "ranged fire keeps the resident mounted");
                helper.assertTrue(scene.body.pack().count(Items.ARROW) == 2, "one physical arrow is consumed");
                session.close();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void unloadingHorseDefersUnassignmentUntilTheSameHorseReloads(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount()));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.refresh();
        CompoundTag saved = new CompoundTag();
        horse.saveWithoutId(saved);
        horse.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        scene.supplies.profiles().assign(List.of(scene.body.id()), Optional.empty());
        helper.assertTrue(scene.supplies.mounts().assigned(scene.body.id()),
            "an unloaded horse keeps its holding until its tack can be returned");
        Horse reloaded = EntityType.HORSE.create(helper.getLevel());
        reloaded.load(saved);
        helper.getLevel().addFreshEntity(reloaded);
        scene.entities.add(reloaded);
        scene.supplies.mounts().tick(helper.getLevel().getServer(), scene.supplies.profiles());
        helper.assertTrue(!scene.supplies.mounts().assigned(scene.body.id())
            && !reloaded.getTags().contains(PastureContent.CLAIMED)
            && scene.colony.holdingOf(reloaded.getUUID()).isEmpty(), "the reloaded horse is released exactly once");
        helper.assertTrue(!reloaded.isSaddled() && scene.body.pack().count(Items.SADDLE) == 1,
            "the saved physical saddle returns when its horse reloads");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void residentDeathReleasesItsMount(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount()));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.refresh();
        ResidentMounts.board(scene.body);
        scene.body.kill();
        helper.assertTrue(!scene.supplies.mounts().assigned(scene.body.id())
            && scene.colony.holdingOf(horse.getUUID()).isEmpty(), "resident death ends its mount's holding");
        helper.assertTrue(!horse.isSaddled() && !horse.getTags().contains(PastureContent.CLAIMED)
            && !horse.isNoAi(), "the horse is unsaddled and free after its resident dies");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void selectingWeaponsDoesNotBoardNearbyHorse(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount(),
            LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD))));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.body.pack().insert(new ItemStack(Items.IRON_SWORD));
        scene.refresh();
        Worker worker = WeaponSessionGameTests.worker(scene.body);
        double onFoot = Weapons.range(worker);
        WeaponSession session = Weapons.select(worker, null, null);
        helper.assertFalse(scene.body.isPassenger(), "melee selection does not board a nearby horse");
        session.close();
        Weapons.selectRanged(worker, null, null);
        helper.assertFalse(scene.body.isPassenger(), "ranged selection does not board a nearby horse");
        helper.assertTrue(ResidentMounts.board(scene.body), "test rider boards the assigned horse");
        helper.assertTrue(Math.abs(Weapons.range(worker) - onFoot - MountedMelee.EXTRA_REACH) < .001,
            "weapon selection and approach use the mounted melee reach");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 400)
    public static void diagonalFetchAndWaypointsKeepRiderMountedThroughShortLeg(GameTestHelper helper) {
        travel(helper, false);
    }

    @GameTest(template = "empty_large", timeoutTicks = 400)
    public static void shortPatrolFetchesHorseAndKeepsItBetweenStops(GameTestHelper helper) {
        travel(helper, true);
    }

    private static void travel(GameTestHelper helper, boolean patrol) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount()));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.refresh();
        scene.body.moveTo(helper.absolutePos(new BlockPos(2, 1, 2)).getBottomCenter());
        if (patrol) {
            scene.body.setDoing(Doing.open(PatrolContent.PATROLLING));
        }
        List<WorldPos> stops = (patrol ? List.of(6, 9, 12) : List.of(16, 19, 22)).stream()
            .map(x -> WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 4)))).toList();
        Conveyance[] leg = {scene.body.locomotion().along(1, LaborRefusal.NO_WAY_THERE)};
        int[] at = {0};
        boolean[] mounted = {false};
        leg[0].setOut(helper.getLevel(), scene.body, Set.of(stops.getFirst()));
        helper.onEachTick(() -> {
            if (at[0] == stops.size()) {
                return;
            }
            if (mounted[0]) {
                helper.assertTrue(scene.body.getVehicle() == horse, "no dismount between travel waypoints");
            }
            mounted[0] |= scene.body.getVehicle() == horse;
            Going result = leg[0].step(helper.getLevel(), scene.body, Set.of(stops.get(at[0])));
            helper.assertFalse(result instanceof Going.Failed, "the mounted leg remains reachable");
            if (result instanceof Going.Arrived) {
                leg[0].release(helper.getLevel(), scene.body);
                helper.assertTrue(scene.body.getVehicle() == horse, "arrival retains the horse");
                at[0]++;
                if (at[0] < stops.size()) {
                    scene.body.locomotion().stand(scene.body, true);
                    leg[0] = scene.body.locomotion().along(1, LaborRefusal.NO_WAY_THERE);
                    leg[0].setOut(helper.getLevel(), scene.body, Set.of(stops.get(at[0])));
                }
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(at[0] == stops.size(), "all stops reached"))
            .thenExecute(() -> {
                helper.assertTrue(mounted[0], "the resident fetched and boarded its horse");
                helper.assertTrue(Reach.standingIn(scene.body,
                    Stances.at(stops.getLast())), "mounted work uses the horse's footing");
                scene.body.locomotion().stand(scene.body, false);
                helper.assertFalse(scene.body.isPassenger(), "work requiring feet on the ground dismounts");
                helper.assertTrue(horse.isNoAi(), "the released horse is parked");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 120)
    public static void unmountedShortTripStaysOnFoot(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount()));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.refresh();
        WorldPos stop = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 7)));
        Conveyance leg = scene.body.locomotion().along(1, LaborRefusal.NO_WAY_THERE);
        leg.setOut(helper.getLevel(), scene.body, Set.of(stop));
        boolean[] arrived = {false};
        helper.onEachTick(() -> {
            helper.assertFalse(scene.body.isPassenger(), "an unmounted short trip stays on foot");
            arrived[0] |= leg.step(helper.getLevel(), scene.body, Set.of(stop)) instanceof Going.Arrived;
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(arrived[0], "short walk arrives"))
            .thenExecute(() -> leg.release(helper.getLevel(), scene.body)).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 600)
    public static void fetchedHorseStopsAtItsPostWithoutCircling(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.27);
        scene.profile(List.of(LoadoutProfile.Entry.mount()));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.refresh();
        scene.body.moveTo(helper.absolutePos(new BlockPos(2, 1, 2)).getBottomCenter());
        var walking = scene.body.getNavigation();
        WorldPos post = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(20, 1, 6)));
        Conveyance trip = scene.body.locomotion().along(1, LaborRefusal.NO_WAY_THERE);
        trip.setOut(helper.getLevel(), scene.body, Set.of(post));
        int[] arrived = {-1};
        long[] lastTick = {-1};
        Vec3[] previous = {horse.position()};
        float[] yaw = {horse.getYRot()};
        double[] measured = {0, 0};
        helper.onEachTick(() -> {
            long tick = helper.getLevel().getGameTime();
            if (tick == lastTick[0]) {
                return;
            }
            lastTick[0] = tick;
            if (arrived[0] < 0) {
                Going going = trip.step(helper.getLevel(), scene.body, Set.of(post));
                helper.assertFalse(going instanceof Going.Failed, "the rider reaches the post through FETCH and RIDE");
                if (going instanceof Going.Arrived) {
                    helper.assertTrue(walking.isDone(), "boarding releases the rider's original FETCH navigation");
                    trip.release(helper.getLevel(), scene.body);
                    scene.body.locomotion().stand(scene.body, true);
                    arrived[0] = 0;
                }
            } else {
                arrived[0]++;
                helper.assertTrue(scene.body.getVehicle() == horse, "the rider stays in the saddle at its post");
                if (arrived[0] > 20) {
                    measured[0] += horse.position().subtract(previous[0]).horizontalDistance();
                    measured[1] += Math.abs(net.minecraft.util.Mth.wrapDegrees(horse.getYRot() - yaw[0]));
                    helper.assertTrue(Reach.standingAt(horse.position(), post.cell()),
                        "the horse stays in the original work footing without a larger arrival tolerance");
                }
                if (arrived[0] >= 220) {
                    com.mojang.logging.LogUtils.getLogger().info("mounted settling: ticks=200 travel={} turn={}",
                        measured[0], measured[1]);
                    helper.assertTrue(measured[0] < .2 && measured[1] < 15,
                        "settled horse must not circle: 200 ticks travel=" + measured[0] + " turn=" + measured[1]);
                    helper.succeed();
                }
            }
            previous[0] = horse.position();
            yaw[0] = horse.getYRot();
        });
    }

    @GameTest(template = "empty_large", timeoutTicks = 160)
    public static void runnerStartsMeleeWorkWithoutDismounting(GameTestHelper helper) {
        mountedWork(helper, Items.IRON_SWORD, 2);
    }

    @GameTest(template = "empty_large", timeoutTicks = 160)
    public static void runnerStartsRangedWorkWithoutDismounting(GameTestHelper helper) {
        mountedWork(helper, Items.BOW, 6);
    }

    private static void mountedWork(GameTestHelper helper, Item weapon, int distance) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount(), LoadoutProfile.Entry.weapon(ItemSpec.of(weapon))));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.body.pack().insert(new ItemStack(weapon));
        scene.body.pack().insert(new ItemStack(Items.ARROW, 3));
        scene.refresh();
        helper.assertTrue(ResidentMounts.board(scene.body), "test rider boards before starting work");
        Zombie target = target(scene, horse.position().add(distance, 0, 0));
        Node node = new Node() {
            private WeaponSession session;
            private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), PersonContent.ID,
                WorkSite.on(target), Stances.WHEREVER, (Workload.Continuous) (level, who) -> {
                    helper.assertTrue(who.body().getVehicle() == horse, "the runner starts combat in the saddle");
                    session = Weapons.select(who, target, session);
                    if (session.state() == WeaponSession.State.READY) {
                        session.fire(target);
                    }
                    session.tick();
                    return target.getHealth() == target.getMaxHealth();
                }).done();

            public NodeSpec spec() {
                return spec;
            }

            public boolean mountedWork() {
                return true;
            }

            public Outcome commit(ServerLevel level, Worker who) {
                return Outcome.done();
            }
        };
        scene.colony.submit(PersonContent.ID, helper.getLevel().dimension(), Grown.of(node), (id, how) -> { });
        scene.labor = new LaborFixture(scene.colony, helper.getLevel());
        helper.onEachTick(scene.labor::tick);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(target.getHealth() < target.getMaxHealth(),
            "the runner lands a mounted weapon hit")).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void meleeReachHitsBeyondFootRangeFromSaddle(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount(),
            LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD))));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.body.pack().insert(new ItemStack(Items.IRON_SWORD));
        scene.refresh();
        helper.assertTrue(ResidentMounts.board(scene.body), "test rider boards");
        horse.setNoAi(true);
        Zombie target = target(scene, horse.position().add(5.6, 0, 0));
        Worker worker = WeaponSessionGameTests.worker(scene.body);
        WeaponSession session = Weapons.select(worker, target, null);
        helper.assertTrue(session.fire(target), "the mounted sword reaches beyond its on-foot range");
        helper.onEachTick(session::tick);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(target.getHealth() < target.getMaxHealth(),
            "the extended reach lands a real hit"))
            .thenExecute(session::close).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void chargeSamplesHorizontalSpeedAtImpact(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount(),
            LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD))));
        scene.body.pack().insert(new ItemStack(Items.SADDLE));
        scene.body.pack().insert(new ItemStack(Items.IRON_SWORD));
        scene.refresh();
        helper.assertTrue(ResidentMounts.board(scene.body), "test rider boards");
        horse.setNoAi(true);
        Worker worker = WeaponSessionGameTests.worker(scene.body);
        WeaponSession session = Weapons.select(worker, null, null);
        Zombie still = target(scene, horse.position().add(2, 0, 0));
        Zombie charging = target(scene, horse.position().add(2, 0, 1));
        double[] damage = {0, 0};
        double[] impulse = {0, 0};
        int[] ticks = {0};
        helper.assertTrue(session.fire(still), "stationary swing starts");
        helper.onEachTick(() -> {
            ticks[0]++;
            // Vertical movement alone must give no charge; horizontal movement starts only at the second impact.
            horse.setDeltaMovement(0, .02, 0);
            if (ticks[0] == 20) {
                helper.assertTrue(session.fire(charging), "second swing starts while stationary");
            }
            if (ticks[0] == 29) {
                horse.move(MoverType.SELF, new Vec3(MountedMelee.FULL_CHARGE_SPEED, 0, 0));
            }
            Zombie victim = ticks[0] < 20 ? still : charging;
            float before = victim.getHealth();
            Vec3 velocity = victim.getDeltaMovement();
            session.tick();
            if (victim.getHealth() < before) {
                int index = ticks[0] < 20 ? 0 : 1;
                damage[index] = before - victim.getHealth();
                impulse[index] = victim.getDeltaMovement().subtract(velocity).horizontalDistance();
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(damage[1] > 0, "charge strike lands"))
            .thenExecute(() -> {
                helper.assertTrue(Math.abs(damage[1] / damage[0] - 1.5) < .06,
                    "full horizontal speed adds fifty percent damage at impact");
                helper.assertTrue(Math.abs(impulse[1] / impulse[0] - 2) < .06,
                    "full horizontal speed doubles native knockback");
                helper.assertFalse(scene.body.getAttribute(Attributes.ATTACK_DAMAGE).hasModifier(
                    ResourceLocation.fromNamespaceAndPath("folkways", "mounted_charge")),
                    "the charge bonus is removed after the strike");
                session.close();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void razingAColonyReleasesItsMountsClaimMarker(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        Horse horse = scene.horse();
        scene.profile(List.of(LoadoutProfile.Entry.mount()));
        scene.refresh();
        helper.assertTrue(horse.getTags().contains(PastureContent.CLAIMED)
                && scene.colony.holdingOf(horse.getUUID()).isPresent(), "the mount is claimed");
        scene.colony.raze();
        helper.assertTrue(scene.colony.holdingOf(horse.getUUID()).isEmpty()
                && !horse.getTags().contains(PastureContent.CLAIMED) && !horse.isNoAi(),
            "mount teardown clears the claim even after razing has forgotten the holdings");
        helper.succeed();
    }

    private static Zombie target(Scene scene, Vec3 at) {
        Zombie zombie = EntityType.ZOMBIE.create(scene.helper.getLevel());
        zombie.moveTo(at);
        zombie.setNoAi(true);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        zombie.getAttribute(Attributes.ARMOR).setBaseValue(0);
        scene.helper.getLevel().addFreshEntity(zombie);
        scene.entities.add(zombie);
        return zombie;
    }

    private static final class Scene implements GameTestListener {
        private final GameTestHelper helper;
        private final Colony colony;
        private final PatrolSupplies supplies;
        private final ResidentEntity body;
        private final List<Entity> entities = new ArrayList<>();
        private LaborFixture labor;
        private boolean penCreated;
        private final ChunkPos ticking;

        private Scene(GameTestHelper helper) {
            this.helper = helper;
            colony = Colonies.mint(helper.getLevel().getServer());
            supplies = PatrolSupplies.of(colony);
            for (int x = 0; x < 25; x++) {
                for (int z = 0; z < 9; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            body = PersonBody.RESIDENT.get().create(helper.getLevel());
            body.moveTo(helper.absolutePos(new BlockPos(2, 1, 4)).getBottomCenter());
            ticking = body.chunkPosition();
            helper.getLevel().getChunkSource().addRegionTicket(TICKING, ticking, 2, body.id());
            helper.getLevel().addFreshEntity(body);
            colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            entities.add(body);
            helper.testInfo.addListener(this);
        }

        private Horse horse() {
            Set<BlockPos> cells = new LinkedHashSet<>();
            for (int x = 1; x <= 6; x++) {
                for (int z = 1; z <= 7; z++) {
                    cells.add(helper.absolutePos(new BlockPos(x, 0, z)));
                }
            }
            if (!penCreated) {
                ColonyFront.of(colony).addZone(helper.getLevel(), PastureContent.PASTURE, cells,
                    ColonySettings.empty().with(PastureContent.ANIMAL.key(),
                        new ColonySettings.Value.Choice(ResourceLocation.withDefaultNamespace("horse"))))
                    .orElseThrow();
                penCreated = true;
            }
            Horse horse = EntityType.HORSE.create(helper.getLevel());
            horse.setTamed(true);
            horse.setAge(0);
            horse.moveTo(helper.absolutePos(new BlockPos(4, 1, 4)).getBottomCenter());
            helper.getLevel().addFreshEntity(horse);
            entities.add(horse);
            return horse;
        }

        private LoadoutProfile profile(List<LoadoutProfile.Entry> entries) {
            LoadoutProfile created = supplies.profiles().create("Rider");
            LoadoutProfile profile = new LoadoutProfile(created.id(), created.name(), entries);
            supplies.profiles().replace(profile);
            supplies.profiles().assign(List.of(body.id()), Optional.of(profile.id()));
            return profile;
        }

        private void refresh() {
            supplies.mounts().reconcile(helper.getLevel(), body, supplies.profiles().assigned(body.id()));
        }

        private void close() {
            if (labor != null) {
                labor.close();
            }
            body.stopWeaponSession();
            supplies.mounts().reconcile(helper.getLevel(), body, Optional.empty());
            entities.forEach(Entity::discard);
            helper.getLevel().getChunkSource().removeRegionTicket(TICKING, ticking, 2, body.id());
            colony.raze();
        }

        public void testStructureLoaded(GameTestInfo info) {
        }

        public void testPassed(GameTestInfo info, GameTestRunner runner) {
            close();
        }

        public void testFailed(GameTestInfo info, GameTestRunner runner) {
            close();
        }

        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {
        }
    }
}
