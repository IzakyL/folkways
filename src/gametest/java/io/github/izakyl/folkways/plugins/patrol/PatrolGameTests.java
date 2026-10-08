package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PatrolGameTests {

    private static final ResourceLocation METAL_GOLEM =
        ResourceLocation.fromNamespaceAndPath("modulargolems", "metal_golem");
    private static final ResourceLocation HUMANOID_GOLEM =
        ResourceLocation.fromNamespaceAndPath("modulargolems", "humanoid_golem");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PatrolGameTests.class);
    }

    @GameTest(template = "empty")
    public static void aWardedStopKeepsNaturalMonstersFromSpawningRoundIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stop = helper.absolutePos(new BlockPos(1, 1, 1));
        int radius = FolkwaysConfig.patrolWardRadius();
        long now = level.getGameTime();
        Wards.ward(level, stop, now + 100);
        try {
            helper.assertTrue(Wards.warded(level, stop.offset(radius - 1, 0, 0)), "a stop wards what is in its reach");
            helper.assertFalse(Wards.warded(level, stop.offset(radius + 1, 0, 0)), "a stop wards nothing past its reach");
            helper.assertFalse(Wards.warded(level, stop.above(radius + 1)), "a stop wards nothing far above it");

            Zombie zombie = EntityType.ZOMBIE.create(level);
            zombie.moveTo(stop.getX() + 2.5, stop.getY(), stop.getZ() + 0.5);
            MobSpawnEvent.PositionCheck natural = new MobSpawnEvent.PositionCheck(zombie, level, MobSpawnType.NATURAL, null);
            Wards.positionCheck(natural);
            helper.assertValueEqual(natural.getResult(), MobSpawnEvent.PositionCheck.Result.FAIL,
                "a monster spawning of its own accord in a ward");
            MobSpawnEvent.PositionCheck spawner = new MobSpawnEvent.PositionCheck(zombie, level, MobSpawnType.SPAWNER, null);
            Wards.positionCheck(spawner);
            helper.assertValueEqual(spawner.getResult(), MobSpawnEvent.PositionCheck.Result.DEFAULT,
                "a monster from a spawner in a ward");

            Wards.ward(level, stop, now - 1);
            helper.assertTrue(Wards.until(level, stop) > now, "a later ward is never cut short by an earlier one");
        } finally {
            Wards.stopped(null);
        }
        helper.assertFalse(Wards.warded(level, stop), "wards are forgotten when the server stops");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stopsAreLaidAlongADrawnRouteNoFurtherApartThanAWardReaches(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 8; x++) {
            helper.setBlock(new BlockPos(x, 0, 0), Blocks.STONE);
        }
        for (int z = 0; z < 8; z++) {
            helper.setBlock(new BlockPos(7, 0, z), Blocks.STONE);
        }
        BlockPos start = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos corner = helper.absolutePos(new BlockPos(7, 0, 0));
        BlockPos end = helper.absolutePos(new BlockPos(7, 0, 7));
        List<Stop> stops = Beats.along(level, List.of(start, corner, end));
        helper.assertTrue(stops.size() >= 3, "a route with a corner stops at its start, corner and end; got " + stops);
        helper.assertValueEqual(stops.getFirst().at().cell(), start.above(), "the first stop, on the start");
        helper.assertValueEqual(stops.getLast().at().cell(), end.above(), "the last stop, on the end");
        int reach = Math.max(4, FolkwaysConfig.patrolWardRadius());
        for (int at = 1; at < stops.size(); at++) {
            double apart = Math.sqrt(stops.get(at - 1).at().cell().distSqr(stops.get(at).at().cell()));
            helper.assertTrue(apart <= reach + 1, "neighbouring stops " + apart + " apart, past a ward's reach");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLapWalksTheRouteInOrderByOnePatrollerAndTheNextComesBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Stop> stops = List.of(
            new Stop(WorldPos.of(level, helper.absolutePos(new BlockPos(0, 1, 0))), Stances.WHEREVER),
            new Stop(WorldPos.of(level, helper.absolutePos(new BlockPos(3, 1, 0))), Stances.WHEREVER),
            new Stop(WorldPos.of(level, helper.absolutePos(new BlockPos(6, 1, 0))), Stances.WHEREVER));
        Colony colony = Colonies.mint(level.getServer());
        try {
            PatrolPresence patrol = new PatrolPresence(colony);
            UUID route = UUID.randomUUID();
            Grown out = patrol.round(route, 0, stops);
            Grown back = patrol.round(route, 1, stops);
            helper.assertValueEqual(out.links().size(), 2, "links chaining three stops");
            helper.assertValueEqual(out.workerGroups().size(), 1, "worker groups on one lap");
            helper.assertTrue(out.workerGroups().getFirst().size() == 3, "one patroller walks the whole lap");
            helper.assertValueEqual(focus(out, 0), stops.getFirst().at(), "the first lap setting out from the start");
            helper.assertValueEqual(focus(back, 0), stops.getLast().at(), "the next lap setting out from the end");
            helper.assertFalse(out.nodes().getFirst().id().equals(back.nodes().getFirst().id()),
                "each lap is new work, not the last one offered again");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    private static WorldPos focus(Grown lap, int at) {
        return lap.nodes().get(at).spec().site().where();
    }

    @GameTest(template = "empty")
    public static void onlyAnArmedPatrollerOnDutyGoesForAMonster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 3; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Colony colony = Colonies.mint(level.getServer());
        Body guard = PersonBody.RESIDENT.get().create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        place(helper, guard.mob(), new BlockPos(1, 1, 1));
        place(helper, zombie, new BlockPos(4, 1, 1));
        zombie.setNoAi(true);
        try {
            PatrolPresence patrol = new PatrolPresence(colony);
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            helper.assertTrue(patrol.urges(worker(guard, sword), colony.view(level)).isEmpty(),
                "a resident who has not stood watch is not on patrol");

            patrol.watched(guard.id(), level.getGameTime());
            helper.assertTrue(patrol.urges(worker(guard, ItemStack.EMPTY), colony.view(level)).isEmpty(),
                "a resident on patrol with no weapon does not go for a monster");
            List<Urge> urges = patrol.urges(worker(guard, sword), colony.view(level));
            helper.assertTrue(urges.size() == 1 && urges.get(0).id().equals(PatrolPresence.ENGAGE),
                "an armed resident on patrol goes for the zombie beside it");
            helper.assertTrue(urges.get(0).weight() > Urge.DIRE && urges.get(0).preempts(),
                "a patroller at full health stands its ground over fleeing");
        } finally {
            guard.mob().discard();
            zombie.discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aStrikeHitsWithTheWeaponInHand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        Body guard = PersonBody.RESIDENT.get().create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        place(helper, guard.mob(), new BlockPos(1, 1, 1));
        place(helper, zombie, new BlockPos(2, 1, 1));
        zombie.setNoAi(true);
        guard.presentHeldItem(new ItemStack(Items.IRON_SWORD));
        helper.runAfterDelay(2, () -> {
            try {
                float before = zombie.getHealth();
                StrikeNode strike = new StrikeNode(level, zombie, Stances.WHEREVER);
                strike.commit(level, worker(guard, guard.mob().getMainHandItem()));
                helper.assertTrue(before - zombie.getHealth() >= 5.0F,
                    "an iron sword strikes for its own damage, not a bare fist's; took " + (before - zombie.getHealth()));
            } finally {
                guard.mob().discard();
                zombie.discard();
                colony.raze();
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void aKindThatFightsBarehandedNeedsNoWeaponToPatrol(GameTestHelper helper) {
        NodeSpec watch = new WatchNode(UUID.randomUUID(),
            new Stop(WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO)),
                Stances.WHEREVER), null).spec();
        helper.assertFalse(Tools.equipped(PersonBody.ID, watch, List.of()), "a person patrols only armed");
        helper.assertTrue(Tools.equipped(PersonBody.ID, watch, List.of(new ItemStack(Items.STONE_AXE))),
            "any melee weapon arms a patroller");
        helper.assertFalse(Tools.equipped(PersonBody.ID, watch, List.of(new ItemStack(Items.STICK))),
            "a stick is no weapon");
        if (ResidentKinds.of(METAL_GOLEM).isPresent()) {
            helper.assertTrue(Tools.equipped(METAL_GOLEM, watch, List.of()), "a metal golem patrols with its fists");
            helper.assertFalse(Tools.equipped(HUMANOID_GOLEM, watch, List.of()), "a humanoid golem needs a weapon");
            helper.assertFalse(ResidentKinds.barehanded(METAL_GOLEM, Optional.empty()),
                "barehanded speaks only of trades it names");
        }
        helper.succeed();
    }

    private static void place(GameTestHelper helper, Mob mob, BlockPos relative) {
        BlockPos at = helper.absolutePos(relative);
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);
    }

    private static Worker worker(Body body, ItemStack weapon) {
        Container pack = new SimpleContainer(1);
        pack.setItem(0, weapon);
        return new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body.mob(); }
            public Container pack() { return pack; }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return Tools.answers(weapon, need) ? weapon : ItemStack.EMPTY; }
            public void spill(ItemStack stack) { throw new AssertionError("patrolling must not spill items"); }
            public int rankOf(String perk) { return 0; }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }
}
