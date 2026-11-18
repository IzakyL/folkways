package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.colony.internal.ColonySnapshot;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Shown;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.runtime.ColonyLabor;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.core.effect.Effects;
import io.github.izakyl.folkways.core.front.engine.charter.Charter;
import io.github.izakyl.folkways.core.front.engine.charter.Charters;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class LeisureGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(LeisureGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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
            TestWorker who = TestWorker.of(actor).effects(Effects.NONE);
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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
            TestWorker who = TestWorker.of(actor).effects(Effects.NONE);
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
            Node blocked = leisure.urge(TestWorker.of(other).effects(Effects.NONE), colony.view(level)).orElseThrow().take().get();
            helper.assertFalse(blocked.spec().doing().getPath().equals("petting"), "one resident per pet");
            blocked.ended(level, Ending.DROPPED);
            action.spec().gesture().release(actor); action.released(level, who, Ending.DROPPED);
            helper.assertValueEqual(actor.leisureMotion(), "", "cancellation clears the motion");
            helper.assertTrue(living.mood(actor.getUUID()).leisure > before, "cancellation keeps recovery");
            // A fresh resident can reserve the pet after the original interaction is cancelled.
            ResidentEntity third = person(level, at.west(2));
            try {
                seedChoice(level, 1);
                Node next = leisure.urge(TestWorker.of(third).effects(Effects.NONE), colony.view(level)).orElseThrow().take().get();
                helper.assertTrue(next.spec().doing().getPath().equals("petting"), "reservation released");
                next.ended(level, Ending.DROPPED);
            } finally { third.discard(); }
        } finally { actor.discard(); other.discard(); pet.discard(); leisure.clear(); colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void routedWorkEndsOrdinaryLeisureForBothChatPartners(GameTestHelper helper) throws Exception {
        for (boolean routeInitiator : List.of(true, false)) {
            try (RoutedLeisure h = new RoutedLeisure(helper, true)) {
                seedChoice(h.level, 0);
                h.interrupt(h.actor, h.runner);
                h.interrupt(h.peer, h.partner);
                helper.assertValueEqual(h.runner.interruptedWork().orElseThrow().spec().doing().getPath(),
                    "chatting", "the first resident starts a conversation");
                helper.assertValueEqual(h.partner.interruptedWork().orElseThrow().spec().doing().getPath(),
                    "chatting", "the invited resident joins the same conversation");

                ResidentEntity leaving = routeInitiator ? h.actor : h.peer;
                ResidentEntity staying = routeInitiator ? h.peer : h.actor;
                BodyRunner worker = routeInitiator ? h.runner : h.partner;
                BodyRunner other = routeInitiator ? h.partner : h.runner;
                Node job = h.queue(leaving, worker);
                h.interrupt(leaving, worker);
                helper.assertTrue(worker.softPreempt().isPresent(), "leisure has a chance to yield softly");
                h.labor.tick(worker, h.level, leaving);
                helper.assertValueEqual(worker.holding().orElseThrow(), job.id(), "the resident leaves for work");
                h.interrupt(staying, other);
                helper.assertTrue(other.ownUrge().isEmpty(), "the shared chat ends for the other resident too");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void routedWorkDoesNotInterruptARestBreak(GameTestHelper helper) throws Exception {
        try (RoutedLeisure h = new RoutedLeisure(helper, false)) {
            LivingPresence living = h.colony.service(LivingContent.ID, LivingPresence.class).orElseThrow();
            living.mood(h.actor.id()).leisure = 10;
            living.mood(h.actor.id()).nextBreak = h.level.getGameTime();
            Node job = h.queue(h.actor, h.runner);
            h.interrupt(h.actor, h.runner);
            helper.assertValueEqual(h.runner.ownUrge().orElseThrow(), Leisure.URGE,
                "the exhausted resident takes a break with ready work on the route");
            Node breakActivity = h.runner.interruptedWork().orElseThrow();
            h.interrupt(h.actor, h.runner);
            h.labor.tick(h.runner, h.level, h.actor);
            helper.assertTrue(h.runner.softPreempt().isEmpty(), "routed work does not preempt the break");
            helper.assertValueEqual(h.runner.interruptedWork().orElseThrow().id(), breakActivity.id(),
                "the same break is still running");
            helper.assertValueEqual(h.runner.queued(), List.of(job.id()), "work stays queued for after the break");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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
            public List<Claim> claims() { return List.of(); }
            public Set<BlockPos> blocks(ResourceLocation owner) { return Set.of(); }
            public List<Resident> residents() { return List.of(first.resident(), second.resident()); }
            public Effects effectsOf(Resident resident) { return Effects.NONE; }
        };
        try {
            TestWorker one = TestWorker.of(first).effects(Effects.NONE), two = TestWorker.of(second).effects(Effects.NONE);
            seedChoice(level, 0);
            Node initiating = leisure.urge(one, view).orElseThrow().take().get();
            Node invited = leisure.urge(two, view).orElseThrow().take().get();
            helper.assertTrue(initiating.spec().doing().getPath().equals("chatting"), "conversation chosen");
            first.setDoing(Doing.heading(initiating.spec().doing(), Optional.empty(), Doing.Wares.NONE));
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

    private static final class RoutedLeisure implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final ColonyLabor labor;
        final ColonyView view;
        final ResidentEntity actor, peer;
        final BodyRunner runner, partner;

        RoutedLeisure(GameTestHelper helper, boolean chat) {
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            ColonyData data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            labor = new ColonyLabor(data, level.dimension());
            BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
            for (BlockPos floor : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(4, -1, 2))) {
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            }
            actor = person(level, at);
            runner = enroll(actor);
            peer = chat ? person(level, at.east(2)) : null;
            partner = chat ? enroll(peer) : null;
            view = ColonySnapshot.of(data, level);
        }

        BodyRunner enroll(ResidentEntity person) {
            colony.hold(PersonContent.ID, Claimed.Entity.of(person)).orElseThrow();
            person.joinColony(colony.id());
            return new BodyRunner(person.id(), labor.execution());
        }

        void interrupt(ResidentEntity person, BodyRunner runner) throws ReflectiveOperationException {
            var method = ColonyLabor.class.getDeclaredMethod("interrupt", ServerLevel.class, ColonyView.class,
                Body.class, BodyRunner.class);
            method.setAccessible(true);
            method.invoke(labor, level, view, person, runner);
        }

        Node queue(ResidentEntity person, BodyRunner runner) {
            Node job = new Node() {
                final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), LivingContent.ID, WorkSite.on(person),
                    Stances.WHEREVER, Workload.Once.of(1000)).done();
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker worker) { return Outcome.done(); }
            };
            PlanningFixture.apply(labor, level, List.of(), Shown.delta(job));
            runner.follow(List.of(job.id()));
            return job;
        }

        @Override
        public void close() {
            runner.letGo(level, actor);
            actor.discard();
            if (partner != null) {
                partner.letGo(level, peer);
                peer.discard();
            }
            labor.close();
            colony.raze();
        }
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

}
