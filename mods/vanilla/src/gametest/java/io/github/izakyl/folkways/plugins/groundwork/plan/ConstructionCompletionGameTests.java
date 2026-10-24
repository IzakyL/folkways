package io.github.izakyl.folkways.plugins.groundwork.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Action;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Box;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Capability;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Cell;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Finish;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Place;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Problem;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.State;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Summary;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Voxel;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Walk;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Witness;
import io.github.izakyl.folkways.plugins.groundwork.plan.ConstructionReference.ExpansionBudget;
import io.github.izakyl.folkways.plugins.groundwork.plan.ConstructionReference.Kind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Pure finite-model cross-checks. These do not claim native navigation or crash-atomic inventory. */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConstructionCompletionGameTests {
    private ConstructionCompletionGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ConstructionCompletionGameTests.class);
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void independentFiniteCasesRequireAWholeConstructionAndRetreat(GameTestHelper helper) {
        for (var named : ConstructionReferenceCases.positive()) {
            Problem problem = named.problem();
            Witness reference = solved(helper, problem);
            helper.assertTrue(ConstructionReference.validate(problem, reference).valid(), named.name() + " independent complete replay");
            verifiedByProduction(helper, problem, reference, true);
            CompletionSearch search = new CompletionSearch(problem);
            for (int calls = 0; calls < 1000 && search.status() == CompletionSearch.Status.SEARCHING; calls++) {
                search.advance(new ExpansionBudget(8192));
            }
            helper.assertTrue(search.status() == CompletionSearch.Status.READY,
                named.name() + " production must find the known small solution: " + search.reason());
            var checked = ConstructionReference.validate(problem, search.witness().orElseThrow());
            helper.assertTrue(checked.valid(), named.name() + " production witness fails independent replay: " + checked.reason());
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void anEscapableFirstPlacementCanStillDestroyTheRemainingConstruction(GameTestHelper helper) {
        Problem problem = ConstructionReferenceCases.singleMouth();
        Cell mouth = problem.orderedTargets().getFirst();
        Cell deep = problem.orderedTargets().getLast();
        Cell outside = problem.start();
        helper.assertTrue(ConstructionReference.placeGeometry(problem, problem.initialState(), mouth),
            "the tempting first placement is locally legal from outside");
        State after = new State(Set.of(mouth), outside, 1);
        helper.assertTrue(!ConstructionReference.route(problem, after, Set.of(outside), problem.safeCells()).isEmpty(),
            "the worker can immediately escape after prematurely closing the mouth");
        Witness incomplete = witness(problem, List.of(new Place(mouth, outside, List.of(outside), List.of(outside)),
            new Finish(outside)), after, 1);
        rejectedByBoth(helper, problem, incomplete, "an escape is not a certificate for the remaining deep target");
        var impossible = ConstructionReference.solve(ConstructionReferenceCases.afterPrematureMouthClosure(), 100000);
        helper.assertTrue(impossible.kind() == Kind.UNSAT_WITHIN_MODEL,
            "the entire known finite graph after that choice really has no completion");
        List<Cell> order = placements(solved(helper, problem)).stream().map(Place::targetCell).toList();
        helper.assertTrue(order.equals(List.of(deep, mouth)), "the valid full solution does deep work before closing the mouth");
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void twoIndividuallySafeOldProofsCannotSealOneSharedExit(GameTestHelper helper) {
        Problem problem = ConstructionReferenceCases.twoCellGate();
        Cell a = problem.orderedTargets().getFirst();
        Cell b = problem.orderedTargets().getLast();
        Cell inside = problem.start();
        State initial = problem.initialState();
        State afterA = new State(Set.of(a), inside, 1);
        State afterBoth = new State(Set.of(a, b), inside, 2);
        List<Cell> entry = ConstructionReference.route(problem, initial, problem.safeCells(), Set.of(inside));
        List<Cell> firstEscape = ConstructionReference.route(problem, afterA, Set.of(inside), problem.safeCells());
        helper.assertTrue(!entry.isEmpty() && !firstEscape.isEmpty()
            && ConstructionReference.placeGeometry(problem, afterA, b), "A is safe and B still has a real work stance");
        helper.assertTrue(ConstructionReference.route(problem, afterBoth, Set.of(inside), problem.safeCells()).isEmpty(),
            "the combined effect seals both lanes");
        List<Cell> stale = List.of(inside, new Cell(4, 1, 1), new Cell(3, 1, 1), new Cell(2, 1, 1), new Cell(1, 1, 1));
        Witness oldProofs = witness(problem, List.of(new Place(a, inside, entry, firstEscape),
            new Place(b, inside, ConstructionReference.route(problem, afterA, problem.safeCells(), Set.of(inside)), stale),
            new Walk(stale), new Finish(stale.getLast())), new State(Set.of(a, b), stale.getLast(), 2), 0);
        rejectedByBoth(helper, problem, oldProofs, "the second action must be checked after the first action's terrain effect");
        helper.assertTrue(ConstructionReference.validate(problem, solved(helper, problem)).valid(),
            "a serial solution can withdraw before the last closure");
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void placedBlocksCreateNewStandingCellsAndACompleteRaisedWorkFace(GameTestHelper helper) {
        Problem problem = ConstructionReferenceCases.raisedPit();
        Cell highTarget = problem.orderedTargets().getLast();
        helper.assertTrue(!ConstructionReference.standable(problem, problem.initialState(), new Cell(2, 3, 2)),
            "the upper work stance does not exist before construction");
        Witness solution = solved(helper, problem);
        Place high = placements(solution).stream().filter(place -> place.targetCell().equals(highTarget)).findFirst().orElseThrow();
        helper.assertTrue(high.stance().y() >= 3, "the last high target needs the genuinely newly built stepped work face");
        helper.assertTrue(solution.actions().stream().filter(Walk.class::isInstance).map(Walk.class::cast)
            .flatMap(walk -> walk.path().stream()).anyMatch(cell -> cell.y() >= 3), "the worker actually walks onto the new standing cells");
        helper.assertTrue(solution.summary().finalState().usedMaterials() == 4 && solution.summary().remainingMaterials() == 0,
            "four placements conserve exactly four supplied units");
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void anOrdinaryRoofCanBePlacedFromBelowWithoutFillingEverythingUnderIt(GameTestHelper helper) {
        Problem problem = ConstructionReferenceCases.roof();
        Cell roof = problem.orderedTargets().getFirst();
        helper.assertTrue(problem.voxel(roof.below()) == Voxel.AIR, "the normal non-gravity target has no artificial below-target prerequisite");
        Witness solution = solved(helper, problem);
        helper.assertTrue(placements(solution).getFirst().stance().y() < roof.y(), "the full plan uses the lower work stance");
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void sharedBranchWorkFinishesBeforeItsCommonEntranceCloses(GameTestHelper helper) {
        Problem problem = ConstructionReferenceCases.sharedBranch();
        List<Cell> order = placements(solved(helper, problem)).stream().map(Place::targetCell).toList();
        helper.assertTrue(order.size() == 3 && order.getLast().equals(problem.orderedTargets().getFirst()),
            "both dependent branches are completed before their shared entrance");
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void theLastOccupiedCellRequiresActualWithdrawalBeforeItsFinalPlacement(GameTestHelper helper) {
        Problem problem = ConstructionReferenceCases.lastCell();
        Witness solution = solved(helper, problem);
        helper.assertTrue(solution.actions().getFirst() instanceof Walk, "the resident starts on the target and must move first");
        helper.assertTrue(!placements(solution).getFirst().stance().equals(problem.orderedTargets().getFirst()),
            "the final placement never intersects the resident's current body");
        helper.assertTrue(problem.safeCells().contains(solution.summary().finalState().workerCell())
            && solution.actions().getLast() instanceof Finish, "physical withdrawal is part of completion");
        List<Action> omittedMovement = solution.actions().stream().filter(action -> !(action instanceof Walk)).toList();
        rejectedByBoth(helper, problem, new Witness(omittedMovement, solution.dependencies(), solution.inputFingerprint(), solution.summary()),
            "a summary cannot pretend the actual worker walked out");
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void aDirectedDropCanReturnByAnotherStairWithoutInventingItsReverseEdge(GameTestHelper helper) {
        Problem problem = ConstructionReferenceCases.directedReturn(true);
        Cell high = problem.safeCells().iterator().next();
        Cell low = problem.start();
        helper.assertTrue(ConstructionReference.edge(problem, problem.initialState(), high, low), "dropping two blocks is a legal directed edge");
        helper.assertTrue(!ConstructionReference.edge(problem, problem.initialState(), low, high), "a two-block upward reverse edge is illegal");
        List<Cell> returnPath = ConstructionReference.route(problem, problem.initialState(), Set.of(low), Set.of(high));
        helper.assertTrue(returnPath.size() > 2, "a distinct stair supplies the valid return direction");
        Witness bad = witness(problem, List.of(new Walk(List.of(low, high)), new Finish(high)), new State(Set.of(), high, 0), 0);
        rejectedByBoth(helper, problem, bad, "the checker must not convert directed movement into undirected adjacency");
        helper.assertTrue(ConstructionReference.solve(ConstructionReferenceCases.directedReturn(false), 100000).kind()
            == Kind.UNSAT_WITHIN_MODEL, "without the alternate stair the known finite return graph is exhausted");
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void referenceBudgetYieldsRetainTheFrontierAndNeverBecomeFalseImpossibility(GameTestHelper helper) {
        Problem problem = ConstructionReferenceCases.singleMouth();
        var session = new ConstructionReference.Session(problem);
        var result = session.advance(new ExpansionBudget(0));
        helper.assertTrue(result.kind() == Kind.UNKNOWN_BUDGET && result.expanded() == 0, "zero work is unknown, not impossible");
        for (int slice = 0; slice < 100000 && result.kind() == Kind.UNKNOWN_BUDGET; slice++) {
            ExpansionBudget budget = new ExpansionBudget(1);
            result = session.advance(budget);
            helper.assertTrue(budget.used() <= 1, "the reference consumes no extra state-expansion allowance");
        }
        helper.assertTrue(result.kind() == Kind.SOLVED && ConstructionReference.validate(problem, result.witness().orElseThrow()).valid(),
            "one-state slices resume to the same complete answer");
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void unknownVoxelsAndOccupiedTargetsAreNotAnUnrestrictedNoSolutionClaim(GameTestHelper helper) {
        Problem closed = ConstructionReferenceCases.afterPrematureMouthClosure();
        Map<Cell, Voxel> unknown = new LinkedHashMap<>(closed.baseVoxels());
        unknown.put(closed.orderedTargets().getFirst(), Voxel.UNKNOWN);
        Problem incomplete = changed(closed, unknown, closed.orderedTargets(), closed.protectedCells(), closed.capability(), closed.materialCount());
        helper.assertTrue(ConstructionReference.solve(incomplete, 100000).kind() == Kind.UNSUPPORTED,
            "missing geometry cannot prove a real-world impossibility");
        Problem open = ConstructionReferenceCases.singleMouth();
        Map<Cell, Voxel> occupied = new LinkedHashMap<>(open.baseVoxels());
        occupied.put(open.orderedTargets().getFirst(), Voxel.FULL);
        helper.assertTrue(ConstructionReference.solve(changed(open, occupied, open.orderedTargets(), open.protectedCells(),
            open.capability(), open.materialCount()), 100000).kind() == Kind.UNSUPPORTED,
            "a player-filled target needs explicit reconciliation, not silent material consumption");
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void forgedMaterialSummariesMissingDependenciesAndDuplicatePlacementsAreRejected(GameTestHelper helper) {
        Problem problem = ConstructionReferenceCases.roof();
        Witness good = solved(helper, problem);
        Witness wrongLedger = new Witness(good.actions(), good.dependencies(), good.inputFingerprint(),
            new Summary(good.summary().finalState(), 1, true));
        rejectedByBoth(helper, problem, wrongLedger, "remaining plus used must equal the physically supplied count");
        Set<Cell> missing = new LinkedHashSet<>(good.dependencies());
        missing.remove(missing.iterator().next());
        rejectedByBoth(helper, problem, new Witness(good.actions(), missing, good.inputFingerprint(), good.summary()),
            "the v1 whole-snapshot dependency contract cannot omit a voxel");
        List<Action> duplicate = new ArrayList<>(good.actions());
        int placed = duplicate.indexOf(placements(good).getFirst());
        duplicate.add(placed + 1, placements(good).getFirst());
        rejectedByBoth(helper, problem, new Witness(duplicate, good.dependencies(), good.inputFingerprint(), good.summary()),
            "a repeated operation cannot consume a second unit or manufacture another placed block");
        rejectedByBoth(helper, problem, new Witness(good.actions(), good.dependencies(), "stale-input", good.summary()),
            "a stale world/rule certificate is not current evidence");
        // Actual save/crash cut points around draw/setBlock/settle remain native execution tests to add.
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void horizontalRotationsAndTranslationsPreserveTheCompleteWitness(GameTestHelper helper) {
        Problem source = ConstructionReferenceCases.singleMouth();
        Witness sourceWitness = solved(helper, source);
        for (int quarter = 0; quarter < 4; quarter++) {
            final int turns = quarter;
            UnaryOperator<Cell> transform = cell -> transformed(cell, turns, 11, 5, -17);
            Problem problem = transform(source, transform);
            Witness witness = transform(sourceWitness, problem, transform);
            helper.assertTrue(ConstructionReference.validate(problem, witness).valid(),
                "horizontal rotation and translation preserve actual edges, reach and material accounting");
            verifiedByProduction(helper, problem, witness, true);
            helper.assertTrue(ConstructionReference.solve(problem, 100000).kind() == Kind.SOLVED,
                "the transformed finite graph has the same solvability");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void allEightSmallTargetsAreEnumeratedToOneCompletePhysicalLedger(GameTestHelper helper) {
        Problem problem = ConstructionReferenceCases.eightTargets();
        Witness witness = solved(helper, problem);
        helper.assertTrue(witness.summary().finalState().placed().size() == 8
            && witness.summary().finalState().usedMaterials() == 8 && witness.summary().remainingMaterials() == 0,
            "all eight targets and the final withdrawal are certified, not merely a safe prefix");
        verifiedByProduction(helper, problem, witness, true);
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "construction_complete")
    public static void protectionAndMissingTemporaryFacilitiesStayExplicitDomainLimits(GameTestHelper helper) {
        Problem roof = ConstructionReferenceCases.roof();
        Problem protectedRoof = changed(roof, roof.baseVoxels(), roof.orderedTargets(), Set.copyOf(roof.orderedTargets()),
            roof.capability(), roof.materialCount());
        helper.assertTrue(ConstructionReference.solve(protectedRoof, 100000).kind() == Kind.UNSAT_WITHIN_MODEL,
            "a protected target has no legal action in this known finite model");
        Problem raised = ConstructionReferenceCases.raisedPit();
        Problem noPlatforms = changed(raised, raised.baseVoxels(), List.of(raised.orderedTargets().getLast()),
            Set.of(), raised.capability(), 1);
        helper.assertTrue(ConstructionReference.solve(noPlatforms, 100000).kind() == Kind.UNSAT_WITHIN_MODEL,
            "no temporary-facility action exists in v1; this is not a claim that scaffolds cannot solve Minecraft");
        Capability tooWide = new Capability(1.1D, 1.8D, 1, 3, 4.5D);
        helper.assertTrue(ConstructionReference.solve(changed(roof, roof.baseVoxels(), roof.orderedTargets(), Set.of(), tooWide, 1),
            100000).kind() == Kind.UNSUPPORTED, "unsupported bodies cannot receive an apparent complete certificate");
        Capability tooFar = new Capability(0.6D, 1.8D, 1, 3, 5.0D);
        Problem unsupportedReach = changed(roof, roof.baseVoxels(), roof.orderedTargets(), Set.of(), tooFar, 1);
        helper.assertTrue(ConstructionReference.solve(unsupportedReach, 100000).kind() == Kind.UNSUPPORTED,
            "work reach beyond the declared 4.5-block domain must stay unsupported in the independent reference");
        helper.assertTrue(new CompletionSearch(unsupportedReach).advance(new ExpansionBudget(1))
            == CompletionSearch.Status.UNSUPPORTED, "production must use the same explicit work-reach domain");
        helper.succeed();
    }

    private static Witness solved(GameTestHelper helper, Problem problem) {
        var result = ConstructionReference.solve(problem, 200000);
        helper.assertTrue(result.kind() == Kind.SOLVED, "reference must solve fixture: " + result.reason());
        Witness witness = result.witness().orElseThrow();
        var checked = ConstructionReference.validate(problem, witness);
        helper.assertTrue(checked.valid(), "reference-generated evidence is independently replayable: " + checked.reason());
        return witness;
    }

    private static void verifiedByProduction(GameTestHelper helper, Problem problem, Witness witness, boolean expected) {
        CompletionVerifier verifier = new CompletionVerifier(problem, witness);
        for (int slice = 0; slice < 10000 && verifier.status() == CompletionVerifier.Status.VERIFYING; slice++) {
            verifier.advance(new ExpansionBudget(1024));
        }
        helper.assertTrue(verifier.status() == (expected ? CompletionVerifier.Status.VALID : CompletionVerifier.Status.INVALID),
            "production replay disagrees with the independent contract: " + verifier.reason());
    }

    private static void rejectedByBoth(GameTestHelper helper, Problem problem, Witness witness, String why) {
        helper.assertTrue(!ConstructionReference.validate(problem, witness).valid(), why);
        verifiedByProduction(helper, problem, witness, false);
    }

    private static Witness witness(Problem problem, List<Action> actions, State finalState, int remaining) {
        return new Witness(actions, ConstructionReference.wholeDomain(problem), ConstructionReference.fingerprint(problem),
            new Summary(finalState, remaining, true));
    }

    private static List<Place> placements(Witness witness) {
        return witness.actions().stream().filter(Place.class::isInstance).map(Place.class::cast).toList();
    }

    private static Problem changed(Problem p, Map<Cell, Voxel> voxels, List<Cell> targets, Set<Cell> protectedCells,
            Capability capability, int materials) {
        return new Problem(p.bounds(), voxels, targets, protectedCells, p.safeCells(), p.start(), materials, capability, p.ruleVersion());
    }

    private static Cell transformed(Cell cell, int turns, int dx, int dy, int dz) {
        int x = cell.x();
        int z = cell.z();
        for (int turn = 0; turn < turns; turn++) {
            int before = x;
            x = -z;
            z = before;
        }
        return new Cell(x + dx, cell.y() + dy, z + dz);
    }

    private static Problem transform(Problem p, UnaryOperator<Cell> f) {
        Map<Cell, Voxel> voxels = new LinkedHashMap<>();
        p.baseVoxels().forEach((cell, voxel) -> voxels.put(f.apply(cell), voxel));
        List<Cell> all = voxels.keySet().stream().toList();
        Cell min = new Cell(all.stream().mapToInt(Cell::x).min().orElseThrow(), all.stream().mapToInt(Cell::y).min().orElseThrow(),
            all.stream().mapToInt(Cell::z).min().orElseThrow());
        Cell max = new Cell(all.stream().mapToInt(Cell::x).max().orElseThrow(), all.stream().mapToInt(Cell::y).max().orElseThrow(),
            all.stream().mapToInt(Cell::z).max().orElseThrow());
        return new Problem(new Box(min, max), voxels, p.orderedTargets().stream().map(f).toList(), mapped(p.protectedCells(), f),
            mapped(p.safeCells(), f), f.apply(p.start()), p.materialCount(), p.capability(), p.ruleVersion());
    }

    private static Witness transform(Witness witness, Problem problem, UnaryOperator<Cell> f) {
        List<Action> actions = witness.actions().stream().map(action -> {
            if (action instanceof Walk walk) return (Action) new Walk(walk.path().stream().map(f).toList());
            if (action instanceof Place place) return (Action) new Place(f.apply(place.targetCell()), f.apply(place.stance()),
                place.entryFromSafe().stream().map(f).toList(), place.escapeToSafe().stream().map(f).toList());
            return (Action) new Finish(f.apply(((Finish) action).safeCell()));
        }).toList();
        State state = witness.summary().finalState();
        return new Witness(actions, mapped(witness.dependencies(), f), ConstructionReference.fingerprint(problem),
            new Summary(new State(mapped(state.placed(), f), f.apply(state.workerCell()), state.usedMaterials()),
                witness.summary().remainingMaterials(), witness.summary().finished()));
    }

    private static Set<Cell> mapped(Set<Cell> cells, UnaryOperator<Cell> transform) {
        return cells.stream().map(transform).collect(Collectors.toSet());
    }
}
