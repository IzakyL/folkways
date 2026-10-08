package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.colony.ColonyGround;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysDataComponents;
import io.github.izakyl.folkways.front.ui.screen.ColonyShell;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class ColonyBookItem extends Item {
    public ColonyBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        open(serverLevel, stack, serverPlayer);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            open(serverLevel, stack, serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static void open(ServerLevel level, ItemStack stack, ServerPlayer player) {
        liveColony(level, stack, player);
        ColonyShell.open(player);
    }

    public static Optional<Colony> liveColony(ServerLevel level, ItemStack stack, Player player) {
        Optional<UUID> bound = boundColonyId(stack);
        if (bound.isPresent() && ColonyGround.razed(level, bound.get())) {
            stack.remove(FolkwaysDataComponents.COLONY_ID.get());
            player.displayClientMessage(Component.translatable("folkways.colony.razed.book"), true);
            return Optional.empty();
        }
        return bound.flatMap(id -> ColonyGround.of(level.getServer(), id));
    }

    public static Optional<UUID> boundColonyId(ItemStack stack) {
        return Optional.ofNullable(stack.get(FolkwaysDataComponents.COLONY_ID.get()));
    }

    public static Shape.Gesture gesture(ItemStack stack) {
        Shape.Gesture written = stack.get(FolkwaysDataComponents.BOOK_GESTURE.get());
        return written == null ? BookGestures.DEFAULT : written;
    }

    public static void setGesture(ItemStack stack, Shape.Gesture gesture) {
        stack.set(FolkwaysDataComponents.BOOK_GESTURE.get(), gesture);
    }

    public static Optional<ItemStack> heldBookBoundTo(Player player, UUID colonyId) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof ColonyBookItem && boundColonyId(stack).filter(colonyId::equals).isPresent()) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

    public static boolean holdsBookBoundTo(Player player, UUID colonyId) {
        return heldBookBoundTo(player, colonyId).isPresent();
    }

    public static boolean carriesBookBoundTo(Player player, UUID colonyId) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() instanceof ColonyBookItem
                    && boundColonyId(stack).filter(colonyId::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    public static UUID bindNewColony(ItemStack stack, ServerLevel level) {
        UUID minted = ColonyGround.found(level).id();
        stack.set(FolkwaysDataComponents.COLONY_ID.get(), minted);
        return minted;
    }

    public static void linkExistingColony(ItemStack stack, UUID colonyId) {
        stack.set(FolkwaysDataComponents.COLONY_ID.get(), colonyId);
    }

}
