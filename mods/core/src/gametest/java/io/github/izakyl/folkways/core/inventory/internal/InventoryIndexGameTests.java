package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.inventory.api.InventoryDiscovery;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback.StoreSlot;
import io.github.izakyl.folkways.core.inventory.api.InventoryProviders;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot.State;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyStocks;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InventoryIndexGameTests {

    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "inventory");
    private static final Map<BlockPos, Probe> PROBES = new HashMap<>();

    private static final Map<UUID, List<InventoryProviders.Endpoint>> ENDPOINTS = new HashMap<>();
    private static final Set<UUID> INCOMPLETE = new HashSet<>();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        Containers.register(SignBlockEntity.class,
            (level, sign) -> Optional.ofNullable(PROBES.get(sign.getBlockPos())));
        event.register(InventoryIndexGameTests.class);
    }

    @SubscribeEvent
    public static void declare(io.github.izakyl.folkways.core.extension.api.Declaring event) {
        InventoryProviders.register(OWNER, new InventoryProviders.Provider() {
            public InventoryProviders.Page discover(InventoryDiscovery colony, ServerLevel level, String cursor, int limit) {
                return new InventoryProviders.Page(ENDPOINTS.getOrDefault(colony.id(), List.of()), null,
                    !INCOMPLETE.contains(colony.id()));
            }

            public Container open(ServerLevel level, InventoryProviders.Endpoint endpoint) {
                return PROBES.get(endpoint.anchor().cell());
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void incompleteProviderDiscoveryRetainsStockAndCoverage(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, Blocks.OAK_SIGN.defaultBlockState());
        WorldPos at = WorldPos.of(level, pos);
        Probe probe = new Probe(27);
        probe.setItem(0, new ItemStack(Items.BREAD, 7));
        PROBES.put(pos, probe);
        ColonyData data = new ColonyData();
        ENDPOINTS.put(data.colonyId(), List.of(new InventoryProviders.Endpoint(OWNER, at)));
        try {
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 20);
            helper.assertTrue(data.inventory().snapshot().coverage().complete(), "the initial endpoint is covered");
            helper.assertTrue(Boolean.TRUE.equals(data.inventory().snapshot().coverage().providers().get(OWNER)),
                "the provider reports complete discovery");
            int reads = probe.reads;
            ENDPOINTS.put(data.colonyId(), List.of());
            INCOMPLETE.add(data.colonyId());
            data.inventory().providersChanged(OWNER);
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 20);
            helper.assertFalse(data.inventory().snapshot().coverage().complete(),
                "unavailable discovery is incomplete");
            helper.assertTrue(Boolean.FALSE.equals(data.inventory().snapshot().coverage().providers().get(OWNER)),
                "the provider reports its incomplete discovery");
            helper.assertTrue(ColonyStocks.available(data.works(), level.getServer(), ItemSpec.of(Items.BREAD)) == 7,
                "an incomplete page retains the last known endpoint");
            data.inventory().work(level.getServer(), 20);
            helper.assertTrue(probe.reads == reads, "incomplete discovery does not poll the endpoint");
            INCOMPLETE.remove(data.colonyId());
            data.inventory().ensureObservedProvider(OWNER);
            data.inventory().ensureObservedProvider(OWNER);
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 20);
            helper.assertTrue(data.inventory().snapshot().coverage().complete()
                && !data.inventory().snapshot().stores().containsKey(Stash.at(at)),
                "a complete rediscovery removes an endpoint no longer offered");
        } finally {
            INCOMPLETE.remove(data.colonyId());
            ENDPOINTS.remove(data.colonyId());
            PROBES.remove(pos);
            data.inventory().clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void nonInventoryWorksiteDoesNotBlockCoverage(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.COMPOSTER.defaultBlockState());
        WorldPos at = WorldPos.of(level, cell);
        ColonyData data = new ColonyData();
        try {
            data.inventory().observe(OWNER, level, List.of(at));
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 100);
            helper.assertTrue(store(data, at).state() == State.UNSUPPORTED && !store(data, at).labor(),
                "a composter is a worksite without a container");
            helper.assertTrue(data.inventory().snapshot().coverage().complete(),
                "a non-inventory worksite does not leave stock coverage incomplete");
            ENDPOINTS.put(data.colonyId(), List.of(new InventoryProviders.Endpoint(OWNER, at)));
            data.inventory().providersChanged(OWNER);
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 100);
            helper.assertFalse(data.inventory().snapshot().coverage().complete(),
                "an unsupported provider endpoint still leaves coverage incomplete");
        } finally {
            ENDPOINTS.remove(data.colonyId());
            data.inventory().clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void providerStockIsObservedOnceAndTotalsNeverReadContainers(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, Blocks.OAK_SIGN.defaultBlockState());
        WorldPos at = WorldPos.of(level, pos);
        Probe probe = new Probe(27);
        probe.setItem(0, new ItemStack(Items.BREAD, 12));
        PROBES.put(pos, probe);
        ColonyData data = new ColonyData();
        ENDPOINTS.put(data.colonyId(), List.of(new InventoryProviders.Endpoint(OWNER, at)));
        try {
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 20);
            helper.assertTrue(ColonyStocks.available(data.works(), level.getServer(), ItemSpec.of(Items.BREAD)) == 12,
                "provider-only inventory contributes physical stock");
            data.works().stores(OWNER, level.dimension(), List.of(at));
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 20);
            var snapshot = data.inventory().snapshot();
            int reads = probe.reads;
            for (int query = 0; query < 10; query++) {
                helper.assertTrue(
                    ColonyStocks.available(data.works(), level.getServer(), ItemSpec.of(Items.BREAD)) == 12,
                    "provider and ordinary membership count the physical container once");
                helper.assertTrue(ColonyStocks.tally(data.works(), level.getServer()).values().stream()
                    .mapToInt(Integer::intValue).sum() == 12, "both totals views share the same stock");
            }
            data.inventory().advance(level, data.works(), Map.of());
            helper.assertTrue(probe.reads == reads && data.inventory().snapshot() == snapshot,
                "unchanged totals and index advances read no containers and retain the publication");
            helper.assertFalse(snapshot.stores().get(Stash.at(at)).labor(), "providers stay outside labor stock");
            probe.setItem(0, new ItemStack(Items.BREAD, 7));
            data.inventory().changed(Stash.at(at));
            data.inventory().work(level.getServer(), 20);
            helper.assertTrue(ColonyStocks.available(data.works(), level.getServer(), ItemSpec.of(Items.BREAD)) == 7,
                "provider mutations refresh only their physical observation");
        } finally {
            ENDPOINTS.remove(data.colonyId());
            PROBES.remove(pos);
            data.inventory().clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void noDemandBootstrapsTheColonyIndex(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        WorldPos at = barrel(helper);
        Containers.at(level, at.cell()).orElseThrow().setItem(3, new ItemStack(Items.BREAD, 5));
        colony.stores(OWNER, level.dimension(), List.of(at));
        helper.succeedWhen(() -> {
            InventorySnapshot snapshot = colony.inventory().snapshot();
            helper.assertTrue(snapshot.stores().size() == 1 && snapshot.coverage().complete(),
                "a colony with no residents or labor asks observes its offered stores");
            helper.assertTrue(snapshot.stores().get(Stash.at(at)).slots().get(3).stackCopy().getCount() == 5,
                "bootstrap publishes the physical stack");
            colony.raze();
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void extraEndpointsShareOrdinaryMembershipAndUnchangedRegistrationDoesNotRead(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.OAK_SIGN.defaultBlockState());
        WorldPos at = WorldPos.of(level, cell);
        Probe probe = new Probe(9);
        probe.setItem(2, new ItemStack(Items.WHEAT_SEEDS, 8));
        PROBES.put(cell, probe);
        ColonyData data = new ColonyData();
        try {
            data.inventory().observe(OWNER, level, List.of(at));
            var endpoint = store(data, at);
            helper.assertTrue(endpoint.state() == State.OBSERVED && !endpoint.labor(),
                "an extra endpoint is observable without offering its goods to labor");
            helper.assertTrue(endpoint.slots().get(2).stackCopy().getCount() == 8,
                "extra endpoints publish physical contents");
            int reads = probe.reads;
            data.inventory().observe(OWNER, level, List.of(at));
            helper.assertTrue(probe.reads == reads, "unchanged registration never reopens the endpoint");
            data.inventory().work(level.getServer(), 100);
            helper.assertTrue(probe.reads == reads, "deferred admission sampling reuses the captured slots");
            data.works().stores(OWNER, level.dimension(), List.of(at));
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 100);
            helper.assertTrue(data.inventory().snapshot().stores().size() == 1 && store(data, at).labor(),
                "ordinary and extra membership share one physical observation");
            data.inventory().observe(OWNER, level, List.of());
            helper.assertTrue(data.inventory().snapshot().store(at).isPresent(),
                "withdrawing an observer retains ordinary membership");
            data.works().stores(OWNER, level.dimension(), List.of());
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 100);
            helper.assertTrue(data.inventory().snapshot().store(at).isEmpty(),
                "the entry disappears when its last membership is withdrawn");
        } finally {
            PROBES.remove(cell);
            data.inventory().clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void partialBudgetKeepsAnEntryAtomicAndSnapshotReadsArePure(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, Blocks.OAK_SIGN.defaultBlockState());
        Probe probe = new Probe(10_000);
        probe.setItem(9_999, new ItemStack(Items.DIAMOND, 2));
        PROBES.put(pos, probe);
        ColonyData data = new ColonyData();
        try {
            WorldPos at = WorldPos.of(level, pos);
            data.works().stores(OWNER, level.dimension(), List.of(at));
            data.inventory().advance(level, data.works(), Map.of());
            InventorySnapshot partial = data.inventory().snapshot();
            helper.assertFalse(partial.coverage().complete(), "budget pending is not observed empty");
            helper.assertTrue(partial.stores().get(Stash.at(at)).state() == State.QUEUED,
                "a partially read store is queued");
            helper.assertTrue(partial.stores().get(Stash.at(at)).slots().isEmpty(),
                "no half-read inventory escapes");
            helper.assertTrue(probe.reads <= 64 * 64, "one tick bounds slot reads");
            data.inventory().work(level.getServer(), 200);
            InventorySnapshot complete = data.inventory().snapshot();
            helper.assertTrue(complete.coverage().complete(), "resumable reading eventually completes");
            int reads = probe.reads;
            for (int query = 0; query < 10; query++) {
                var snapshot = data.works().inventory().snapshot();
                snapshot.coverage().complete();
                snapshot.stores().values().forEach(store -> store.slots().forEach(slot -> slot.stackCopy()));
            }
            helper.assertTrue(probe.reads == reads, "snapshot access and stack copies never reopen the container");
            data.inventory().advance(level, data.works(), Map.of());
            helper.assertTrue(probe.reads == reads, "an unchanged index never polls its observed entries");
            helper.assertFalse(partial.coverage().complete(), "a retained publication remains immutable");
        } finally {
            PROBES.remove(pos);
            data.inventory().clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void membershipDiscoverySharesTheBudgetAndReportsPendingImmediately(GameTestHelper helper) {
        var level = helper.getLevel();
        ColonyData data = new ColonyData();
        List<WorldPos> offered = new ArrayList<>();
        for (int store = 0; store < 5_000; store++) {
            offered.add(WorldPos.of(level.dimension(), new BlockPos(21_000_000 + store, 80, 21_000_000)));
        }
        try {
            data.works().stores(OWNER, level.dimension(), offered);
            helper.assertTrue(data.inventory().snapshot().coverage().membershipPending()
                && !data.inventory().snapshot().coverage().complete(),
                "publication marks undiscovered membership incomplete before any observation runs");
            data.inventory().advance(level, data.works(), Map.of());
            helper.assertTrue(data.inventory().snapshot().coverage().membershipPending()
                && data.inventory().snapshot().stores().size() < offered.size(),
                "membership enumeration cannot bypass the tick budget");
            helper.assertFalse(level.isLoaded(offered.getFirst().cell()), "discovery never loads a missing store");
        } finally {
            data.inventory().clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void movingAnIdenticalStackBetweenEmptySlotsChangesContents(GameTestHelper helper) {
        var level = helper.getLevel();
        WorldPos at = barrel(helper);
        var container = Containers.at(level, at.cell()).orElseThrow();
        container.setItem(0, new ItemStack(Items.BREAD, 4));
        ColonyData data = observe(helper, at);
        var before = store(data, at);
        container.setItem(2, container.removeItemNoUpdate(0));
        data.inventory().changed(Stash.at(at));
        helper.assertTrue(store(data, at).state() == State.DIRTY, "mutation feedback immediately marks coverage dirty");
        data.inventory().work(level.getServer(), 10);
        var after = store(data, at);
        helper.assertTrue(after.version().contents() > before.version().contents(), "physical movement has a revision");
        helper.assertTrue(after.slots().size() == 27 && after.slots().get(0).stackCopy().isEmpty()
            && after.slots().get(2).stackCopy().getCount() == 4, "empty physical slots retain their indices");
        data.inventory().clear();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void rulesOnlyChangePolicyAndAccessibleSlotMapping(GameTestHelper helper) {
        var level = helper.getLevel();
        WorldPos at = barrel(helper);
        ColonyData data = observe(helper, at);
        var before = store(data, at);
        try {
            data.works().rule(OWNER, level.dimension(), List.of(new StoreRule.Slots(at, 3, 7),
                new StoreRule.Access(at, false, true)));
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 10);
            var after = store(data, at);
            helper.assertTrue(after.version().contents() == before.version().contents(),
                "rules never manufacture a content revision");
            helper.assertTrue(after.version().policy() > before.version().policy(),
                "rule-only changes have a revision");
            helper.assertTrue(after.slots().get(2).accessibleSlot().isEmpty()
                && after.slots().get(3).accessibleSlot().orElseThrow() == 0
                && after.slots().get(6).accessibleSlot().orElseThrow() == 3
                && after.slots().get(7).accessibleSlot().isEmpty(), "accessible indices match Stores.Kept");
            helper.assertFalse(StoreRule.takes(after.rules()), "the effective permissions are detached values");
        } finally {
            Stores.ruled(data.colonyId(), Map.of());
            data.inventory().clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void doubleChestAliasesCountOnePhysicalInventory(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
        var left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos second = first.relative(ChestBlock.getConnectedDirection(left));
        level.setBlock(first, left, 2);
        level.setBlock(second, left.setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
        WorldPos one = WorldPos.of(level, first);
        WorldPos two = WorldPos.of(level, second);
        Containers.at(level, first).orElseThrow().setItem(0, new ItemStack(Items.BREAD, 7));
        ColonyData data = observe(helper, one, two);
        helper.assertTrue(data.inventory().snapshot().stores().size() == 1, "aliases share one canonical observation");
        var store = data.inventory().snapshot().stores().values().iterator().next();
        helper.assertTrue(store.stash().pos().cell().equals(Containers.anchor(level, first))
            && store.aliases().containsAll(List.of(one, two)) && store.slots().size() == 54,
            "the anchor and both halves retain all physical slots");
        helper.assertTrue(store.slots().stream().mapToInt(slot -> slot.stackCopy().getCount()).sum() == 7,
            "the physical stock is counted once");
        try {
            WorldPos alias = store.stash().pos().equals(one) ? two : one;
            data.works().stores(OWNER, level.dimension(), List.of(alias));
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 20);
            helper.assertTrue(data.works().store(level, store.stash().pos()).isPresent(),
                "a canonical snapshot address opens through Colony even when only its other alias was offered");
            data.works().stores(OWNER, level.dimension(), List.of(one, two));
            data.works().withhold(OWNER, level.dimension(), List.of(two));
            data.inventory().advance(level, data.works(), Map.of());
            data.inventory().work(level.getServer(), 20);
            helper.assertFalse(data.inventory().snapshot().stores().values().iterator().next().labor(),
                "withholding either half excludes the physical store from labor, while retaining raw slots");
            data.works().rule(OWNER, level.dimension(), List.of(new StoreRule.Slots(two, 3, 7)));
            data.inventory().work(level.getServer(), 20);
            helper.assertTrue(Stores.at(level, one).orElseThrow().getContainerSize() == 4,
                "live access and observed mapping apply rules published on either alias");
            Stores.ruled(data.colonyId(), Map.of());
            level.setBlock(first, left.setValue(ChestBlock.TYPE, ChestType.SINGLE), 2);
            level.setBlock(second, left.setValue(ChestBlock.TYPE, ChestType.SINGLE), 2);
            data.inventory().changed(store.stash());
            data.inventory().work(level.getServer(), 20);
            helper.assertTrue(data.inventory().snapshot().stores().size() == 2,
                "a split retires the shared alias and discovers both independently offered inventories");
            data.works().stores(OWNER, level.dimension(), List.of(alias));
            helper.assertTrue(data.works().store(level, store.stash().pos()).isEmpty(),
                "a retired alias cannot authorize live access to the newly separated unoffered half");
        } finally {
            Stores.ruled(data.colonyId(), Map.of());
            data.inventory().clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aDoubleChestWithOneHalfUnloadedIsNotComplete(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos first = new BlockPos(22_000_015, 80, 22_000_000);
        var state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos second = first.relative(ChestBlock.getConnectedDirection(state));
        level.getChunk(first);
        helper.assertFalse(level.isLoaded(second), "the second half starts in an actually unloaded chunk");
        var section = level.getChunk(first).getSection(level.getSectionIndex(first.getY()));
        section.setBlockState(first.getX() & 15, first.getY() & 15, first.getZ() & 15, state);
        WorldPos at = WorldPos.of(level, first);
        ColonyData data = observe(helper, at);
        try {
            helper.assertTrue(store(data, at).state() == State.UNLOADED
                && !data.inventory().snapshot().coverage().complete(),
                "half a double chest is incomplete: " + store(data, at).state() + " / "
                    + level.getBlockState(first) + " / loaded=" + level.isLoaded(second));
            helper.assertFalse(level.isLoaded(second), "observation does not load the other half");
        } finally {
            section.setBlockState(first.getX() & 15, first.getY() & 15, first.getZ() & 15,
                Blocks.AIR.defaultBlockState());
            data.inventory().clear();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void feedbackIsTargetedAndUnchangedReadsClearDirtyCoverage(GameTestHelper helper) {
        var level = helper.getLevel();
        WorldPos at = barrel(helper);
        WorldPos other = WorldPos.of(level, at.cell().offset(2, 0, 0));
        level.setBlockAndUpdate(other.cell(), Blocks.BARREL.defaultBlockState());
        ColonyData data = observe(helper, at, other);
        var before = store(data, at);
        var untouched = store(data, other);
        data.inventory().stale(new StoreSlot(Stash.at(at), 0), before.version());
        data.inventory().work(level.getServer(), 10);
        var after = store(data, at);
        helper.assertTrue(after.state() == State.OBSERVED && after.version().contents() == before.version().contents()
            && after.version().coverage() > before.version().coverage(), "an identical reread resolves dirty coverage");
        helper.assertTrue(store(data, other).version().equals(untouched.version()),
            "feedback leaves other stores alone");
        data.inventory().staleApproach(Stash.at(at), after.version());
        helper.assertTrue(store(data, at).approach().state() == State.DIRTY, "approach failures are observable");
        data.inventory().work(level.getServer(), 10);
        helper.assertTrue(store(data, at).approach().state() == State.OBSERVED, "only that approach is read again");
        var current = store(data, at);
        data.inventory().stale(Stash.at(at), after.version());
        helper.assertTrue(store(data, at).state() == State.OBSERVED,
            "a whole-container mismatch against an older version is ignored");
        data.inventory().stale(Stash.at(at), current.version());
        helper.assertTrue(store(data, at).state() == State.DIRTY,
            "a whole-container mismatch against the current version queues that store");
        data.inventory().clear();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void rawResidentSlotsEquipmentAndDefensiveCopiesAreIndependent(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = PersonBody.RESIDENT.get().create(level);
        body.pack().setItem(4, new ItemStack(Items.BREAD, 3));
        body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        ColonyData data = new ColonyData();
        try {
            data.inventory().advance(level, data.works(), Map.of(body.id(), body));
            data.inventory().work(level.getServer(), 10);
            var before = data.inventory().snapshot().residents().get(body.id());
            before.pack().get(4).stackCopy().shrink(3);
            before.equipment().get(EquipmentSlot.HEAD).stackCopy()
                .set(DataComponents.CUSTOM_NAME, Component.literal("copy"));
            helper.assertTrue(before.pack().get(4).stackCopy().getCount() == 3
                && !before.equipment().get(EquipmentSlot.HEAD).stackCopy().has(DataComponents.CUSTOM_NAME),
                "pack and equipment access never leak the cache's stacks");
            body.pack().getItem(4).shrink(1);
            body.pack().getItem(4).set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
            body.pack().setChanged();
            data.inventory().work(level.getServer(), 10);
            var after = data.inventory().snapshot().residents().get(body.id());
            helper.assertTrue(after.version().contents() > before.version().contents()
                && after.pack().get(4).stackCopy().getCount() == 2
                && after.pack().get(4).stackCopy().getHoverName().getString().equals("changed"),
                "in-place count and component edits are observed through setChanged");
            helper.assertTrue(after.pack().get(4).incarnation() == before.pack().get(4).incarnation(),
                "in-place edits retain the physical slot identity");
            data.inventory().unloaded(body.id());
            helper.assertTrue(data.inventory().snapshot().residents().get(body.id()).state() == State.UNLOADED,
                "unload retains last-known goods with incomplete coverage");
        } finally {
            data.inventory().clear();
            body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void replacingAStoreChangesItsIncarnation(GameTestHelper helper) {
        var level = helper.getLevel();
        WorldPos at = barrel(helper);
        ColonyData data = observe(helper, at);
        var before = store(data, at);
        level.setBlockAndUpdate(at.cell(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(at.cell(), Blocks.BARREL.defaultBlockState());
        data.inventory().changed(Stash.at(at));
        data.inventory().work(level.getServer(), 10);
        var after = store(data, at);
        helper.assertTrue(after.incarnation() > before.incarnation()
            && after.version().topology() > before.version().topology(),
            "replacement invalidates old physical slot tokens even when the slots are identical");
        data.inventory().clear();
        data.inventory().advance(level, data.works(), Map.of());
        data.inventory().work(level.getServer(), 20);
        var reopened = store(data, at);
        helper.assertFalse(reopened.version().epoch().equals(after.version().epoch()),
            "a reset cannot reuse an old entry's version");
        data.inventory().stale(new StoreSlot(Stash.at(at), 0), after.version());
        helper.assertTrue(store(data, at).state() == State.OBSERVED,
            "feedback from the retired epoch cannot invalidate a reopened observation");
        data.inventory().clear();
        helper.succeed();
    }

    private static ColonyData observe(GameTestHelper helper, WorldPos... at) {
        ColonyData data = new ColonyData();
        data.works().stores(OWNER, helper.getLevel().dimension(), List.of(at));
        data.inventory().advance(helper.getLevel(), data.works(), Map.of());
        data.inventory().work(helper.getLevel().getServer(), 1_000);
        return data;
    }

    private static InventorySnapshot.Store store(ColonyData data, WorldPos at) {
        return data.inventory().snapshot().stores().get(Stash.at(at));
    }

    private static WorldPos barrel(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
        return WorldPos.of(helper.getLevel(), pos);
    }

    private static final class Probe extends SimpleContainer {

        int reads;

        Probe(int size) {
            super(size);
        }

        @Override
        public ItemStack getItem(int slot) {
            reads++;
            return super.getItem(slot);
        }
    }
}
