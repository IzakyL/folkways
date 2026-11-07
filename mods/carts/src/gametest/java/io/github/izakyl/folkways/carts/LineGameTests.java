package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.internal.Stands;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.internal.Claims;
import io.github.izakyl.folkways.core.plan.internal.Cooldowns;
import io.github.izakyl.folkways.core.plan.internal.Crew;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Situation;
import io.github.izakyl.folkways.core.plan.internal.Stock;
import io.github.izakyl.folkways.core.transport.api.BatchRequest;
import io.github.izakyl.folkways.core.transport.api.QuoteResult;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.transport.api.TransportQuery;
import io.github.izakyl.folkways.core.transport.api.TransportSnapshot;
import io.github.izakyl.folkways.core.transport.api.TransportTravel;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.plugins.combat.weapon.ResidentMounts;
import io.github.izakyl.folkways.plugins.pasture.PastureContent;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCartsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LineGameTests {

    private static final ItemSpec STONE = ItemSpec.exact(new ItemStack(Items.COBBLESTONE));

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LineGameTests.class);
    }

    @GameTest(template = "empty")
    public static void openLinesRunOutAndBackAndLoopsRoundWithStopsReckoned(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 24)) {
            Line open = rig.line(false);
            helper.assertTrue(open.lap(0).getFirst().station() == 0 && open.lap(0).getLast().station() == 1
                    && open.lap(1).getFirst().station() < 0 && open.lap(1).getLast().station() == 0,
                "an open line runs out, then back without standing twice at the far end");
            long walk = (long) Math.ceil(24 / io.github.izakyl.folkways.core.resident.api.Gait.BLOCKS_PER_TICK);
            helper.assertTrue(open.ride(0, 1) == walk && open.ride(1, 0) == walk && open.ride(0, 0) < 0,
                "a ride is reckoned along the line both ways from an open line's ends");
            helper.assertTrue(open.cycle() == 2 * (walk + 2L * Line.DWELL_TICKS),
                "a round of an open line is out and back, with every station stood at");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLineSetsDownAHeldCartOfItsKindAtItsFirstStation(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 24)) {
            Line line = rig.line(false);
            LineState state = rig.lines.track(line);
            Node place = new LineWork.Place(rig.lines, line, line.station(0).orElseThrow());
            helper.assertTrue(place.spec().needs().getFirst().spec().anyOf().size() > 1,
                "a cart of any wood will do");
            Outcome done = place.commit(helper.getLevel(), rig.worker(List.of(new ItemStack(
                BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("astikorcartsredux",
                    "spruce_hand_cart"))))));
            helper.assertTrue(done instanceof Outcome.Done && state.cart.isPresent(), "the cart is set down: " + done);
            AbstractCargoCart cart = state.cart(helper.getLevel()).orElseThrow();
            helper.assertTrue(Line.Kind.HAND.is(cart) && rig.colony.claimOf(cart.getUUID())
                    .filter(held -> held.owner().equals(FolkwaysCartsMod.OWNER)).isPresent()
                    && cart.position().distanceTo(rig.station(0).cell().getBottomCenter()) < 1,
                "a hand cart of the colony's stands at the first station");
            cart.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void goodsGoAboardAndComeOffOnlyWhereTheCartStands(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 24)) {
            Line line = rig.line(false);
            LineState state = rig.lines.track(line);
            AbstractCargoCart cart = rig.cart(state, 0);
            LineState.Consignment sent = new LineState.Consignment(UUID.randomUUID(), STONE, 80, 0, 1);
            state.aboard.put(sent.id, sent);
            var load = new LineWork.Load(state, line, line.station(0).orElseThrow(), sent);
            var unload = new LineWork.Unload(state, line, line.station(1).orElseThrow(), sent);
            helper.assertTrue(load.ready(helper.getLevel()) && !unload.ready(helper.getLevel()),
                "loading waits for the cart at its own station only");
            helper.assertTrue(load.commit(helper.getLevel(), rig.worker(List.of(new ItemStack(Items.COBBLESTONE, 64),
                    new ItemStack(Items.COBBLESTONE, 16)))) instanceof Outcome.Done
                    && sent.loaded && Goods.countIn(cart, STONE) == 80, "the batch goes aboard whole");
            helper.assertTrue(unload.ready(helper.getLevel())
                    && unload.failing(helper.getLevel(), CartRefusal.NOT_AT_STATION) instanceof Recourse.Wait,
                "the handler can head for B while the loaded batch travels and wait without cancelling it");
            helper.assertTrue(unload.commit(helper.getLevel(), rig.worker(List.of())) instanceof Outcome.Failed,
                "nothing comes off before the cart reaches the far station");
            cart.moveTo(rig.station(1).cell().getBottomCenter());
            cart.setOldPosAndRot();
            helper.assertTrue(unload.ready(helper.getLevel())
                    && unload.commit(helper.getLevel(), rig.worker(List.of())) instanceof Outcome.Done done
                    && done.made().stream().mapToInt(ItemStack::getCount).sum() == 80
                    && done.made().stream().allMatch(stack -> stack.getCount() <= stack.getMaxStackSize())
                    && Goods.countIn(cart, STONE) == 0 && !state.aboard.containsKey(sent.id),
                "at the far station the batch comes off whole, in stacks a pack can hold");
            cart.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aRollingCartIsNotParkedEvenWithZeroHorizontalVelocity(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 24)) {
            var level = helper.getLevel();
            Line line = rig.line(false);
            LineState state = rig.lines.track(line);
            AbstractCargoCart cart = rig.cart(state, 0, Line.Kind.SUPPLY);
            var horse = net.minecraft.world.entity.EntityType.HORSE.create(level);
            horse.moveTo(rig.station(0).cell().getBottomCenter().add(3, 0, 0));
            horse.setTamed(true);
            horse.setNoAi(true);
            level.addFreshEntity(horse);
            cart.setPulling(horse);
            cart.setOldPosAndRot();
            cart.pulledTick();
            helper.assertTrue(cart.getDeltaMovement().horizontalDistanceSqr() == 0 && LineState.moving(cart)
                    && !state.parkedAt(level, line, 0),
                "native pulledTick rolls the cart without horizontal velocity; loading must wait for a stop: cart="
                    + cart.position() + " old=" + cart.xo + "," + cart.zo + " velocity=" + cart.getDeltaMovement()
                    + " puller=" + cart.getPulling() + " parked=" + state.parkedAt(level, line, 0));
            cart.setOldPosAndRot();
            helper.assertTrue(state.parkedAt(level, line, 0), "a stationary cart beside the station can be loaded");
            cart.setPulling(null);
            cart.discard();
            horse.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void goodsNoBatchAccountsForAreSetDownAtTheNextStation(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 24)) {
            Line line = rig.line(false);
            LineState state = rig.lines.track(line);
            AbstractCargoCart cart = rig.cart(state, 1);
            cart.setItem(0, new ItemStack(Items.COBBLESTONE, 30));
            LineState.Consignment kept = new LineState.Consignment(UUID.randomUUID(), STONE, 20, 0, 1);
            kept.loaded = true;
            state.aboard.put(kept.id, kept);
            helper.assertTrue(state.stray(cart).stream().mapToInt(ItemStack::getCount).sum() == 10,
                "only what no loaded batch accounts for is stray");
            var clear = new LineWork.Clear(UUID.randomUUID(), helper.getLevel(), state, line, cart);
            helper.assertTrue(clear.ready(helper.getLevel())
                    && clear.commit(helper.getLevel(), rig.worker(List.of())) instanceof Outcome.Done done
                    && done.made().stream().mapToInt(ItemStack::getCount).sum() == 10
                    && Goods.countIn(cart, STONE) == 20, "the stray goods are set down, the booked ones stay");
            cart.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theLineIsQuotedForWaitingRidingAndHandlingAndBookedOnLaying(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 24)) {
            Line line = rig.line(false);
            LineState state = rig.lines.track(line);
            AbstractCargoCart cart = rig.cart(state, 0);
            CartTransport mode = new CartTransport(rig.lines, List.of(new CartTransport.Running(line,
                cart.getUUID(), 2)));
            var snapshot = new TransportSnapshot(1, 0, TransportTravel.UNKNOWN, List.of());
            var batch = new BatchRequest(STONE, 200, 0);
            var links = mode.links(new TransportQuery(rig.endpoint(), rig.endpoint(), batch, List.of()), snapshot);
            helper.assertTrue(links.size() == 2, "each station sends to the other: " + links.size());
            var quote = mode.quote(links.getFirst(), batch, snapshot);
            helper.assertTrue(quote instanceof QuoteResult.Available available
                    && available.quote().covered() == 128
                    && available.quote().cost() == 2L * io.github.izakyl.folkways.FolkwaysConfig.haulReachTicks()
                        + io.github.izakyl.folkways.core.transport.api.TransportQuote.delay(
                            line.cycle() / 2 + line.ride(0, 1))
                    && available.quote().handedAt() == 2L * io.github.izakyl.folkways.FolkwaysConfig.haulReachTicks()
                        + line.cycle() / 2 + line.ride(0, 1),
                "a batch beyond the free cells is covered in part; only handling is work, waiting and riding delay");
            cart.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aLongHaulGoesByTheLineThatRunsAnyway(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 120)) {
            Line line = rig.line(false);
            LineState state = rig.lines.track(line);
            AbstractCargoCart cart = rig.cart(state, 0);
            var post = new TransportPublication(1, List.of(new TransportPublication.Entry(CartTransport.ID,
                helper.getLevel().dimension(), new TransportPublication.Offering(new CartTransport(rig.lines,
                    List.of(new CartTransport.Running(line, cart.getUUID(), 27))),
                    new TransportSnapshot(1, 0, TransportTravel.UNKNOWN, List.of()), true))));
            WorldPos from = rig.fixture.source;
            WorldPos to = rig.station(1).at(rig.station(1).cell().offset(0, 0, -2));
            List<Stock.Holding> held = new ArrayList<>();
            for (int at = 0; at < 20; at++) {
                held.add(new Stock.Holding(new Stash(from), new ItemStack(Items.COBBLESTONE, 64)));
            }
            Stock stock = new Stock(held, Map.of(new Stash(to), 27, new Stash(from), 0));
            Stands stands = Stands.building().container(new Stash(from), Set.of(new Stand(from)))
                .container(new Stash(to), Set.of(new Stand(to))).done();
            Crew.Hand near = rig.fixture.crew.hands().getFirst();
            Crew.Hand far = new PlanningFixture(helper).crew.hands().getFirst();
            Crew crew = new Crew(List.of(near, new Crew.Hand(far.who(), to, 6, 6, 1, far.licences(), List.of(),
                Optional.empty())));
            var owed = new PlanningFixture.Owed(new WorkSite.AtBlock(to), Stances.at(to), STONE, 1280);
            PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
            try {
                var solved = solver.solve(new Situation(rig.fixture.ways, stock, List.of(), stands, () -> post, 0),
                    crew, owed.goals(), List.of(), 0);
                long loads = solved.weave().vertices().values().stream()
                    .filter(vertex -> vertex.node() instanceof LineWork.Load).count();
                helper.assertTrue(loads >= 1 && solved.weave().vertices().values().stream()
                        .anyMatch(vertex -> vertex.node() instanceof LineWork.Unload),
                    "a long way the cart runs anyway beats carrying each pack the whole way");
                helper.assertTrue(!state.aboard.isEmpty(), "the batch is booked on the line");
            } finally {
                solver.closed();
            }
            cart.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aHorseTakenForALineIsNoLongerPastureStock(GameTestHelper helper) {
        var horse = net.minecraft.world.entity.EntityType.HORSE.create(helper.getLevel());
        helper.assertTrue(horse != null, "a horse");
        horse.addTag(PastureContent.CLAIMED);
        helper.assertTrue(horse.getTags().contains(PastureContent.CLAIMED),
            "the tag that keeps it out of the pen's count is on the horse and saved with it");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSupplyLineClaimsItsOwnNavigationAndReleasesItOnErasure(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 24)) {
            var level = helper.getLevel();
            var front = ColonyFront.of(rig.colony);
            front.addZone(level, PastureContent.PASTURE, Set.of(rig.station(0).cell().below()),
                ColonySettings.empty().with(PastureContent.ANIMAL.key(),
                    new ColonySettings.Value.Choice(ResourceLocation.withDefaultNamespace("horse")))).orElseThrow();
            var horse = net.minecraft.world.entity.EntityType.HORSE.create(level);
            horse.moveTo(rig.station(0).cell().getBottomCenter());
            horse.setTamed(true);
            level.addFreshEntity(horse);
            var path = front.addPath(level, FolkwaysCartsMod.LINE,
                List.of(rig.station(0).cell(), rig.station(1).cell()),
                ColonySettings.empty().with(FolkwaysCartsMod.CART.key(),
                    new ColonySettings.Value.Choice(Line.Kind.SUPPLY.entity()))
                    .with(FolkwaysCartsMod.ROUND.key(), new ColonySettings.Value.Choice(FolkwaysCartsMod.OPEN)))
                .orElseThrow();
            rig.lines.refresh(level.getServer());
            LineState state = rig.lines.state(path.id()).orElseThrow();
            var line = Line.of(path, level).orElseThrow();
            var station = line.station(0).orElseThrow();
            helper.assertTrue(station.stances().cells().size() > 1
                    && !station.stances().cells().contains(station.at()),
                "handlers work beside the parked cart");
            helper.assertTrue(state.horse.filter(horse.getUUID()::equals).isPresent() && !horse.isNoAi()
                    && horse.getNavigation() instanceof HorseCartNavigation && state.team != null,
                "claiming a pasture horse fits the line's own movement goal");
            var cart = rig.cart(state, 0, Line.Kind.SUPPLY);
            helper.assertTrue(rig.lines.goals(rig.colony.views(level.getServer()).iterator().next()).isEmpty(),
                "a supply line asks for no resident to hitch, lead, yoke or drive");
            helper.assertTrue(!horse.isLeashed() && horse.getLeashHolder() == null,
                "the line never attaches a resident leader");
            front.erase(path.id());
            rig.lines.refresh(level.getServer());
            helper.assertTrue(!horse.isNoAi() && !horse.getTags().contains(PastureContent.CLAIMED)
                    && rig.colony.claimOf(horse.getUUID()).isEmpty(),
                "erasing the line restores the horse's navigation, AI and pasture availability");
            cart.discard();
            horse.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void supplyLineCannotTakeAProfilesHorseAndCanTakeItAfterRelease(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 24)) {
            var body = (ResidentEntity) rig.fixture.worker.body();
            body.moveTo(rig.station(0).cell().getBottomCenter());
            helper.getLevel().addFreshEntity(body);
            rig.colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
            body.joinColony(rig.colony.id());
            var front = ColonyFront.of(rig.colony);
            front.addZone(helper.getLevel(), PastureContent.PASTURE,
                Set.of(rig.station(0).cell().below()),
                ColonySettings.empty()
                    .with(PastureContent.ANIMAL.key(),
                        new ColonySettings.Value.Choice(
                            ResourceLocation.withDefaultNamespace("horse")))).orElseThrow();
            var horse = net.minecraft.world.entity.EntityType.HORSE.create(helper.getLevel());
            horse.moveTo(rig.station(0).cell().getBottomCenter());
            horse.setTamed(true);
            helper.getLevel().addFreshEntity(horse);
            var profiles = LoadoutProfiles.of(rig.colony);
            var created = profiles.create("Rider");
            profiles.replace(new LoadoutProfile(created.id(),
                created.name(), List.of(LoadoutProfile.Entry.mount())));
            profiles.assign(List.of(body.id()), Optional.of(created.id()));
            helper.assertTrue(rig.colony.claimOf(horse.getUUID()).orElseThrow().owner()
                .equals(ResidentMounts.ID),
                "the profile owns the pasture horse first");
            var path = front.addPath(helper.getLevel(), FolkwaysCartsMod.LINE,
                List.of(rig.station(0).cell(), rig.station(1).cell()),
                ColonySettings.empty()
                    .with(FolkwaysCartsMod.CART.key(),
                        new ColonySettings.Value.Choice(
                            Line.Kind.SUPPLY.entity()))
                    .with(FolkwaysCartsMod.ROUND.key(), new ColonySettings.Value.Choice(FolkwaysCartsMod.OPEN)))
                .orElseThrow();
            rig.lines.refresh(helper.getLevel().getServer());
            helper.assertTrue(rig.lines.state(path.id()).orElseThrow().horse.isEmpty(),
                "a real supply line cannot take the rider's horse");
            profiles.assign(List.of(body.id()), Optional.empty());
            rig.lines.refresh(helper.getLevel().getServer());
            helper.assertTrue(rig.lines.state(path.id()).orElseThrow().horse.filter(horse.getUUID()::equals).isPresent(),
                "the supply line can claim it once the profile releases it");
            horse.discard();
            body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bookingsCanChangeOnThePlannerWhileTheServerReadsThem(GameTestHelper helper) throws Exception {
        try (Rig rig = new Rig(helper, 24)) {
            Line line = rig.line(false);
            LineState state = rig.lines.track(line);
            AbstractCargoCart cart = rig.cart(state, 0);
            try {
                var sent = new LineState.Consignment(UUID.randomUUID(), STONE, 64, 0, 1);
                var other = new LineState.Consignment(UUID.randomUUID(), STONE, 64, 0, 1);
                Hold booked = booking(state, sent);
                booked.take();
                booking(state, other).take();
                var reading = state.aboard.values().iterator();
                reading.next();
                var replacement = new LineState.Consignment(sent.id, STONE, 64, 0, 1);
                Thread planner = new Thread(() -> {
                    booked.release(Ending.DROPPED);
                    booking(state, replacement).take();
                }, "cart-booking-test");
                planner.start();
                planner.join();
                while (reading.hasNext()) {
                    reading.next();
                }
                booked.release(Ending.DROPPED);
                helper.assertTrue(state.aboard.get(sent.id) == replacement && state.freeCells(cart)
                        == cart.getContainerSize() - 2 && state.stray(cart).isEmpty(),
                    "server reads tolerate planner bookings and an old release keeps the replacement");
                var load = new LineWork.Load(state, line, line.station(0).orElseThrow(), sent);
                helper.assertTrue(!load.ready(helper.getLevel())
                        && load.commit(helper.getLevel(), rig.worker(List.of(new ItemStack(Items.COBBLESTONE, 64))))
                            instanceof Outcome.Failed && cart.isEmpty(),
                    "a stale load cannot use a replacement booking with the same id");
            } finally {
                cart.discard();
            }
        }
        helper.succeed();
    }

    private static Hold booking(LineState state, LineState.Consignment sent) {
        try {
            var type = Class.forName(CartTransport.class.getName() + "$Booked");
            var constructor = type.getDeclaredConstructor(LineState.class, LineState.Consignment.class);
            constructor.setAccessible(true);
            return (Hold) constructor.newInstance(state, sent);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    @GameTest(template = "empty")
    public static void erasingANetherLineReleasesTheHorseInItsOwnDimension(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, 24)) {
            var nether = helper.getLevel().getServer().getLevel(Level.NETHER);
            var horse = rig.supplyHorse(rig.lines, nether);
            try {
                helper.assertTrue(nether.getEntity(horse.getUUID()) == horse,
                    "the line's horse is loaded in the Nether");
                rig.lines.refresh(nether.getServer());
                helper.assertTrue(!horse.getTags().contains(PastureContent.CLAIMED)
                        && rig.colony.claimOf(horse.getUUID()).isEmpty()
                        && !(horse.getNavigation() instanceof HorseCartNavigation),
                    "removing a line searches every dimension before forgetting the horse");
            } finally {
                horse.discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void colonyClosureReleasesSupplyHorsesExceptAtShutdown(GameTestHelper helper) throws Exception {
        for (Closing why : List.of(Closing.SHUTDOWN, Closing.RAZED, Closing.GONE)) {
            try (Rig rig = new Rig(helper, 24)) {
                var lines = rig.colony.service(FolkwaysCartsMod.OWNER, CartLines.class).orElseThrow();
                var horse = rig.supplyHorse(lines, helper.getLevel());
                try {
                    if (why == Closing.RAZED) {
                        rig.colony.raze();
                    } else {
                        // Close through the same colony scope that receives the server's closing reason.
                        var close = rig.colony.getClass().getSuperclass().getDeclaredMethod("closed", Closing.class);
                        close.setAccessible(true);
                        close.invoke(rig.colony, why);
                    }
                    helper.assertTrue(!(horse.getNavigation() instanceof HorseCartNavigation),
                        "closing restores the team's navigation");
                    helper.assertTrue(horse.getTags().contains(PastureContent.CLAIMED) == (why == Closing.SHUTDOWN)
                            && rig.colony.claimOf(horse.getUUID()).isPresent() == (why == Closing.SHUTDOWN),
                        "only shutdown keeps the supply horse's ownership: " + why);
                } finally {
                    horse.discard();
                }
            }
        }
        helper.succeed();
    }

    private static final class Rig implements AutoCloseable {
        private final GameTestHelper helper;
        private final PlanningFixture fixture;
        private final Colony colony;
        private final CartLines lines;
        private final int length;

        private Rig(GameTestHelper helper, int length) {
            this.helper = helper;
            this.length = length;
            fixture = new PlanningFixture(helper, 6);
            colony = Colonies.mint(helper.getLevel().getServer());
            lines = new CartLines(colony);
            for (int x = -2; x <= length + 2; x++) {
                for (int z = -3; z <= 3; z++) {
                    helper.getLevel().setBlockAndUpdate(fixture.source.cell().offset(x, -1, z),
                        Blocks.STONE.defaultBlockState());
                }
            }
        }

        private AbstractHorse supplyHorse(CartLines lines, ServerLevel level) {
            var cell = station(0).cell();
            WorldPos first = WorldPos.of(level, level == helper.getLevel() ? cell
                : new net.minecraft.core.BlockPos(cell.getX() / 8, 128, cell.getZ() / 8));
            WorldPos last = first.at(first.cell().offset(length, 0, 0));
            Line line = new Line(UUID.randomUUID(), level.dimension(), Line.Kind.SUPPLY, false,
                List.of(new Line.Mark(first, Stances.at(first), 0), new Line.Mark(last, Stances.at(last), 1)), 2);
            LineState state = lines.track(line);
            level.getChunkAt(first.cell());
            // The other level has no players to make the horse visible to entity lookup.
            var horse = new Horse(EntityType.HORSE, level) {
                @Override
                public boolean isAlwaysTicking() {
                    return true;
                }
            };
            horse.moveTo(first.cell().getBottomCenter());
            horse.setTamed(true);
            helper.assertTrue(level.addFreshEntity(horse), "the supply horse is admitted to its level");
            colony.hold(FolkwaysCartsMod.OWNER, Claimed.Entity.of(horse)).orElseThrow();
            horse.addTag(PastureContent.CLAIMED);
            state.horse = Optional.of(horse.getUUID());
            state.team = new HorseLine(level, line, state, horse);
            return horse;
        }

        private WorldPos station(int which) {
            return fixture.source.at(fixture.source.cell().offset(which == 0 ? 0 : length, 0, 2));
        }

        private Line line(boolean loop) {
            WorldPos middle = fixture.source.at(fixture.source.cell().offset(length / 2, 0, 2));
            return new Line(UUID.randomUUID(), helper.getLevel().dimension(), Line.Kind.HAND, loop, List.of(
                new Line.Mark(station(0), Stances.at(station(0)), 0),
                new Line.Mark(middle, Stances.at(middle), -1),
                new Line.Mark(station(1), Stances.at(station(1)), 1)), 2);
        }

        private AbstractCargoCart cart(LineState state, int at) {
            return cart(state, at, Line.Kind.HAND);
        }

        private AbstractCargoCart cart(LineState state, int at, Line.Kind kind) {
            var cart = (AbstractCargoCart) BuiltInRegistries.ENTITY_TYPE.get(kind.entity())
                .create(helper.getLevel());
            cart.moveTo(station(at).cell().getBottomCenter());
            helper.getLevel().addFreshEntity(cart);
            colony.hold(FolkwaysCartsMod.OWNER, Claimed.Entity.of(cart)).orElseThrow();
            state.cart = Optional.of(cart.getUUID());
            return cart;
        }

        private io.github.izakyl.folkways.core.transport.api.TransportEndpoint endpoint() {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("folkways", "test_end");
            return new io.github.izakyl.folkways.core.transport.api.TransportEndpoint(id, fixture.source,
                Set.of(new Stand(fixture.source)), new io.github.izakyl.folkways.core.transport.api.TransportEndpoint
                    .Handover(io.github.izakyl.folkways.core.transport.api.TransportEndpoint.Form.INVENTORY, id));
        }

        private Worker worker(List<ItemStack> supplied) {
            Worker actual = fixture.worker;
            return new Worker() {
                public io.github.izakyl.folkways.core.resident.api.Resident resident() { return actual.resident(); }
                public Mob body() { return actual.body(); }
                public net.minecraft.world.Container pack() { return actual.pack(); }
                public List<Store> within() { return List.of(); }
                public ItemStack held(io.github.izakyl.folkways.core.work.api.ToolNeed need) {
                    return ItemStack.EMPTY;
                }
                public void spill(ItemStack stack) {
                    throw new AssertionError("unexpected spill: " + stack);
                }
                public io.github.izakyl.folkways.effect.Effects effects() {
                    return io.github.izakyl.folkways.effect.Effects.NONE;
                }
                public List<Node> route() { return List.of(); }
                public io.github.izakyl.folkways.core.work.api.Placement placement() {
                    return io.github.izakyl.folkways.core.work.api.Placement.NOWHERE;
                }
                public List<ItemStack> supplied() { return supplied; }
            };
        }

        @Override
        public void close() {
            colony.raze();
        }
    }
}
