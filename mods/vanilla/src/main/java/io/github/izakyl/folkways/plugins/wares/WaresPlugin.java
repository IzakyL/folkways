package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.ui.RegisteringUi;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

public final class WaresPlugin {

    private WaresPlugin() {
    }

    public static void install(IEventBus modBus) {
        WaresBlockEntities.register(modBus);
        modBus.addListener(Declaring.class, WaresContent::declare);
        modBus.addListener(Registering.class, WaresContent::enroll);
        modBus.addListener(RegisteringUi.class, event -> event.outline(WaresPlugin::otherHalf));
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(WorkshopScene::declare);
        }
    }

    // A marked double chest is kept at one half and outlined with the other.
    static Optional<BlockPos> otherHalf(BlockGetter level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return Optional.empty();
        }
        BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
        return level.getBlockState(other).is(state.getBlock()) ? Optional.of(other) : Optional.empty();
    }
}
