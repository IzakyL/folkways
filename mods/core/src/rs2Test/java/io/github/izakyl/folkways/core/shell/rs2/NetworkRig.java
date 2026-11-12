package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.content.Blocks;
import com.refinedmods.refinedstorage.common.content.DataComponents;
import com.refinedmods.refinedstorage.common.storage.AbstractDiskContainerBlockEntity;
import com.refinedmods.refinedstorage.common.storage.StorageTypes;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class NetworkRig {

    static final BlockPos POWER = new BlockPos(2, 2, 2);
    static final BlockPos DRIVE = new BlockPos(3, 2, 2);
    static final BlockPos CABLE = new BlockPos(4, 2, 2);
    static final BlockPos TERMINAL = new BlockPos(5, 2, 2);
    static final BlockPos OTHER = new BlockPos(6, 2, 2);
    private final GameTestHelper helper;

    NetworkRig(GameTestHelper helper) {
        this(helper, 1024L);
    }

    NetworkRig(GameTestHelper helper, long capacity) {
        this.helper = helper;
        helper.setBlock(POWER, Blocks.INSTANCE.getCreativeController().getDefault());
        helper.setBlock(DRIVE, Blocks.INSTANCE.getDiskDrive());
        var repository = RefinedStorageApi.INSTANCE.getStorageRepository(helper.getLevel());
        UUID diskId = UUID.randomUUID();
        repository.set(diskId, StorageTypes.ITEM.create(capacity, repository::markAsChanged));
        ItemStack disk = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("refinedstorage", "1k_storage_disk")));
        disk.set(DataComponents.INSTANCE.getStorageReference(), diskId);
        var drive = (AbstractDiskContainerBlockEntity<?>) helper.getLevel().getBlockEntity(helper.absolutePos(DRIVE));
        drive.getDiskInventory().setItem(0, disk);
        drive.setChanged();
        helper.setBlock(CABLE, Blocks.INSTANCE.getCable().getDefault());
        helper.setBlock(TERMINAL, Blocks.INSTANCE.getCraftingGrid().getDefault());
        helper.setBlock(OTHER, Blocks.INSTANCE.getGrid().getDefault());
    }

    NetworkPort port() {
        NetworkPort port = Devices.at(helper.getLevel(), helper.absolutePos(TERMINAL)).orElseThrow();
        helper.assertTrue(port.usability().usable(), "minimal Refined Storage network is powered: " + port.usability());
        return port;
    }

    void stock(Item item, long count) {
        NetworkPort port = port();
        long inserted = port.storage().insert(new ItemResource(item), count, Action.EXECUTE,
            Devices.source(port.node()));
        helper.assertTrue(inserted == count, "fixture storage accepted its stock: " + item
                + ", requested=" + count + ", inserted=" + inserted
                + ", room=" + port.room() + ", stock=" + port.storage().getAll());
    }

    long count(Item item) {
        return port().storage().get(new ItemResource(item));
    }
}
