package io.github.izakyl.folkways.core.shell.storage;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.front.engine.colony.MemberToggle;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.shell.colossalchests.ChestRig;
import io.github.izakyl.folkways.core.shell.fxntstorage.StorageRig;
import io.github.izakyl.folkways.core.shell.mekanism.QioRig;
import io.github.izakyl.folkways.core.shell.toms.TomsRig;
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
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
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
public final class PortsLaborGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        String profile = System.getProperty("folkways.storage.profile");
        for (var method : PortsLaborGameTests.class.getDeclaredMethods()) {
            if (method.getName().startsWith(profile)) {
                event.register(method);
            }
        }
    }

    private static void run(GameTestHelper helper, String access, boolean put) {
        for (int x = -5; x <= 15; x++) {
            for (int z = -5; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        String profile = System.getProperty("folkways.storage.profile");
        BlockPos target;
        BlockPos stock;
        Runnable connect = () -> { };
        switch (profile) {
            case "createStorage" -> {
                new StorageRig(helper);
                stock = StorageRig.MEMBER;
                target = access.equals("controller") ? StorageRig.CONTROLLER : StorageRig.MEMBER;
            }
            case "toms" -> {
                new TomsRig(helper);
                if (access.equals("connector")) {
                    // This test approaches the connector from the west, beyond its member's reach.
                    helper.setBlock(TomsRig.CABLE, Blocks.AIR);
                }
                stock = TomsRig.MEMBER;
                target = Map.of("connector", TomsRig.CONNECTOR, "terminal", TomsRig.TERMINAL,
                    "crafting", TomsRig.CRAFTING, "cable", TomsRig.CABLE, "framed", TomsRig.FRAMED).get(access);
            }
            case "colossal" -> {
                new ChestRig(helper);
                stock = ChestRig.CORE;
                target = Map.of("core", ChestRig.CORE, "wall", ChestRig.WALL, "interface", ChestRig.INTERFACE)
                    .get(access);
            }
            case "qio" -> {
                QioRig rig = new QioRig(helper);
                stock = QioRig.DASHBOARD;
                target = access.equals("dashboard") ? QioRig.DASHBOARD : QioRig.OTHER;
                connect = rig::connect;
            }
            default -> throw new IllegalStateException("unexpected profile: " + profile);
        }
        helper.startSequence().thenIdle(5).thenExecute(connect).thenIdle(40)
            .thenExecute(() -> work(helper, profile, target, stock, put));
    }

    private static void work(GameTestHelper helper, String profile, BlockPos target, BlockPos stock, boolean put) {
        ServerLevel level = helper.getLevel();
        if (!put) {
            helper.assertTrue(PortChecks.at(helper, stock).put(level, List.of(new ItemStack(Items.STONE, 32)),
                List.of(), false, false).done(), "seed native stock");
        }
        var colony = Colonies.mint(level.getServer());
        ResourceLocation owner = ResourceLocation.parse("folkways_core:ports_labor");
        WorkDefaults.set(colony, PersonBody.ID, Vocations.HAULING, true, 1);
        helper.assertTrue(MemberToggle.toggleAt(level, colony, helper.absolutePos(target),
            FakePlayerFactory.getMinecraft(level)).refusal().isEmpty(), "book enrolls selected native access point");
        WorldPos at = WorldPos.of(level, helper.absolutePos(target));
        helper.assertTrue(colony.claimAt(at).isPresent(), "selected access point retains its enrollment frame");
        if (level.getBlockEntity(helper.absolutePos(target)) == null) {
            helper.assertTrue(colony.claimAt(at).orElseThrow().what() instanceof Claimed.Point,
                "wall uses a persisted point claim without inventing a block entity");
        }
        BlockPos start = profile.equals("toms")
            ? new BlockPos(target.getX() - 4, 1, target.getZ())
            : profile.equals("colossal") ? new BlockPos(target.getX(), 1, target.getZ() - 2)
            : target.south();
        helper.assertTrue(Reach.inSight(level,
            helper.absolutePos(start), helper.absolutePos(target)), "selected access has a clear hand approach");
        var body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(helper.absolutePos(start).getBottomCenter());
        body.setNoAi(true);
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        body.pack().setItem(0, new ItemStack(Items.COOKED_BEEF, 32));
        if (put) {
            body.pack().setItem(1, new ItemStack(Items.STONE, 16));
        }
        AtomicInteger consumed = new AtomicInteger();
        if (put) {
            Delivery delivery = Delivery.to(UUID.randomUUID(), owner, WorkSite.at(level, helper.absolutePos(
                PortChecks.at(helper, target).proxyMembers().isPresent() ? stock : target)),
                Stances.at(level, helper.absolutePos(start)), ItemSpec.of(Items.STONE), 16, arrived -> { });
            colony.submit(owner, level.dimension(), Grown.of(delivery), (id, ending) -> { });
        } else {
            WorldPos destination = WorldPos.of(level, helper.absolutePos(start));
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), owner, new WorkSite.AtBlock(destination),
                Stances.at(destination), Workload.Once.of(1)).vocation(Vocations.required(Vocations.HAULING))
                .needs(new Need(ItemSpec.of(Items.STONE), 16, true)).done();
            Node node = new Node() {
                @Override
                public NodeSpec spec() {
                    return spec;
                }

                @Override
                public Outcome commit(ServerLevel world, Worker worker) {
                    helper.assertTrue(worker.supplied().stream().allMatch(stack -> stack.is(Items.STONE)),
                        "work receives native stone");
                    consumed.addAndGet(worker.supplied().stream().mapToInt(ItemStack::getCount).sum());
                    return Outcome.done();
                }
            };
            colony.submit(owner, level.dimension(), Grown.of(node), (id, ending) -> { });
        }
        LaborFixture labor = new LaborFixture(colony, level);
        AtomicBoolean closed = new AtomicBoolean();
        Cleanup.on(helper, () -> {
            closed.set(true);
            labor.close();
            body.discard();
            colony.raze();
        });
        helper.onEachTick(() -> {
            if (!closed.get()) {
                labor.tick();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stone(helper, stock) == 16
                && (put || consumed.get() == 16), "resident transfers at " + target + "; stock="
                + stone(helper, stock) + "; consumed=" + consumed.get()
                + "; routeSize=" + labor.route(body).size());
            helper.assertTrue(Reach.withinReach(body.blockPosition(),
                helper.absolutePos(target)), "resident is at the selected access point");
            if (profile.equals("toms") || profile.equals("createStorage") && target.equals(StorageRig.CONTROLLER)) {
                helper.assertTrue(!Reach.withinReach(body.blockPosition(),
                    helper.absolutePos(stock)), "proxy transfer happens beyond reach of the member itself");
            }
        });
    }

    private static long stone(GameTestHelper helper, BlockPos pos) {
        return PortChecks.read(helper, PortChecks.at(helper, pos)).entries().stream()
            .filter(entry -> entry.prototype().is(Items.STONE)).mapToLong(entry -> entry.count()).sum();
    }

    private static void frames(GameTestHelper helper) {
        String profile = System.getProperty("folkways.storage.profile");
        List<String> blocks = Map.of("createStorage", List.of("fxntstorage:passer_block",
                "fxntstorage:smart_passer_block",
                "fxntstorage:storage_interface", "fxntstorage:storage_interface_filtered"),
            "toms", List.of("toms_storage:inventory_proxy", "toms_storage:level_emitter",
                "toms_storage:inventory_interface", "toms_storage:open_crate", "toms_storage:basic_inventory_hopper"),
            "colossal", List.of("colossalchests:chest_wall_wood", "colossalchests:colossal_chest_wood",
                "colossalchests:interface_wood"),
            "qio", List.of("mekanism:qio_drive_array", "mekanism:qio_importer", "mekanism:qio_exporter",
                "mekanism:qio_redstone_adapter")).get(profile);
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            int x = 0;
            for (String block : blocks) {
                BlockPos pos = new BlockPos(x++ * 2, 1, 2);
                PortChecks.place(helper, pos, block);
                helper.assertTrue(MemberToggle.toggleAt(helper.getLevel(), colony, helper.absolutePos(pos),
                    FakePlayerFactory.getMinecraft(helper.getLevel())).refusal().isEmpty(),
                    "refused device can be enrolled for its frame: " + block);
                helper.assertTrue(colony.claimAt(WorldPos.of(helper.getLevel(), helper.absolutePos(pos))).isPresent(),
                    "refused device keeps its frame claim");
                PortChecks.unusable(helper, pos);
            }
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void createStorageMemberTake(GameTestHelper helper) {
        run(helper, "member", false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void createStorageMemberPut(GameTestHelper helper) {
        run(helper, "member", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void createStorageControllerPut(GameTestHelper helper) {
        run(helper, "controller", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void createStorageRefusedFrames(GameTestHelper helper) {
        frames(helper);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void tomsConnectorTake(GameTestHelper helper) {
        run(helper, "connector", false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void tomsConnectorPut(GameTestHelper helper) {
        run(helper, "connector", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void tomsTerminalTake(GameTestHelper helper) {
        run(helper, "terminal", false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void tomsTerminalPut(GameTestHelper helper) {
        run(helper, "terminal", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void tomsCraftingTake(GameTestHelper helper) {
        run(helper, "crafting", false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void tomsCraftingPut(GameTestHelper helper) {
        run(helper, "crafting", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void tomsCableTake(GameTestHelper helper) {
        run(helper, "cable", false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void tomsCablePut(GameTestHelper helper) {
        run(helper, "cable", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void tomsFramedTake(GameTestHelper helper) {
        run(helper, "framed", false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void tomsFramedPut(GameTestHelper helper) {
        run(helper, "framed", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void tomsRefusedFrames(GameTestHelper helper) {
        frames(helper);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void colossalCoreTake(GameTestHelper helper) {
        run(helper, "core", false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void colossalCorePut(GameTestHelper helper) {
        run(helper, "core", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void colossalWallTake(GameTestHelper helper) {
        run(helper, "wall", false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void colossalWallPut(GameTestHelper helper) {
        run(helper, "wall", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void colossalInterfaceTake(GameTestHelper helper) {
        run(helper, "interface", false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void colossalInterfacePut(GameTestHelper helper) {
        run(helper, "interface", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void colossalRefusedFrames(GameTestHelper helper) {
        frames(helper);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void qioDashboardTake(GameTestHelper helper) {
        run(helper, "dashboard", false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void qioDashboardPut(GameTestHelper helper) {
        run(helper, "dashboard", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void qioOtherTake(GameTestHelper helper) {
        run(helper, "other", false);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void qioOtherPut(GameTestHelper helper) {
        run(helper, "other", true);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void qioRefusedFrames(GameTestHelper helper) {
        frames(helper);
    }
}
