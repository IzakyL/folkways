package io.github.izakyl.folkways.core.shell.mekanism;

import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import java.util.UUID;
import mekanism.api.security.SecurityMode;
import mekanism.common.content.qio.QIOFrequency;
import mekanism.common.lib.frequency.Frequency.FrequencyIdentity;
import mekanism.common.lib.frequency.FrequencyType;
import mekanism.common.tile.qio.TileEntityQIOComponent;
import mekanism.common.tile.qio.TileEntityQIODriveArray;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.UsernameCache;

public final class QioRig {

    public static final BlockPos ARRAY = new BlockPos(2, 1, 2);
    public static final BlockPos DASHBOARD = new BlockPos(5, 1, 2);
    public static final BlockPos OTHER = new BlockPos(9, 1, 2);
    private final GameTestHelper helper;
    private final UUID owner = UUID.randomUUID();
    private final String name = "folkways_test_" + owner;

    public QioRig(GameTestHelper helper) {
        this.helper = helper;
        PortChecks.place(helper, ARRAY, "mekanism:qio_drive_array");
        PortChecks.place(helper, DASHBOARD, "mekanism:qio_dashboard");
        PortChecks.place(helper, OTHER, "mekanism:qio_dashboard");
        array().getDriveSlots().getFirst().setStack(PortChecks.item("mekanism:qio_drive_base"));
    }

    public void connect() {
        // GameTestServer has no profile cache; native frequency creation first checks this username cache.
        try {
            var remember = UsernameCache.class.getDeclaredMethod("setUsername", UUID.class, String.class);
            remember.setAccessible(true);
            remember.invoke(null, owner, "FolkwaysQioTest");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot initialize the native test owner", failure);
        }
        for (BlockPos pos : java.util.List.of(ARRAY, DASHBOARD, OTHER)) {
            var tile = (TileEntityQIOComponent) helper.getLevel().getBlockEntity(helper.absolutePos(pos));
            tile.getSecurity().setOwnerUUID(owner);
            tile.setFrequency(FrequencyType.QIO, new FrequencyIdentity(name, SecurityMode.PUBLIC, owner), owner);
        }
    }

    public TileEntityQIODriveArray array() {
        return (TileEntityQIODriveArray) helper.getLevel().getBlockEntity(helper.absolutePos(ARRAY));
    }

    public QIOFrequency frequency() {
        return array().getQIOFrequency();
    }

    public void customDrives() {
        array().getDriveSlots().get(0).setStack(new ItemStack(QioFixtures.SMALL));
        array().getDriveSlots().get(1).setStack(new ItemStack(QioFixtures.LARGE));
    }
}
