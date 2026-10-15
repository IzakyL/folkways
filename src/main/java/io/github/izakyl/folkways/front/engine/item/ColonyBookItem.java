package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.charter.CurrentColony;
import io.github.izakyl.folkways.front.engine.charter.LegacyBooks;
import io.github.izakyl.folkways.front.engine.colony.ColonyBoards;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysDataComponents;
import io.github.izakyl.folkways.front.ui.screen.ColonyShell;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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

// The book is how a player reaches the colony they act for, not what lets them: any colony book opens the panel and
// marks the world for whichever colony the player has chosen, if they keep it.
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
        LegacyBooks.takeUp(serverPlayer, stack);
        // A block the colony keeps a board for opens on that board; anything else opens the colony's own panel.
        BlockPos at = context.getClickedPos();
        if (CurrentColony.of(serverPlayer)
                .flatMap(colony -> ColonyBoards.firstAt(serverLevel, colony, at)).isPresent()) {
            ColonyShell.openAt(serverPlayer, at);
        } else {
            ColonyShell.open(serverPlayer);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            LegacyBooks.takeUp(serverPlayer, stack);
            ColonyShell.open(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    public static Shape.Gesture gesture(ItemStack stack) {
        Shape.Gesture written = stack.get(FolkwaysDataComponents.BOOK_GESTURE.get());
        return written == null ? BookGestures.DEFAULT : written;
    }

    public static void setGesture(ItemStack stack, Shape.Gesture gesture) {
        stack.set(FolkwaysDataComponents.BOOK_GESTURE.get(), gesture);
    }

    public static Optional<ItemStack> heldBook(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof ColonyBookItem) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

    // A book in hand, while the player acts for that colony.
    public static boolean holdsBookFor(Player player, UUID colonyId) {
        return heldBook(player).isPresent() && CurrentColony.id(player).filter(colonyId::equals).isPresent();
    }
}
