package io.github.izakyl.folkways.core.shell.mekanism;

import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.shell.storage.NetworkChecks;
import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class QioGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(QioGameTests.class);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 200)
    public static void boundedReadingSurvivesKindsChangingBetweenSteps(GameTestHelper helper) {
        QioRig rig = new QioRig(helper);
        helper.startSequence().thenIdle(5).thenExecute(rig::connect).thenIdle(30).thenExecute(() -> {
            rig.frequency().massInsert(new ItemStack(Items.STONE), 3, mekanism.api.Action.EXECUTE);
            rig.frequency().massInsert(new ItemStack(Items.DIRT), 5, mekanism.api.Action.EXECUTE);
            var reader = PortChecks.at(helper, QioRig.DASHBOARD).reader(helper.getLevel(), List.of());
            helper.assertTrue(!reader.step(1), "reading spans multiple steps");
            rig.frequency().massInsert(new ItemStack(Items.SAND), 7, mekanism.api.Action.EXECUTE);
            helper.assertTrue(reader.step(64), "native kind additions cannot invalidate the reader");
            helper.assertTrue(reader.result().entries().size() == 2
                && reader.result().entries().stream().mapToLong(entry -> entry.count()).sum() == 8,
                "both keys and counts belong to the captured snapshot");
        }).thenSucceed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 250)
    public static void dashboardsShareFrequencyAndDrivesWithoutDoubleCount(GameTestHelper helper) {
        QioRig rig = new QioRig(helper);
        helper.startSequence().thenIdle(5).thenExecute(rig::connect).thenIdle(30).thenExecute(() -> {
            var port = PortChecks.at(helper, QioRig.DASHBOARD);
            helper.assertTrue(port.usability().usable(),
                "frequency with a real native drive is usable: " + port.usability());
            helper.assertTrue(port.identity().key().equals(PortChecks.at(helper, QioRig.OTHER).identity().key()),
                "every dashboard on the frequency is an alias");
            for (BlockPos pos : List.of(QioRig.DASHBOARD, QioRig.OTHER)) {
                var access = PortChecks.at(helper, pos);
                helper.assertTrue(access.put(helper.getLevel(), List.of(new ItemStack(Items.STONE, 17)), List.of(),
                    false, true).done() && PortChecks.count(helper, access) == 0, "simulation leaves QIO unchanged");
                helper.assertTrue(access.put(helper.getLevel(), List.of(new ItemStack(Items.STONE, 17)), List.of(),
                    false, false).done(), "put through each dashboard");
                helper.assertTrue(access.take(helper.getLevel(), List.of(
                    new Pull.Exact(new ItemStack(Items.STONE, 17))),
                    List.of(), false).done(), "take through each dashboard");
            }
            port.put(helper.getLevel(), List.of(new ItemStack(Items.STONE, 23)), List.of(), false, false);
            NetworkChecks.observed(helper, List.of(QioRig.DASHBOARD, QioRig.OTHER), 1, 23);
        });
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 200)
    public static void perDriveRoomAndBatchReservationsDoNotOverpromise(GameTestHelper helper) {
        QioRig rig = new QioRig(helper);
        rig.customDrives();
        helper.startSequence().thenIdle(5).thenExecute(rig::connect).thenIdle(30).thenExecute(() -> {
            var port = PortChecks.at(helper, QioRig.DASHBOARD);
            var room = (Room.Pool)
                PortChecks.read(helper, port).room();
            helper.assertTrue(room.items() == 1010 && room.newKinds() == 1,
                "counts are pooled but distinct type limits are reserved per drive: " + room);
            helper.assertTrue(!port.put(helper.getLevel(), List.of(new ItemStack(Items.STONE, 8),
                new ItemStack(Items.DIRT, 8), new ItemStack(Items.SAND, 8)), List.of(), false, false).done()
                && PortChecks.count(helper, port) == 0, "unsupported multi-kind batch fails without partial writes");
            rig.frequency().massInsert(new ItemStack(Items.STONE), 5, mekanism.api.Action.EXECUTE);
            room = (Room.Pool) PortChecks.read(helper, port).room();
            helper.assertTrue(room.newKinds() == 0, "type-full drive cannot promise a new kind");
            rig.frequency().massInsert(new ItemStack(Items.DIRT), 1, mekanism.api.Action.EXECUTE);
            room = (Room.Pool) PortChecks.read(helper, port).room();
            helper.assertTrue(room.items() <= 999 && room.newKinds() == 0,
                "free counts on a type-full drive cannot promise arbitrary top-ups of remote kinds: " + room);
            long count = PortChecks.count(helper, port);
            helper.assertTrue(!port.put(helper.getLevel(), List.of(new ItemStack(Items.STONE, 600),
                new ItemStack(Items.DIRT, 600)), List.of(), false, false).done()
                && PortChecks.count(helper, port) == count, "shared item count reservations are atomic");
            helper.assertTrue(!port.take(helper.getLevel(), List.of(
                new Pull.Exact(new ItemStack(Items.STONE, 4)),
                new Pull.Exact(new ItemStack(Items.STONE, 4))),
                List.of(), false).done() && PortChecks.count(helper, port) == count, "overlapping takes reserve stock");
            Object oldIdentity = port.identity().key();
            rig.array().getDriveSlots().get(1).setStack(ItemStack.EMPTY);
            helper.assertTrue(!oldIdentity.equals(port.identity().key())
                && port.identity().key().equals(PortChecks.at(helper, QioRig.OTHER).identity().key()),
                "live identities agree after drive removal");
        }).thenSucceed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void automationAndDriveSlotsNeverGrantHandAccess(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 2);
        for (String block : List.of("qio_importer", "qio_exporter", "qio_redstone_adapter")) {
            PortChecks.place(helper, pos, "mekanism:" + block);
            NetworkChecks.refused(helper, pos, "buffer");
        }
        PortChecks.place(helper, pos, "mekanism:qio_drive_array");
        NetworkChecks.refused(helper, pos, "hand_access");
        PortChecks.place(helper, pos, "mekanism:qio_dashboard");
        NetworkChecks.refused(helper, pos, "disconnected");
        helper.succeed();
    }
}
