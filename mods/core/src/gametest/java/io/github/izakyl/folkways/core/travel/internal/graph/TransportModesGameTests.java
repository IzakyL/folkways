package io.github.izakyl.folkways.core.travel.internal.graph;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.internal.DirectTransport;
import io.github.izakyl.folkways.core.plan.internal.TransportEstimate;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.transport.api.BatchRequest;
import io.github.izakyl.folkways.core.transport.api.LayResult;
import io.github.izakyl.folkways.core.transport.api.QuoteResult;
import io.github.izakyl.folkways.core.transport.api.TransportEndpoint;
import io.github.izakyl.folkways.core.transport.api.TransportLaying;
import io.github.izakyl.folkways.core.transport.api.TransportLink;
import io.github.izakyl.folkways.core.transport.api.TransportMode;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.transport.api.TransportQuery;
import io.github.izakyl.folkways.core.transport.api.TransportQuote;
import io.github.izakyl.folkways.core.transport.api.TransportSnapshot;
import io.github.izakyl.folkways.core.transport.api.TransportTravel;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.travel.api.Passage;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.Closures;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TransportModesGameTests {

    private static final ResourceLocation MODE = id("test_transport");
    private static final ResourceLocation KIND = id("resident");
    private static final ResourceLocation SERVICE = id("test_transport_passage");
    private static final ItemSpec GOODS = ItemSpec.of(Items.COBBLESTONE);
    private static final BatchRequest BATCH = new BatchRequest(GOODS, 64, 100);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TransportModesGameTests.class);
    }

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.passage(new Passage() {
            public ResourceLocation id() {
                return SERVICE;
            }

            public List<Hop> hopsIn(Colony colony) {
                return List.of();
            }

            public Conveyance aboard(Hop hop) {
                throw new AssertionError("quotation must not board a passage");
            }
        });
        Participations.register(KIND, Stake.passage(SERVICE), Participation.ALWAYS);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void cheapTrunkMustPayForTheWholeRoute(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.edge("pq", "p", "q", 1).carry("a", "p", 40).carry("q", "b", 40);
        f.edge("rs", "r", "s", 10).carry("a", "r", 2).carry("s", "b", 2);
        f.carry("a", "b", 30);
        var route = check(helper, f.estimate(), 14, List.of("carry_a_r", "rs", "carry_s_b"));
        helper.assertTrue(route.legs().get(1).quote().batch().readyAt() == BATCH.readyAt() + 2
                && route.legs().get(2).quote().batch().readyAt() == BATCH.readyAt() + 12
                && route.handedAt() == BATCH.readyAt() + 14,
            "each leg is quoted for when the goods reach it, so a trunk sees its real departure");
        helper.assertTrue(route.trunk().orElseThrow().quote().link().id().getPath().equals("rs"),
            "the route names its single trunk");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aFartherHubCanSucceedWhenTheNearestIsCutOff(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.edge("pq", "p", "q", -2).carry("a", "p", 1).carry("q", "b", 1);
        f.edge("rs", "r", "s", 3).carry("a", "r", 8).carry("s", "b", 8);
        check(helper, f.estimate(), 19, List.of("carry_a_r", "rs", "carry_s_b"));
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void longDirectHaulStillCompetesWithTrunks(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.edge("pq", "p", "q", 1);
        DirectTransport direct = new DirectTransport();
        Resident who = new Resident(UUID.randomUUID(), KIND);
        TransportSnapshot snapshot = new TransportSnapshot(1, 100,
            (kind, from, to, departure) -> to.equals(f.at("b").at()) && from.equals(f.at("a").at())
                ? new TransportTravel.Yes(20, 0) : from.equals(to) ? new TransportTravel.Yes(0, 0) : TransportTravel.NO,
            List.of(new TransportSnapshot.Carrier(who, f.at("a").at(), Map.of(GOODS, 64L), 0)));
        var offering = new TransportPublication.Offering(direct, snapshot, true);
        var result = TransportEstimate.estimate(f.query(), f.publication(), offering);
        check(helper, result, 20, List.of("direct"));
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void trunksNeverChain(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.edge("ap", "a", "p", 3).edge("pb", "p", "b", 4);
        helper.assertTrue(f.estimate() instanceof TransportEstimate.Unreachable,
            "two trunks are not joined end to end, even within one mode");
        f.carry("p", "b", 5);
        check(helper, f.estimate(), 8, List.of("ap", "carry_p_b"));
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aTrunkFromTheSourceNeedsNoApproachButCustodyIsNotPlace(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.edge("ab", "a", "b", 5).carry("a", "b", 10);
        check(helper, f.estimate(), 5, List.of("ab"));
        Fixture custody = new Fixture(helper);
        TransportEndpoint a = custody.at("a");
        custody.endpoints.put("q", new TransportEndpoint(id("q"), a.at(), a.stands(),
            new TransportEndpoint.Handover(TransportEndpoint.Form.PACK, id("separate_custody"))));
        custody.edge("qb", "q", "b", 1);
        helper.assertTrue(custody.estimate() instanceof TransportEstimate.Unreachable,
            "co-located custody interfaces still need a carry between them");
        custody.carry("a", "q", 2);
        check(helper, custody.estimate(), 3, List.of("carry_a_q", "qb"));
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void laterKeepsUnknownAlternativesPending(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.edge("pq", "p", "q", -1).carry("a", "p", 1).carry("q", "b", 1);
        helper.assertTrue(f.estimate() instanceof TransportEstimate.Pending,
            "Later is neither a free edge nor unreachable");
        f.carry("a", "b", 10);
        helper.assertTrue(f.estimate() instanceof TransportEstimate.Best best && !best.complete()
            && best.route().cost() == 10, "a known route cannot prove itself cheapest over Later");
        Fixture approach = new Fixture(helper);
        approach.edge("pq", "p", "q", 1).carry("a", "p", -1).carry("q", "b", 1).carry("a", "b", 50);
        helper.assertTrue(approach.estimate() instanceof TransportEstimate.Best best && !best.complete()
            && best.route().cost() == 50, "an unknown approach keeps its trunk an open alternative");
        Resident who = new Resident(UUID.randomUUID(), KIND);
        ResourceLocation unknown = id("unknown_transport_route");
        TransportTravel travel = (kind, from, to, departure) -> kind.equals(unknown)
            ? TransportTravel.LATER : new TransportTravel.Yes(from.equals(to) ? 0 : 1, 0);
        TransportSnapshot snapshot = new TransportSnapshot(1, 100, travel, List.of(
            carrier(f, travel, who, 64).carriers().getFirst(),
            carrier(f, travel, new Resident(UUID.randomUUID(), KIND, unknown), 64).carriers().getFirst()));
        DirectTransport direct = new DirectTransport();
        var quote = direct.quote(f.link("direct", "a", "b"), BATCH, snapshot);
        helper.assertTrue(quote instanceof QuoteResult.Available available && !available.complete()
            && available.quote().cost() == 5, "known direct quotes survive unknown eligible carriers");
        Fixture known = new Fixture(helper);
        known.edge("expensive", "a", "b", 20);
        var result = TransportEstimate.estimate(known.query(), known.publication(),
            new TransportPublication.Offering(direct, snapshot, true));
        helper.assertTrue(result instanceof TransportEstimate.Best best && !best.complete()
            && best.route().cost() == 5, "the incomplete direct quote beats the expensive trunk");
        var loading = f.at("a");
        WorldPos unobserved = loading.at().at(loading.at().cell().south(1));
        var stands = new TransportEndpoint(loading.id(), loading.at(),
            Set.of(new Stand(loading.at()), new Stand(unobserved)), loading.handover());
        var link = f.link("direct", "a", "b");
        var mixed = new TransportLink(link.id(), link.version(), stands, link.to(), link.accepts(), link.capacity(),
            link.service(), link.loading(), link.unloading(), link.keptOnArrival());
        TransportTravel mixedStands = (kind, from, to, departure) -> from.equals(unobserved) || to.equals(unobserved)
            ? TransportTravel.LATER : new TransportTravel.Yes(from.equals(to) ? 0 : 1, 0);
        helper.assertTrue(direct.quote(mixed, BATCH, carrier(f, mixedStands, who, 64))
            instanceof QuoteResult.Available available && !available.complete() && available.quote().cost() == 5,
            "known direct quotes also survive unknown stand combinations");
        helper.assertTrue(direct.quote(link, BATCH, carrier(f, TransportTravel.UNKNOWN, who, 64))
            instanceof QuoteResult.Unknown, "without any known combination the quote stays Later");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void partialTrunksAreReportedAndExcludedLinksSkipped(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.edge("pq", "p", "q", 1).carry("a", "p", 1).carry("q", "b", 1).carry("a", "b", 40);
        f.limit = 16;
        List<Long> partial = new ArrayList<>();
        var result = TransportEstimate.estimate(f.query(), f.publication(), f.carries(), Set.of(), partial::add);
        helper.assertTrue(partial.equals(List.of(16L)) && result instanceof TransportEstimate.Best best
            && best.route().cost() == 40, "a trunk taking part of the batch offers a smaller batch, not a route");
        f.limit = Long.MAX_VALUE;
        check(helper, f.estimate(), 3, List.of("carry_a_p", "pq", "carry_q_b"));
        var excluded = Set.of(new TransportEstimate.Connection(MODE, f.links.getFirst()));
        check(helper, TransportEstimate.estimate(f.query(), f.publication(), f.carries(), excluded, count -> { }),
            40, List.of("carry_a_b"));
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void quotingNeverRequestsOrTreadsRoutes(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Ways dangerous = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                throw new AssertionError("quotation called journey");
            }

            public Faring reckon(Resident who, WorldPos from, Set<WorldPos> goals) {
                throw new AssertionError("quotation called reckon");
            }

            public Faring anyoneReaches(Set<WorldPos> goals) {
                throw new AssertionError("quotation called anyoneReaches");
            }
        };
        helper.assertTrue(dangerous.estimates().estimate(KIND, f.at("a").at(), f.at("b").at(), 100)
            instanceof TransportTravel.Later, "the default pure view must preserve unknown without fallback");
        Asked asked = new Asked();
        Trodden trodden = new Trodden();
        Sort sort = new Sort(id("walking"), new Bulk(1, 2));
        Waypoints net = new Waypoints();
        int a = net.admit(f.at("a").at().cell().asLong(), 0);
        int b = net.admit(f.at("b").at().cell().asLong(), 0);
        net.link(a, b, 12, 0);
        WayGraph graph = new WayGraph(Map.of(KIND, sort),
            Map.of(new Tract(sort, f.at("a").at().realm()), net.freeze(1)), List.of(), Map.of(),
            asked, trodden, List.of(), 0);
        var view = graph.estimates();
        for (int i = 0; i < 20; i++) {
            helper.assertTrue(view.estimate(KIND, f.at("a").at(), f.at("b").at(), 100)
                instanceof TransportTravel.Yes yes && yes.ticks() == 12, "pure queries use known routes");
            helper.assertTrue(view.estimate(KIND, f.at("a").at(),
                f.at("a").at().at(new BlockPos(9000, 1, 9000)), 100) instanceof TransportTravel.Later,
                "unknown routes remain Later");
        }
        var snapshot = carrier(f, view, new Resident(UUID.randomUUID(), KIND), 16);
        var result = new DirectTransport().quote(f.link("direct", "a", "b"), BATCH, snapshot);
        helper.assertTrue(result instanceof QuoteResult.Unknown,
            "multiple trips need a return route, whose absence is still unknown");
        helper.assertTrue(asked.drain().isEmpty() && trodden.drain().isEmpty(),
            "quoting known and unknown routes must neither register requests nor record walking");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void pureTravelUsesTheRequestedDepartureAndCountsPassageOnce(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        WorldPos a = f.at("a").at();
        WorldPos b = f.at("b").at();
        Sort sort = new Sort(id("walking"), new Bulk(1, 2));
        Waypoints net = new Waypoints();
        net.pin(a.cell().asLong(), 0);
        net.pin(b.cell().asLong(), 0);
        Hop hop = new Hop(SERVICE, new Stances.Cells(Set.of(a)), new Stances.Cells(Set.of(b)),
            when -> OptionalLong.of((when / 100 + 1) * 100 + 10));
        Asked asked = new Asked();
        WayGraph graph = new WayGraph(Map.of(KIND, sort), Map.of(new Tract(sort, a.realm()), net.freeze(1)),
            List.of(new Crossing(a, b, hop, SERVICE)), Map.of(), asked, new Trodden(), List.of(), 0);
        var view = graph.estimates();
        helper.assertTrue(view.estimate(KIND, a, b, 100) instanceof TransportTravel.Yes early
            && early.walkTicks() == 0 && early.rideTicks() == 110, "waiting and riding are counted once");
        helper.assertTrue(view.estimate(KIND, a, b, 150) instanceof TransportTravel.Yes later
            && later.rideTicks() == 60, "departure times must not reuse a reverse field priced for now");
        helper.assertTrue(asked.drain().isEmpty(), "estimating future departures requests no routes");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void directQuotesIncludeRepeatedTripsAndHandling(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Resident who = new Resident(UUID.randomUUID(), KIND);
        TransportTravel travel = (kind, from, to, departure) -> new TransportTravel.Yes(from.equals(to) ? 0 : 10, 0);
        DirectTransport direct = new DirectTransport();
        TransportLink link = f.link("direct", "a", "b");
        var pack = direct.quote(link, BATCH, carrier(f, travel, who, 16));
        helper.assertTrue(pack instanceof QuoteResult.Available p && p.quote().cost() == 86,
            "four pack trips include handling and three return journeys");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void directQuotesSeparateRouteKindsOfTheSameResidentKind(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        ResourceLocation cart = id("cart_test");
        Resident walker = new Resident(UUID.randomUUID(), KIND);
        Resident driver = new Resident(UUID.randomUUID(), KIND, cart);
        Map<ResourceLocation, Integer> queries = new LinkedHashMap<>();
        TransportTravel travel = (kind, from, to, departure) -> {
            queries.merge(kind, 1, Integer::sum);
            return kind.equals(cart) ? new TransportTravel.Yes(from.equals(to) ? 0 : 3, 0) : TransportTravel.NO;
        };
        TransportSnapshot snapshot = new TransportSnapshot(1, 100, travel, List.of(
            carrier(f, travel, walker, 64).carriers().getFirst(),
            carrier(f, travel, driver, 64).carriers().getFirst()));
        var result = new DirectTransport().quote(f.link("direct", "a", "b"), BATCH, snapshot);
        helper.assertTrue(result instanceof QuoteResult.Available available && available.quote().cost() == 7,
            "the alternate route must not inherit the walking resident kind's denial");
        helper.assertTrue(queries.keySet().equals(Set.of(KIND, cart)), "both route kinds must be queried");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void publicationsReplaceOnlyTheirOwnerAndDimensionAndAdvanceVersions(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        ColonyData colony = new ColonyData();
        var works = colony.works();
        var dimension = helper.getLevel().dimension();
        var offering = new TransportPublication.Offering(f, f.snapshot(), true);
        works.publishTransport(MODE, dimension, List.of(offering));
        var first = works.transports();
        works.publishTransport(MODE, dimension, List.of(offering));
        helper.assertTrue(works.transports() == first && works.transportVersion() == first.version(),
            "republishing equal data keeps every quote made from it");
        works.publishTransport(MODE, dimension, List.of(new TransportPublication.Offering(f,
            new TransportSnapshot(2, 100, TransportTravel.UNKNOWN, List.of()), true)));
        helper.assertTrue(works.transports().version() > first.version(), "a changed snapshot invalidates quotes");
        works.publishTransport(id("another_owner"), dimension, List.of(offering));
        works.publishTransport(MODE, net.minecraft.world.level.Level.NETHER, List.of(offering));
        var before = works.transports();
        works.publishTransport(MODE, dimension, List.of());
        helper.assertTrue(works.transports().version() > before.version() && works.transports().entries().size() == 2,
            "withdrawal only replaces one owner in one dimension");
        helper.assertTrue(first.entries().size() == 1 && works.workshopsIn(dimension).isEmpty(),
            "publication snapshots remain stable and do not enter the workshop list");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void closedEndsMatchPairwiseTravelEstimates(GameTestHelper helper) {
        WorldPos from = WorldPos.of(helper.getLevel(), new BlockPos(0, 1, 0));
        WorldPos to = from.at(new BlockPos(1000, 1, 0));
        Sort sort = new Sort(id("walking"), new Bulk(1, 2));
        Waypoints net = new Waypoints();
        List<WorldPos> points = List.of(from.at(new BlockPos(2, 1, 0)), from.at(new BlockPos(10, 1, 8)),
            from.at(new BlockPos(80, 1, 0)), from.at(new BlockPos(900, 1, 0)),
            from.at(new BlockPos(980, 1, 8)), from.at(new BlockPos(998, 1, 0)));
        for (WorldPos point : points) {
            net.pin(point.cell().asLong(), 0);
        }
        for (int i = 0; i < 2; i++) {
            net.link(i, i + 1, 30, 0);
            net.link(i + 3, i + 4, 30, 0);
        }
        List<Crossing> crossings = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            WorldPos a = points.get(i);
            WorldPos b = points.get(5 - i);
            int ride = 10 + i;
            Hop hop = new Hop(SERVICE, new Stances.Cells(Set.of(a)), new Stances.Cells(Set.of(b)),
                when -> OptionalLong.of((when / 100 + 1) * 100 + ride));
            crossings.add(new Crossing(a, b, hop, SERVICE));
        }
        var nets = Map.of(new Tract(sort, from.realm()), net.freeze(1));
        Asked asked = new Asked();
        Trodden trodden = new Trodden();
        WayGraph pairwise = new WayGraph(Map.of(KIND, sort), nets, crossings, Map.of(), asked, trodden,
            List.of(), 0);
        var closed = List.of(new Closures.Closed(helper.getLevel().dimension(), new BoundingBox(-1, 0, -1, 1, 2, 1)),
            new Closures.Closed(helper.getLevel().dimension(), new BoundingBox(999, 0, -1, 1001, 2, 1)));
        WayGraph graph = new WayGraph(Map.of(KIND, sort), nets, crossings, Map.of(), asked, trodden, closed, 0);
        for (long departure : List.of(0L, 90L, 150L)) {
            long least = Waypoints.Net.ticks(from.cell(), to.cell());
            long riding = 0;
            for (WorldPos start : points) {
                int approach = Waypoints.Net.ticks(from.cell(), start.cell());
                for (WorldPos end : points) {
                    var result = pairwise.estimates().estimate(KIND, start, end, departure + approach);
                    if (result instanceof TransportTravel.Yes arrival) {
                        long ticks = approach + arrival.ticks() + Waypoints.Net.ticks(end.cell(), to.cell());
                        if (ticks < least) {
                            least = ticks;
                            riding = arrival.rideTicks();
                        }
                    }
                }
            }
            helper.assertTrue(graph.estimates().estimate(KIND, from, to, departure)
                    .equals(new TransportTravel.Yes(least - riding, riding)),
                "all end arrivals include the last walk and preserve pairwise costs at " + departure);
            helper.assertTrue(riding > 0, "the scene chooses a passage over the native walk");
        }
        helper.assertTrue(asked.drain().isEmpty() && trodden.drain().isEmpty(),
            "estimating closed ends neither requests routes nor records walking");
        helper.succeed();
    }

    private static TransportSnapshot carrier(Fixture f, TransportTravel travel, Resident who, long pack) {
        return new TransportSnapshot(1, 100, travel, List.of(DirectTransport.carrier(who, f.at("a").at(),
            GOODS, pack, 2)));
    }

    private static TransportEstimate.Route check(GameTestHelper helper, TransportEstimate.Result result, long cost,
                                                 List<String> lines) {
        helper.assertTrue(result instanceof TransportEstimate.Best best && best.complete(),
            "expected a complete estimate, got " + result);
        var route = ((TransportEstimate.Best) result).route();
        helper.assertTrue(route.cost() == cost && route.legs().stream().map(leg -> leg.quote().link().id().getPath())
            .toList().equals(lines), "compare complete route costs and keep the selected concrete lines: " + route);
        return route;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways_core", path);
    }

    private static final class Fixture implements TransportMode {

        private final GameTestHelper helper;
        private final Map<String, TransportEndpoint> endpoints = new LinkedHashMap<>();
        private final List<TransportLink> links = new ArrayList<>();
        private final Map<TransportLink, Long> costs = new LinkedHashMap<>();
        private final Map<List<TransportEndpoint>, TransportLink> carries = new LinkedHashMap<>();
        private long limit = Long.MAX_VALUE;

        private Fixture(GameTestHelper helper) {
            this.helper = helper;
            at("a");
            at("b");
        }

        private TransportEndpoint at(String name) {
            return endpoints.computeIfAbsent(name, key -> {
                WorldPos pos = WorldPos.of(helper.getLevel(), new BlockPos(endpoints.size() * 32, 1, 0));
                return new TransportEndpoint(TransportModesGameTests.id(key), pos, Set.of(new Stand(pos)),
                    new TransportEndpoint.Handover(TransportEndpoint.Form.PACK, TransportModesGameTests.id(key)));
            });
        }

        private TransportLink link(String name, String from, String to) {
            return new TransportLink(TransportModesGameTests.id(name), 1, at(from), at(to), List.of(GOODS),
                new TransportLink.Capacity(TransportModesGameTests.id("items"), 64, 1024, 1, false),
                new TransportLink.Service(MODE, 1, 0, Long.MAX_VALUE), true, true, true);
        }

        private Fixture edge(String name, String from, String to, long cost) {
            TransportLink link = link(name, from, to);
            links.add(link);
            costs.put(link, cost);
            return this;
        }

        private TransportSnapshot snapshot() {
            return new TransportSnapshot(1, 100, TransportTravel.UNKNOWN, List.of());
        }

        private TransportQuery query() {
            return new TransportQuery(at("a"), at("b"), BATCH, List.copyOf(endpoints.values()));
        }

        private TransportPublication publication() {
            return new TransportPublication(7, List.of(new TransportPublication.Entry(MODE,
                helper.getLevel().dimension(), new TransportPublication.Offering(this, snapshot(), true))));
        }

        private Fixture carry(String from, String to, long cost) {
            TransportLink link = link("carry_" + from + "_" + to, from, to);
            carries.put(List.of(at(from), at(to)), link);
            costs.put(link, cost);
            return this;
        }

        // The direct way of this fixture: only the carries it was given, at their given costs.
        private TransportPublication.Offering carries() {
            TransportMode direct = new TransportMode() {
                public ResourceLocation id() {
                    return DirectTransport.ID;
                }

                public List<TransportLink> links(TransportQuery query, TransportSnapshot snapshot) {
                    TransportLink link = carries.get(List.of(query.from(), query.to()));
                    return link == null ? List.of() : List.of(link);
                }

                public QuoteResult quote(TransportLink link, BatchRequest batch, TransportSnapshot snapshot) {
                    return priced(DirectTransport.ID, link, batch, snapshot, Long.MAX_VALUE);
                }

                public LayResult lay(TransportQuote quote, TransportLaying plan) {
                    throw new AssertionError("estimating must never expand or reserve work");
                }
            };
            return new TransportPublication.Offering(direct, snapshot(), true);
        }

        private TransportEstimate.Result estimate() {
            return TransportEstimate.estimate(query(), publication(), carries());
        }

        private QuoteResult priced(ResourceLocation mode, TransportLink link, BatchRequest batch,
                                   TransportSnapshot snapshot, long most) {
            long cost = costs.get(link);
            if (cost == -1) {
                return QuoteResult.LATER;
            }
            if (cost < 0) {
                return QuoteResult.NO;
            }
            long count = Math.min(most, batch.count());
            return new QuoteResult.Available(new TransportQuote(mode, link, batch, count, cost,
                batch.readyAt() + cost, List.of(new TransportQuote.Load(count, 1,
                    batch.readyAt(), batch.readyAt() + cost)), List.of(), Map.of(mode, snapshot.version()),
                Optional.empty()), true);
        }

        @Override
        public ResourceLocation id() {
            return MODE;
        }

        @Override
        public List<TransportLink> links(TransportQuery query, TransportSnapshot snapshot) {
            return List.copyOf(links);
        }

        @Override
        public QuoteResult quote(TransportLink link, BatchRequest batch, TransportSnapshot snapshot) {
            return priced(MODE, link, batch, snapshot, limit);
        }

        @Override
        public LayResult lay(TransportQuote quote, TransportLaying plan) {
            throw new AssertionError("estimating must never expand or reserve work");
        }
    }
}
