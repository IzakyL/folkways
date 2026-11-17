package io.github.izakyl.folkways.airline;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import io.github.izakyl.folkways.core.front.ui.screen.Desk;
import io.github.izakyl.folkways.core.front.ui.screen.Window;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

public final class AirportTowerBlock extends Block implements EntityBlock, BlockUIMenuType.BlockUI {
    public static final DirectionProperty FACING =
        BlockStateProperties.HORIZONTAL_FACING;

    public AirportTowerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AirportTower(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : (world, pos, blockState, entity) -> {
            if (entity instanceof AirportTower tower && world instanceof ServerLevel server
                    && world.getGameTime() % 20 == 0) {
                TowerDuty.reconcile(tower, server);
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level instanceof ServerLevel world) {
            if (Airports.validTower(world, pos)) {
                Airports.request(world, pos);
                BlockUIMenuType.openUI(server, pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        Desk desk = new Desk();
        Window window = desk.panel(Window.of("airport", Component.translatable("folkways.airline.airport.title"),
            0, 0, 440, 410, 280, 220));
        window.body().addChildren(new AirportPage(holder.pos).build(holder.player));
        window.onDismiss(() -> {
            if (holder.player instanceof ServerPlayer player) {
                player.closeContainer();
            }
        });
        window.open();
        return new ModularUI(UI.of(desk.root(),
            List.of(StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC),
                StylesheetManager.INSTANCE.getStylesheetSafe(
                    ResourceLocation.fromNamespaceAndPath("folkways_core", "lss/desk.lss"))), screen -> screen),
            holder.player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!state.is(next.getBlock()) && level instanceof ServerLevel server) {
            Airports.remove(server, pos);
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
