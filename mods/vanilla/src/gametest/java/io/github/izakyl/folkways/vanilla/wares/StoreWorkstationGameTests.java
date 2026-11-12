package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.inventory.api.StoreWorkstations;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.StandGeometry;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysVanillaMod.ID)
@PrefixGameTestTemplate(false)
public final class StoreWorkstationGameTests {

    private static final Map<WorldPos, Set<StoreWorkstations.Capability>> DECLARED = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        StoreWorkstations.register(ResourceLocation.fromNamespaceAndPath(FolkwaysVanillaMod.ID, "test_stores"),
            (level, pos) -> DECLARED.getOrDefault(WorldPos.of(level, pos), Set.of()));
        event.register(StoreWorkstationGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void residentCraftsAtEnrolledStoreAndRevocationRemovesStation(GameTestHelper helper) {
        var level = helper.getLevel();
        floor(helper);
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        ((Container) level.getBlockEntity(pos)).setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        WorldPos store = WorldPos.of(level, pos);
        var colony = Colonies.mint(level.getServer());
        var presence = colony.service(WaresContent.ID, WaresPresence.class).orElseThrow();
        DECLARED.put(store, Set.of(StoreWorkstations.Capability.CRAFTING));
        presence.refresh(level.getServer());
        helper.assertTrue(workshops(colony, helper).isEmpty(), "a declaration alone does not enroll a store");
        colony.hold(WaresContent.STORE, new Claimed.Block(store)).orElseThrow();
        presence.refresh(level.getServer());
        Workshop station = workshops(colony, helper).getFirst();
        helper.assertTrue(station.site().equals(new WorkSite.AtBlock(store)), "work is at the enrolled store");
        Produce wanted = Produce.of(WaresContent.ID, station.site(), ItemSpec.of(Items.STICK), 4, Produce.UNBOUNDED);
        Node stale = station.options(wanted, Grown.of(wanted)).getFirst().replacement().nodes().getFirst();
        helper.assertValueEqual(stale.spec().stances().cells(), StandGeometry.footingsOf(level, store).stream()
            .map(Stand::cell).collect(java.util.stream.Collectors.toSet()), "the store's footings are reused");
        WorkDefaults.set(colony, PersonBody.ID, WaresContent.CRAFTING, true, 1);
        WorkDefaults.set(colony, PersonBody.ID, Vocations.HAULING, true, 1);
        var body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(helper.absolutePos(new BlockPos(6, 1, 2)).getBottomCenter());
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        LaborFixture labor = new LaborFixture(colony, level);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean consumed = new AtomicBoolean();
        Cleanup.on(helper, () -> {
            running.set(false);
            DECLARED.remove(store);
            labor.close();
            body.discard();
            colony.raze();
        });
        WorldPos target = WorldPos.of(level, helper.absolutePos(new BlockPos(8, 1, 2)));
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), WaresContent.ID, new WorkSite.AtBlock(target),
            Stances.at(target), Workload.Once.of(1)).needs(new Need(ItemSpec.of(Items.STICK), 4, true)).done();
        colony.submit(WaresContent.ID, level.dimension(), Grown.of(new Node() {
            @Override
            public NodeSpec spec() {
                return spec;
            }

            @Override
            public Outcome commit(net.minecraft.server.level.ServerLevel world, Worker worker) {
                helper.assertTrue(worker.supplied().stream().filter(stack -> stack.is(Items.STICK))
                    .mapToInt(ItemStack::getCount).sum() == 4, "resident receives real crafted sticks");
                consumed.set(true);
                return Outcome.done();
            }
        }), (id, ending) -> { });
        helper.onEachTick(() -> {
            if (running.get()) {
                presence.tick(level.getServer());
                labor.tick();
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(consumed.get(),
            "resident crafts using only the STORE claim: " + labor.diagnosis()))
            .thenExecute(() -> {
                DECLARED.remove(store);
                helper.assertTrue(stale.refusal(level).isPresent(), "revocation invalidates already planned work");
                presence.refresh(level.getServer());
                helper.assertTrue(workshops(colony, helper).isEmpty(), "revocation removes the workshop");
                helper.assertTrue(((Container) level.getBlockEntity(pos)).isEmpty(), "native ingredients were spent");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void capabilitiesChooseRecipesAndVanillaStationsSurviveRevocation(GameTestHelper helper) {
        floor(helper);
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        WorldPos store = WorldPos.of(level, pos);
        var colony = Colonies.mint(level.getServer());
        Cleanup.on(helper, () -> {
            DECLARED.remove(store);
            colony.raze();
        });
        colony.hold(WaresContent.STORE, new Claimed.Block(store)).orElseThrow();
        for (var entry : Map.of(4, Blocks.CRAFTING_TABLE, 6, Blocks.STONECUTTER, 8, Blocks.SMITHING_TABLE).entrySet()) {
            BlockPos at = helper.absolutePos(new BlockPos(entry.getKey(), 1, 2));
            level.setBlockAndUpdate(at, entry.getValue().defaultBlockState());
            colony.hold(WaresContent.STATION, new Claimed.Block(WorldPos.of(level, at))).orElseThrow();
        }
        DECLARED.put(store, Set.of(StoreWorkstations.Capability.values()));
        var presence = colony.service(WaresContent.ID, WaresPresence.class).orElseThrow();
        presence.refresh(level.getServer());
        helper.assertValueEqual(workshops(colony, helper).size(), 6, "three native and three declared stations");
        for (Workshop workshop : workshops(colony, helper)) {
            helper.assertTrue(workshop.withholdsStore() != workshop.site().where().equals(store),
                "only STORE pack workstations retain ordinary material supply after decoration");
        }
        for (var entry : Map.of(StoreWorkstations.Capability.CRAFTING, Items.STICK,
            StoreWorkstations.Capability.STONECUTTING, Items.STONE_SLAB,
            StoreWorkstations.Capability.SMITHING, Items.NETHERITE_PICKAXE).entrySet()) {
            var item = entry.getValue();
            DECLARED.put(store, Set.of(entry.getKey()));
            presence.refresh(level.getServer());
            Produce wanted = Produce.of(WaresContent.ID, new WorkSite.AtBlock(store), ItemSpec.of(item), 1,
                Produce.UNBOUNDED);
            long answers = workshops(colony, helper).stream().filter(host -> host.site().equals(wanted.spec().site()))
                .filter(host -> !host.options(wanted, Grown.of(wanted)).isEmpty()).count();
            helper.assertValueEqual(answers, 1L, "exactly the declared recipe capability answers for " + item);
        }
        DECLARED.remove(store);
        presence.refresh(level.getServer());
        helper.assertValueEqual(workshops(colony, helper).size(), 3, "native stations remain after revocation");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void condemnedStoreLosesItsCapabilitiesAndPendingWork(GameTestHelper helper) {
        floor(helper);
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        WorldPos store = WorldPos.of(level, pos);
        var colony = Colonies.mint(level.getServer());
        Cleanup.on(helper, () -> {
            DECLARED.remove(store);
            colony.raze();
        });
        colony.hold(WaresContent.STORE, new Claimed.Block(store)).orElseThrow();
        DECLARED.put(store, Set.of(StoreWorkstations.Capability.CRAFTING));
        var presence = colony.service(WaresContent.ID, WaresPresence.class).orElseThrow();
        presence.refresh(level.getServer());
        Workshop station = workshops(colony, helper).getFirst();
        Produce wanted = Produce.of(WaresContent.ID, station.site(), ItemSpec.of(Items.STICK), 4, Produce.UNBOUNDED);
        Node stale = station.options(wanted, Grown.of(wanted)).getFirst().replacement().nodes().getFirst();
        StorePorts.condemn(level, store, Component.literal("test conservation failure"));
        helper.assertTrue(StoreWorkstations.at(level, pos).isEmpty(), "condemnation hides live declarations");
        helper.assertTrue(stale.commit(level, null) instanceof Outcome.Failed,
            "pending work fails before touching the worker's ingredients");
        presence.refresh(level.getServer());
        helper.assertTrue(workshops(colony, helper).isEmpty(), "an unusable store publishes no workshop");
        helper.succeed();
    }

    private static List<Workshop> workshops(Colony colony, GameTestHelper helper) {
        return ColonyData.find(helper.getLevel().getServer(), colony.id()).orElseThrow().works()
            .workshopsIn(helper.getLevel().dimension());
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 6; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }
}
