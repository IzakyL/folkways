package io.github.izakyl.folkways.core.shell.create.dispatch;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class PackageNetworkGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PackageNetworkGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void createParcelsAreTakenWholeByTheirOrder(GameTestHelper helper) {
        BlockPos port = new BlockPos(1, 1, 1);
        helper.setBlock(port, BuiltInRegistries.BLOCK.get(
            ResourceLocation.fromNamespaceAndPath("create", "package_frogport")));
        BlockPos at = helper.absolutePos(port);
        PackagePortBlockEntity frogport = (PackagePortBlockEntity) helper.getLevel().getBlockEntity(at);
        ItemStack ordered = PackageItem.containing(List.of(new ItemStack(Items.OAK_LOG, 16)));
        PackageItem.setOrder(ordered, 7, 0, true, 0, true, PackageOrderWithCrafts.empty());
        ItemStack byHand = PackageItem.containing(List.of(new ItemStack(Items.OAK_LOG, 5)));
        frogport.inventory.setStackInSlot(0, byHand);
        frogport.inventory.setStackInSlot(1, ordered);
        PackageNetwork network = new CreatePackageNetwork();
        helper.assertTrue(network.parcelsAt(helper.getLevel(), at).stream().map(PackageNetwork.Parcel::order).toList()
                .equals(List.of(OptionalInt.empty(), OptionalInt.of(7))),
            "a parcel reads back the order it was packed for");
        List<ItemStack> taken = network.collectFrom(helper.getLevel(), at, 7);
        helper.assertTrue(taken.stream().mapToInt(ItemStack::getCount).sum() == 16,
            "a collection takes its order's parcel whole");
        helper.assertTrue(network.parcelsAt(helper.getLevel(), at).size() == 1
                && network.collectFrom(helper.getLevel(), at, 7).isEmpty(),
            "a parcel sent by hand is no order's");
        Optional<List<ItemStack>> cleared = network.clearOne(helper.getLevel(), at, parcel -> parcel.order().isEmpty());
        helper.assertTrue(cleared.isPresent() && cleared.get().getFirst().getCount() == 5
                && network.parcelsAt(helper.getLevel(), at).isEmpty(),
            "a stray is cleared whole");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aFrogportOverAContainerIsNoPickUpPoint(GameTestHelper helper) {
        ResourceLocation frogport = ResourceLocation.fromNamespaceAndPath("create", "package_frogport");
        ResourceLocation postbox = ResourceLocation.fromNamespaceAndPath("create", "white_postbox");
        ResourceLocation packager = ResourceLocation.fromNamespaceAndPath("create", "packager");
        helper.setBlock(new BlockPos(1, 2, 1), BuiltInRegistries.BLOCK.get(frogport));
        helper.setBlock(new BlockPos(3, 2, 1), BuiltInRegistries.BLOCK.get(frogport));
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.BARREL);
        helper.setBlock(new BlockPos(5, 3, 1), BuiltInRegistries.BLOCK.get(frogport));
        helper.setBlock(new BlockPos(5, 2, 1), BuiltInRegistries.BLOCK.get(packager).defaultBlockState()
            .setValue(com.simibubi.create.content.logistics.packager.PackagerBlock.FACING,
                net.minecraft.core.Direction.UP));
        helper.setBlock(new BlockPos(7, 3, 1), BuiltInRegistries.BLOCK.get(frogport));
        helper.setBlock(new BlockPos(7, 2, 1), BuiltInRegistries.BLOCK.get(packager).defaultBlockState()
            .setValue(com.simibubi.create.content.logistics.packager.PackagerBlock.FACING,
                net.minecraft.core.Direction.UP));
        helper.setBlock(new BlockPos(7, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(9, 2, 1), BuiltInRegistries.BLOCK.get(postbox));
        helper.setBlock(new BlockPos(9, 1, 1), Blocks.BARREL);
        var level = helper.getLevel();
        helper.assertFalse(CreateLayout.unpacks(level, helper.absolutePos(new BlockPos(1, 2, 1))),
            "a frogport over nothing keeps what it catches");
        helper.assertTrue(CreateLayout.unpacks(level, helper.absolutePos(new BlockPos(3, 2, 1))),
            "a frogport over a container pushes its parcels into it");
        helper.assertFalse(CreateLayout.unpacks(level, helper.absolutePos(new BlockPos(5, 3, 1))),
            "a packager facing no container has nowhere to unpack");
        helper.assertTrue(CreateLayout.unpacks(level, helper.absolutePos(new BlockPos(7, 3, 1))),
            "a packager facing a container unpacks into it");
        helper.assertFalse(CreateLayout.unpacks(level, helper.absolutePos(new BlockPos(9, 2, 1))),
            "a postbox keeps what it is sent");
        helper.assertTrue(CreateLayout.targetOf(level, helper.absolutePos(new BlockPos(7, 2, 1)))
                .equals(Optional.of(helper.absolutePos(new BlockPos(7, 1, 1)))),
            "a packager's container is the one behind the way it faces");
        helper.succeed();
    }
}
