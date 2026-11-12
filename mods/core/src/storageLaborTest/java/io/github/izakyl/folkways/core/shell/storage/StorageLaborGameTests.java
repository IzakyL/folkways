package io.github.izakyl.folkways.core.shell.storage;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.front.engine.colony.MemberToggle;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
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
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class StorageLaborGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        String profile = System.getProperty("folkways.storage.profile");
        for (var method : StorageLaborGameTests.class.getDeclaredMethods()) {
            if (method.getName().equals("residentCraftsAtNativeUpgradeStore")
                && !profile.equals("sophisticatedStorage") && !profile.equals("sophisticatedBackpacks")) {
                continue;
            }
            if (profile.equals("sophisticatedBackpacks") && (method.getName().equals("networkEndpointsAreObservedOnce")
                || method.getName().equals("residentFetchesThroughProxy")
                || method.getName().equals("residentFetchesThroughNetworkMember"))) {
                continue;
            }
            event.register(method);
        }
    }

    // Exercises discovery, planning, movement and native transfers.
    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void residentFetchesNativeStockForWork(GameTestHelper helper) throws ReflectiveOperationException {
        residentWork(helper, false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void residentCraftsAtNativeUpgradeStore(GameTestHelper helper) throws ReflectiveOperationException {
        residentWork(helper, true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void residentFetchesThroughProxy(GameTestHelper helper) throws ReflectiveOperationException {
        residentWork(helper, false, true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void residentFetchesThroughNetworkMember(GameTestHelper helper) throws ReflectiveOperationException {
        residentWork(helper, false, false);
    }

    private static void residentWork(GameTestHelper helper, boolean crafting) throws ReflectiveOperationException {
        residentWork(helper, crafting, null);
    }

    private static void residentWork(GameTestHelper helper, boolean crafting, Boolean throughProxy)
            throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        String profile = System.getProperty("folkways.storage.profile");
        String block = Map.of("sophisticatedStorage", "sophisticatedstorage:chest",
            "sophisticatedBackpacks", "sophisticatedbackpacks:backpack", "drawers", "storagedrawers:oak_full_drawers_2",
            "functional", "functionalstorage:oak_2").get(profile);
        for (int x = throughProxy == null ? 0 : -5; x < (throughProxy == null ? 10 : 15); x++) {
            for (int z = throughProxy == null ? 0 : -5; z < (throughProxy == null ? 6 : 10); z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos source = new BlockPos(1, 1, 2);
        var nativeBlock = PortChecks.place(helper, source, block);
        if (profile.equals("sophisticatedBackpacks")) {
            nativeBlock.getClass().getMethod("setBackpack", ItemStack.class)
                .invoke(nativeBlock, PortChecks.item("sophisticatedbackpacks:backpack"));
        }
        if (crafting) {
            Object wrapper = nativeBlock.getClass().getMethod(profile.equals("sophisticatedStorage")
                ? "getStorageWrapper" : "getBackpackWrapper").invoke(nativeBlock);
            var upgrades = (net.neoforged.neoforge.items.IItemHandlerModifiable) Class.forName(
                "net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper").getMethod("getUpgradeHandler").invoke(wrapper);
            upgrades.setStackInSlot(0, PortChecks.item((profile.equals("sophisticatedStorage")
                ? "sophisticatedstorage" : "sophisticatedbackpacks") + ":crafting_upgrade"));
        }
        var port = PortChecks.at(helper, source);
        ItemStack stock = new ItemStack(crafting ? Items.OAK_PLANKS : Items.STONE, crafting ? 2 : 32);
        helper.assertTrue(port.put(level, List.of(stock), List.of(), false, false).done(),
            "native stock is seeded through the public port");
        BlockPos proxy = new BlockPos(9, 1, 2);
        if (throughProxy != null) {
            connectProxy(helper, profile, proxy, source);
        }
        var colony = Colonies.mint(level.getServer());
        ResourceLocation owner = ResourceLocation.fromNamespaceAndPath("folkways_core", "labor_test");
        WorkDefaults.set(colony, PersonBody.ID, Vocations.HAULING, true, 1);
        if (crafting) {
            WorkDefaults.set(colony, PersonBody.ID, WaresContent.CRAFTING, true, 1);
        }
        var body = PersonBody.RESIDENT.get().create(level);
        BlockPos start = new BlockPos(throughProxy == null ? 6 : throughProxy ? 8 : 2, 1,
            throughProxy == null ? 2 : 3);
        body.moveTo(helper.absolutePos(start).getBottomCenter());
        if (throughProxy != null) {
            body.setNoAi(true);
        }
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        if (crafting) {
            helper.assertTrue(MemberToggle.toggleAt(level, colony, helper.absolutePos(source),
                FakePlayerFactory.getMinecraft(level)).refusal().isEmpty(), "the book enrolls the native store");
            helper.assertTrue(colony.claimAt(WorldPos.of(level, helper.absolutePos(source)))
                .filter(claim -> claim.owner().equals(WaresContent.STORE)).isPresent(),
                "the upgraded container needs only a STORE claim");
            helper.assertTrue(colony.claims(WaresContent.STATION).isEmpty(), "no separate workstation is enrolled");
            Class<?> presence = Class.forName("io.github.izakyl.folkways.vanilla.wares.WaresPresence");
            var refresh = presence.getDeclaredMethod("refresh", net.minecraft.server.MinecraftServer.class);
            refresh.setAccessible(true);
            refresh.invoke(colony.service(WaresContent.ID, presence).orElseThrow(), level.getServer());
        } else if (throughProxy != null) {
            helper.assertTrue(MemberToggle.toggleAt(level, colony, helper.absolutePos(proxy),
                FakePlayerFactory.getMinecraft(level)).refusal().isEmpty(), "book enrolls proxy and its members");
            helper.assertTrue(colony.claimAt(WorldPos.of(level, helper.absolutePos(source))).isPresent(),
                "member has its own enrolled frame");
        } else {
            colony.stores(owner, level.dimension(), List.of(WorldPos.of(level, helper.absolutePos(source))));
        }
        int required = crafting ? 4 : 16;
        WorldPos destination = WorldPos.of(level, helper.absolutePos(throughProxy == null
            ? new BlockPos(8, 1, 2) : start));
        AtomicInteger consumed = new AtomicInteger();
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), owner, new WorkSite.AtBlock(destination),
            Stances.at(destination), Workload.Once.of(1)).vocation(Vocations.required(Vocations.HAULING))
            .needs(new Need(ItemSpec.of(crafting ? Items.STICK : Items.STONE), required, true)).done();
        Node node = new Node() {
            @Override
            public NodeSpec spec() {
                return spec;
            }

            @Override
            public Outcome commit(ServerLevel world, Worker worker) {
                int supplied = worker.supplied().stream().mapToInt(ItemStack::getCount).sum();
                helper.assertTrue(supplied == required && worker.supplied().stream()
                    .allMatch(stack -> stack.is(crafting ? Items.STICK : Items.STONE)),
                    "work receives exactly its native or crafted goods");
                consumed.addAndGet(supplied);
                return Outcome.done();
            }
        };
        LaborFixture labor = new LaborFixture(colony, level);
        AtomicBoolean running = new AtomicBoolean(true);
        Cleanup.on(helper, () -> {
            running.set(false);
            labor.close();
            body.discard();
            colony.raze();
        });
        colony.submit(owner, level.dimension(), Grown.of(node), (id, ending) -> { });
        AtomicBoolean witnessed = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (running.get()) {
                labor.tick();
                if (throughProxy != null && !witnessed.get()
                    && PortChecks.count(helper, PortChecks.at(helper, source)) < 32) {
                    witnessed.set(true);
                    BlockPos used = helper.absolutePos(throughProxy ? proxy : source);
                    helper.assertTrue(io.github.izakyl.folkways.core.world.api.Reach.withinReach(
                        body.blockPosition(), used), "resident transfers at selected access " + used
                        + " from " + body.blockPosition()
                        + " verdict=" + PortChecks.at(helper, source).usability());
                    helper.assertTrue(!io.github.izakyl.folkways.core.world.api.Reach.withinReach(
                        body.blockPosition(), helper.absolutePos(throughProxy ? source : proxy)),
                        "selected access is physically distinct from the other endpoint");
                }
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(consumed.get() == required,
            "resident works from native storage: consumed=" + consumed.get() + " stock="
                + PortChecks.count(helper, PortChecks.at(helper, source)) + " body=" + body.blockPosition()
                + " route=" + labor.route(body).stream().map(n -> n.spec().site()).toList()
                + " stores=" + colony.inventory().snapshot().stores().values().stream()
                    .map(s -> s.state() + "/" + s.usability() + "/" + s.entries() + "/" + s.operations()
                        + "/stands=" + s.approach().taking().size()).toList()
                + " diagnosis=" + labor.diagnosis()))
            .thenExecute(() -> helper.assertTrue(PortChecks.count(helper, PortChecks.at(helper, source))
                == (crafting ? 0 : 16),
                "only the consumed goods leave storage"))
            .thenSucceed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 200)
    public static void networkEndpointsAreObservedOnce(GameTestHelper helper) throws ReflectiveOperationException {
        String profile = System.getProperty("folkways.storage.profile");
        BlockPos controller = new BlockPos(1, 1, 2);
        BlockPos member = controller.east();
        PortChecks.place(helper, member, Map.of("drawers", "storagedrawers:oak_full_drawers_2",
            "functional", "functionalstorage:oak_2",
            "sophisticatedStorage", "sophisticatedstorage:chest").get(profile));
        connectProxy(helper, profile, controller, member);
        helper.assertTrue(PortChecks.at(helper, member).put(helper.getLevel(),
            List.of(new ItemStack(Items.STONE, 23)), List.of(), false, false).done(), "member stock is seeded");
        var colony = Colonies.mint(helper.getLevel().getServer());
        ResourceLocation owner = ResourceLocation.fromNamespaceAndPath("folkways_core", "dedup_test");
        colony.stores(owner, helper.getLevel().dimension(), List.of(
            WorldPos.of(helper.getLevel(), helper.absolutePos(controller)),
            WorldPos.of(helper.getLevel(), helper.absolutePos(member))));
        LaborFixture labor = new LaborFixture(colony, helper.getLevel());
        AtomicBoolean running = new AtomicBoolean(true);
        Cleanup.on(helper, () -> {
            running.set(false);
            labor.close();
            colony.raze();
        });
        helper.onEachTick(() -> {
            if (running.get()) {
                colony.inventory().ensureObserved();
                labor.tick();
            }
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(colony.inventory().snapshot().coverage().complete(), "network observation completes");
            helper.assertTrue(colony.inventory().snapshot().stores().size() == 1,
                "registering controller and member publishes one inventory");
            var store = colony.inventory().snapshot().stores().values().iterator().next();
            helper.assertTrue(store.aliases().size() == 1 && store.operations().size() == 2 && store.entries().stream()
                .mapToLong(entry -> entry.count()).sum() == 23, "member and proxy approaches publish stock once");
        }).thenSucceed();
    }

    private static void connectProxy(GameTestHelper helper, String profile, BlockPos proxy, BlockPos member)
            throws ReflectiveOperationException {
        String block = Map.of("drawers", "storagedrawers:controller",
            "functional", "functionalstorage:storage_controller",
            "sophisticatedStorage", "sophisticatedstorage:controller").get(profile);
        if (profile.equals("drawers") && proxy.getX() > member.getX()) {
            for (int x = member.getX() + 1; x < proxy.getX(); x++) {
                PortChecks.place(helper, new BlockPos(x, member.getY(), member.getZ()), "storagedrawers:oak_trim");
            }
        }
        var control = PortChecks.place(helper, proxy, block);
        if (profile.equals("drawers")) {
            control.getClass().getMethod("updateCache").invoke(control);
        } else if (profile.equals("functional")) {
            Class<?> mode = Class.forName("com.buuz135.functionalstorage.item.LinkingToolItem$ActionMode");
            control.getClass().getMethod("addConnectedDrawers", mode, BlockPos[].class)
                .invoke(control, mode.getField("ADD").get(null), new BlockPos[] {helper.absolutePos(member)});
        } else {
            control.getClass().getMethod("addStorage", BlockPos.class).invoke(control, helper.absolutePos(member));
        }
    }
}
