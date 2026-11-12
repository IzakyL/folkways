package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.PackSpace;
import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class PackSnapshotGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PackSnapshotGameTests.class);
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void transferSnapshotPreservesUpgradedStacksAndSlotLimits(GameTestHelper helper) {
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        helper.getLevel().addFreshEntity(body);
        try {
            ItemStack bag = new ItemStack(ModItems.BACKPACK.get());
            var wrapper = BackpackWrapper.fromStack(bag);
            wrapper.onInit(helper.getLevel());
            wrapper.getUpgradeHandler().setStackInSlot(0,
                PortChecks.item("sophisticatedbackpacks:stack_upgrade_tier_1"));
            wrapper.getInventoryHandler().setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 128));
            body.setItemSlot(EquipmentSlot.CHEST, bag);
            var pack = body.pack();
            int nativeSlot = pack.ownCells();
            helper.assertTrue(pack.slotLimit(nativeSlot, new ItemStack(Items.COBBLESTONE)) >= 128,
                "the real upgraded native slot holds over one vanilla stack");
            var snapshot = TransferNode.snapshot(pack);
            helper.assertTrue(snapshot.getItem(nativeSlot).getCount() == 128,
                "snapshot preserves every existing native item");
            for (int slot = 0; slot < pack.ownCells(); slot++) {
                snapshot.setItem(slot, new ItemStack(Items.DIRT, 64));
            }
            snapshot.getItem(nativeSlot).shrink(64);
            helper.assertTrue(Containers.insert(snapshot, new ItemStack(Items.COBBLESTONE, 16)).isEmpty()
                && snapshot.getItem(nativeSlot).getCount() == 80,
                "snapshot insertion obeys native per-slot capacity");
            helper.assertTrue(pack.getItem(nativeSlot).getCount() == 128, "simulation leaves the pack intact");
        } finally {
            body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void plannerCapacitySurvivesBackpackRemoval(GameTestHelper helper) {
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        helper.getLevel().addFreshEntity(body);
        try {
            ItemStack bag = new ItemStack(ModItems.BACKPACK.get());
            BackpackWrapper.fromStack(bag).onInit(helper.getLevel());
            body.setItemSlot(EquipmentSlot.CHEST, bag);
            PackSpace captured = PackSpace.of(body.pack(), List.of());
            long capacity = captured.capacity(ItemSpec.of(Items.COBBLESTONE));
            body.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            helper.assertTrue(captured.capacity(ItemSpec.of(Items.COBBLESTONE)) == capacity,
                "capacity is independent of the current worn inventory");
            helper.assertTrue(captured.load(Map.of(ItemSpec.of(Items.COBBLESTONE), capacity)) != Integer.MAX_VALUE,
                "background packing uses captured limits after the live cells disappear");
        } finally {
            body.discard();
        }
        helper.succeed();
    }
}
