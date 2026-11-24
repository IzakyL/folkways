package io.github.izakyl.folkways.core.shell.fxntstorage;

import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import net.fxnt.fxntstorage.controller.StorageControllerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

public final class StorageRig {

    public static final BlockPos MEMBER = new BlockPos(1, 1, 2);
    public static final BlockPos CONTROLLER = new BlockPos(9, 1, 2);

    public StorageRig(GameTestHelper helper) {
        PortChecks.place(helper, MEMBER, "fxntstorage:oak_simple_storage_box");
        for (int x = 2; x < 9; x++) {
            PortChecks.place(helper, new BlockPos(x, 1, 2), "fxntstorage:oak_storage_trim");
        }
        var controller = (StorageControllerEntity) PortChecks.place(helper, CONTROLLER,
            "fxntstorage:storage_controller");
        controller.getStorageNetwork();
    }
}
