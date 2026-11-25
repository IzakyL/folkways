package io.github.izakyl.folkways.core.shell.colossalchests;

import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

public final class ChestRig {

    public static final BlockPos CORE = new BlockPos(3, 1, 2);
    public static final BlockPos WALL = CORE.east();
    public static final BlockPos INTERFACE = CORE.above();

    public ChestRig(GameTestHelper helper) {
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos at = CORE.offset(x, y, z);
                    if (!at.equals(CORE)) {
                        PortChecks.place(helper, at, "colossalchests:chest_wall_wood");
                    }
                }
            }
        }
        PortChecks.place(helper, INTERFACE, "colossalchests:interface_wood");
        PortChecks.place(helper, CORE, "colossalchests:colossal_chest_wood");
    }
}
