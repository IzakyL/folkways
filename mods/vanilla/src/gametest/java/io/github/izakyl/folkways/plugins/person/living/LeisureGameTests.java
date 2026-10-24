package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Trip;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LeisureGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(LeisureGameTests.class); }

    @GameTest(template = "empty")
    public static void residentsCanEnjoyColonyParrotsButRespectAnotherOwnersPet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        LivingPresence living = new LivingPresence(colony);
        Leisure leisure = new Leisure(colony, living);
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        for (BlockPos floor : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, -1, 3))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        ResidentEntity actor = person(level, at);
        Parrot pet = EntityType.PARROT.create(level);
        pet.getPersistentData().putUUID("folkways_colony_pet", colony.id());
        pet.setNoAi(true);
        pet.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 1.5);
        level.addFreshEntity(pet);
        try {
            seedChoice(level, 1);
            TestWorker who = new TestWorker(actor);
            Node action = leisure.urge(who, colony.view(level)).orElseThrow().take().get();
            helper.assertTrue(action.spec().doing().getPath().equals("petting"), "untamed colony parrot can be enjoyed");
            double before = living.mood(actor.getUUID()).leisure;
            for (int i = 0; i < 100; i++) {
                helper.assertTrue(((Workload.Continuous) action.spec().workload()).holds(level, who), "parrot interaction continues");
            }
            helper.assertTrue(living.mood(actor.getUUID()).leisure > before, "parrot restores recreation");
            action.ended(level, Ending.DROPPED);
            pet.setTame(true, true);
            pet.setOwnerUUID(UUID.randomUUID());
            // Use a new scheduler so the previous activity's retry delay does not mask the ownership check.
            leisure = new Leisure(colony, living);
            seedChoice(level, 1);
            Node other = leisure.urge(who, colony.view(level)).orElseThrow().take().get();
            helper.assertFalse(other.spec().doing().getPath().equals("petting"), "adoption by another owner takes priority over colony origin");
            other.ended(level, Ending.DROPPED);
        } finally { actor.discard(); pet.discard(); leisure.clear(); colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pettingRecoversGraduallyAndCancellationReleasesThePet(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        UUID owner = UUID.randomUUID(); Charters.keep(colony, Charter.founded("pets", owner));
        LivingPresence living = new LivingPresence(colony);
        Leisure leisure = new Leisure(colony, living);
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        for (BlockPos floor : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, -1, 3))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        ResidentEntity actor = person(level, at);
        ResidentEntity other = person(level, at.east(2));
        Wolf pet = EntityType.WOLF.create(level);
        pet.setTame(true, true); pet.setOwnerUUID(owner); pet.setOrderedToSit(true); pet.setNoAi(true);
        pet.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 1.5); level.addFreshEntity(pet);
        try {
            seedChoice(level, 1);
            TestWorker who = new TestWorker(actor);
            Node action = leisure.urge(who, colony.view(level)).orElseThrow().take().get();
            helper.assertTrue(action.spec().doing().getPath().equals("petting"), "member's wolf is chosen");
            action.spec().gesture().perform(actor, 0, actor.blockPosition());
            helper.assertValueEqual(actor.leisureMotion(), "petting", "dedicated synchronized motion");
            helper.assertFalse(actor.swinging, "petting never uses the attack swing");
            double before = living.mood(actor.getUUID()).leisure;
            for (int i = 0; i < 100; i++) {
                helper.assertTrue(((Workload.Continuous) action.spec().workload()).holds(level, who), "interaction remains available");
            }
            helper.assertTrue(living.mood(actor.getUUID()).leisure > before + 4.9, "partial interaction restores entertainment");
            helper.assertTrue(pet.isOrderedToSit(), "owner's sit order is preserved");
            seedChoice(level, 1);
            Node blocked = leisure.urge(new TestWorker(other), colony.view(level)).orElseThrow().take().get();
            helper.assertFalse(blocked.spec().doing().getPath().equals("petting"), "one resident per pet");
            blocked.ended(level, Ending.DROPPED);
            action.spec().gesture().release(actor); action.released(level, who, Ending.DROPPED);
            helper.assertValueEqual(actor.leisureMotion(), "", "cancellation clears the motion");
            helper.assertTrue(living.mood(actor.getUUID()).leisure > before, "cancellation keeps recovery");
            // A fresh resident can reserve the pet after the original interaction is cancelled.
            ResidentEntity third = person(level, at.west(2));
            try {
                seedChoice(level, 1);
                Node next = leisure.urge(new TestWorker(third), colony.view(level)).orElseThrow().take().get();
                helper.assertTrue(next.spec().doing().getPath().equals("petting"), "reservation released");
                next.ended(level, Ending.DROPPED);
            } finally { third.discard(); }
        } finally { actor.discard(); other.discard(); pet.discard(); leisure.clear(); colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void recreationYieldsToWorkButAnExhaustedResidentCanTakeABreak(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        LivingPresence living = new LivingPresence(colony);
        Leisure leisure = new Leisure(colony, living);
        ResidentEntity actor = person(level, helper.absolutePos(new BlockPos(3, 2, 3)));
        try {
            TestWorker who = new TestWorker(actor);
            Node queued = new Node() {
                final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), LivingContent.ID, WorkSite.on(actor), Stances.WHEREVER, Workload.Once.of(20)).done();
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker worker) { return Outcome.done(); }
            };
            who.route = List.of(queued);
            helper.assertTrue(leisure.urge(who, colony.view(level)).isEmpty(), "normal leisure cannot take queued work's place");
            living.mood(actor.getUUID()).leisure = 10;
            living.mood(actor.getUUID()).nextBreak = level.getGameTime();
            Urge urge = leisure.urge(who, colony.view(level)).orElseThrow();
            helper.assertFalse(urge.preempts(), "short break waits for a work boundary");
            helper.assertTrue(urge.weight() == Urge.SPARE, "low recreation may request a short break");
        } finally { actor.discard(); leisure.clear(); colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void chatPartnersWaitToMeetAndDangerEndsTheConversation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        LivingPresence living = new LivingPresence(colony);
        Leisure leisure = new Leisure(colony, living);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        for (BlockPos floor : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(8, -1, 2))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        ResidentEntity first = person(level, at), second = person(level, at.east(6));
        var view = new ColonyView() {
            public ServerLevel level() { return level; }
            public ResourceKey<Level> dimension() { return level.dimension(); }
            public List<Holding> holdings() { return List.of(); }
            public Set<BlockPos> blocks(ResourceLocation owner) { return Set.of(); }
            public List<Resident> residents() { return List.of(first.resident(), second.resident()); }
            public Effects effectsOf(Resident resident) { return Effects.NONE; }
        };
        try {
            TestWorker one = new TestWorker(first), two = new TestWorker(second);
            seedChoice(level, 0);
            Node initiating = leisure.urge(one, view).orElseThrow().take().get();
            Node invited = leisure.urge(two, view).orElseThrow().take().get();
            helper.assertTrue(initiating.spec().doing().getPath().equals("chatting"), "conversation chosen");
            double before = living.mood(second.getUUID()).leisure;
            helper.assertTrue(((Workload.Continuous) invited.spec().workload()).holds(level, two), "invited partner waits for approach from six blocks away");
            helper.assertTrue(living.mood(second.getUUID()).leisure == before, "waiting alone gives no social reward");
            first.moveTo(second.getX() - 1, second.getY(), second.getZ());
            first.setDoing(Doing.open(initiating.spec().doing())); second.setDoing(Doing.open(invited.spec().doing()));
            for (int i = 0; i < 100; i++) {
                helper.assertTrue(((Workload.Continuous) initiating.spec().workload()).holds(level, one), "initiator chats");
                helper.assertTrue(((Workload.Continuous) invited.spec().workload()).holds(level, two), "partner chats");
            }
            initiating.spec().gesture().perform(first, 0, at); invited.spec().gesture().perform(second, 0, at);
            helper.assertTrue(first.leisurePhase() != second.leisurePhase(), "partners alternate speaking gestures");
            helper.assertTrue(living.mood(second.getUUID()).leisure > before, "meeting restores recreation");
            first.setLastHurtByMob(EntityType.ZOMBIE.create(level));
            helper.assertTrue(leisure.urge(one, view).isEmpty(), "being attacked cancels active leisure");
            helper.assertFalse(((Workload.Continuous) invited.spec().workload()).holds(level, two), "the other partner also stops");
            initiating.released(level, one, Ending.DROPPED); invited.released(level, two, Ending.DROPPED);
            first.clearDoing(); second.clearDoing();
        } finally { first.discard(); second.discard(); leisure.clear(); colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void anActiveIdleTripYieldsToNewlyQueuedWorkBeforeTheLeisureArrival(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        for (BlockPos floor : BlockPos.betweenClosed(at.offset(-7, -1, -7), at.offset(7, -1, 7))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        for (boolean awaiting : List.of(true, false)) {
            Colony colony = Colonies.mint(level.getServer());
            LivingPresence living = new LivingPresence(colony);
            Leisure leisure = new Leisure(colony, living);
            ResidentEntity actor = travellingPerson(helper, at);
            Trip trip = null;
            try {
                TestWorker who = new TestWorker(actor);
                seedChoice(level, 2);
                Node action = leisure.urge(who, colony.view(level)).orElseThrow().take().get();
                helper.assertTrue(action.spec().doing().getPath().equals("strolling"),
                    "the seeded ordinary activity is a real stroll, not a mandatory rest break");
                trip = leisureTrip(actor, action, awaiting);
                trip.setOut(level, actor, action.spec().stances().cells());
                // Match BodyRunner's publication of the actual Trip's WAITING/WALKING state.
                actor.setDoing(trip.doing().orElseThrow().headingFor(action.spec().doing(),
                    action.spec().about(), Doing.Wares.NONE));
                helper.assertTrue(actor.doing().orElseThrow().what().equals(awaiting ? Doings.WAITING : Doings.WALKING),
                    "the ordinary activity has an actual native trip still waiting or walking before arrival");
                System.out.println("LEISURE_TRIP_FIXTURE awaiting=" + awaiting + " position=" + actor.position()
                    + " onGround=" + actor.onGround() + " target=" + action.spec().stances().cells()
                    + " nativeNavigationDone=" + actor.getNavigation().isDone());
                helper.assertTrue(awaiting || !actor.getNavigation().isDone(),
                    "the walking control has a real native path toward its supported activity destination");
                helper.assertTrue(leisure.urge(who, colony.view(level)).orElseThrow().weight() == Urge.IDLE,
                    "the same active ordinary trip remains available while there is no queued work");
                Node queued = queuedWork(actor);
                who.route = List.of(queued);
                System.out.println("LEISURE_ROUTE_HANDOFF awaiting=" + awaiting + " actor=" + actor.getUUID()
                    + " doing=" + actor.doing() + " trip=" + trip.describe() + " queued=" + queued.id());
                helper.assertTrue(leisure.urge(who, colony.view(level)).isEmpty(),
                    "an active ordinary idle trip must yield to real queued work before arrival instead of waiting for session expiry; awaiting=" + awaiting);
                helper.assertFalse(((Workload.Continuous) action.spec().workload()).holds(level, who),
                    "the cancelled session releases the previous action and its reservation");
                action.released(level, who, Ending.DROPPED);
            } finally {
                if (trip != null) trip.release(level, actor);
                actor.discard(); leisure.clear(); colony.raze();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void arrivedLeisureKeepsItsFortyTickGraceAndATravellingRestBreakKeepsSpare(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        for (BlockPos clear : BlockPos.betweenClosed(at.offset(-7, -2, -7), at.offset(7, 1, 7))) {
            level.setBlockAndUpdate(clear, Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
        Colony colony = Colonies.mint(level.getServer());
        LivingPresence living = new LivingPresence(colony);
        Leisure leisure = new Leisure(colony, living);
        ResidentEntity actor = travellingPerson(helper, at);
        Trip trip = null;
        try {
            TestWorker who = new TestWorker(actor);
            seedChoice(level, 2);
            Node arrived = leisure.urge(who, colony.view(level)).orElseThrow().take().get();
            helper.assertTrue(arrived.spec().stances().cells().stream().allMatch(cell -> cell.block(level).equals(actor.blockPosition())),
                "the fallback activity is already at the actual resident's sole supported cell");
            actor.setDoing(Doing.open(arrived.spec().doing()));
            who.route = List.of(queuedWork(actor));
            helper.assertTrue(leisure.urge(who, colony.view(level)).isPresent(),
                "already-arrived recreation keeps its existing minimum enjoyment instead of being cancelled as a trip");
            Workload.Continuous holding = (Workload.Continuous) arrived.spec().workload();
            for (int tick = 0; tick < 40; tick++) {
                helper.assertTrue(holding.holds(level, who), "the arrived activity retains its original forty work ticks");
            }
            helper.assertFalse(holding.holds(level, who), "the arrived ordinary activity then returns queued work");
            arrived.released(level, who, Ending.DROPPED);
            leisure.clear();
            actor.clearDoing();
            who.route = List.of();
            living.mood(actor.getUUID()).leisure = 10;
            living.mood(actor.getUUID()).nextBreak = level.getGameTime();
            for (BlockPos floor : BlockPos.betweenClosed(at.offset(-7, -1, -7), at.offset(7, -1, 7))) {
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            }
            seedChoice(level, 2);
            Node rest = leisure.urge(who, colony.view(level)).orElseThrow().take().get();
            trip = leisureTrip(actor, rest, true);
            trip.setOut(level, actor, rest.spec().stances().cells());
            actor.setDoing(trip.doing().orElseThrow().headingFor(rest.spec().doing(), rest.spec().about(), Doing.Wares.NONE));
            who.route = List.of(queuedWork(actor));
            Urge offered = leisure.urge(who, colony.view(level)).orElseThrow();
            helper.assertTrue(offered.weight() == Urge.SPARE && !offered.preempts(),
                "a genuinely exhausted traveller keeps its existing non-preemptive rest-break policy with work queued");
            rest.released(level, who, Ending.DROPPED);
        } finally {
            if (trip != null) trip.release(level, actor);
            actor.discard(); leisure.clear(); colony.raze();
        }
        helper.succeed();
    }

    private static Trip leisureTrip(ResidentEntity actor, Node action, boolean awaiting) {
        Set<WorldPos> target = action.spec().stances().cells();
        if (!awaiting) return Trip.afoot(actor.kind().locomotion(), target, 0.75, LaborRefusal.NO_WAY_THERE).orElseThrow();
        // Keep the real Awaiting conveyance unresolved deterministically, as in the captured CI case.
        Ways pending = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) { return Faring.LATER; }
            public Faring anyoneReaches(Set<WorldPos> goals) { return Faring.LATER; }
        };
        return Trip.to(() -> pending, actor.resident(), actor.kind().locomotion(), actor,
            target, 0.75, LaborRefusal.NO_WAY_THERE).orElseThrow();
    }

    private static Node queuedWork(ResidentEntity actor) {
        return new Node() {
            final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), LivingContent.ID,
                WorkSite.on(actor), Stances.WHEREVER, Workload.Once.of(20)).done();
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
    }

    private static ResidentEntity travellingPerson(GameTestHelper helper, BlockPos at) {
        ServerLevel level = helper.getLevel();
        ResidentEntity actor = PersonBody.RESIDENT.get().create(level);
        actor.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        helper.assertTrue(level.noCollision(actor, actor.getBoundingBox()) && level.getBlockCollisions(actor,
                actor.getBoundingBox().move(0.0D, -0.0001D, 0.0D)).iterator().hasNext(),
            "the new traveller's initial actual body is collision-free and physically rests on a real floor");
        // This synchronous guard fixture asks for a path before the first physics tick. Initialize
        // only its truthfully grounded creation pose, which native GroundPathNavigation requires.
        actor.setOnGround(true);
        level.addFreshEntity(actor);
        return actor;
    }

    private static void seedChoice(ServerLevel level, int choice) {
        long seed = 0;
        while (RandomSource.create(seed).nextInt(3) != choice) { seed++; }
        level.random.setSeed(seed);
    }

    private static ResidentEntity person(ServerLevel level, BlockPos at) {
        ResidentEntity person = PersonBody.RESIDENT.get().create(level); person.setNoAi(true);
        person.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5); level.addFreshEntity(person); return person;
    }

    private static final class TestWorker implements Worker {
        final ResidentEntity person;
        List<Node> route = List.of();
        TestWorker(ResidentEntity person) { this.person = person; }
        public Resident resident() { return person.resident(); }
        public Mob body() { return person; }
        public Container pack() { return person.pack(); }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { throw new AssertionError("leisure cannot spill goods"); }
        public Effects effects() { return Effects.NONE; }
        public List<Node> route() { return route; }
        public Placement placement() { return Placement.NOWHERE; }
    }
}
