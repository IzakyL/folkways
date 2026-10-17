package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.front.api.notice.Attempt;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The book, in member mode, pointed at a stock link or stock ticker: the colony takes that network on or gives it back;
 * or pointed at a frogport or chain conveyor: the same for the whole chain net. A network is taken on however it ships,
 * and orders nothing until what it ships by is the colony's too.
 */
final class NetworkHandover {

    private NetworkHandover() {
    }

    static Attempt toggle(ServerPlayer player, ServerLevel level, DispatchPresence ours, UUID network) {
        if (!PackageNetworks.get().mayAdministrate(level.getServer(), network, player)) {
            return Attempt.refused(DispatchRefusal.NOT_YOURS);
        }
        Optional<DispatchPresence> holder = DispatchContent.holderOf(level.getServer(), network);
        if (holder.isPresent() && holder.get() != ours) {
            return Attempt.refused(DispatchRefusal.ANOTHER_COLONY);
        }
        if (ours.holds(network)) {
            ours.release(network);
            say(player, "folkways.dispatch.given_back", ChatFormatting.WHITE);
            return Attempt.went();
        }
        ours.take(network);
        say(player, "folkways.dispatch.taken_on", ChatFormatting.GREEN);
        return Attempt.went();
    }

    static Attempt toggleChain(ServerPlayer player, ServerLevel level, DispatchPresence ours, BlockPos chain) {
        Optional<DispatchPresence> holder = DispatchContent.chainHolderOf(level, chain);
        if (holder.isPresent() && holder.get() != ours) {
            return Attempt.refused(DispatchRefusal.CHAIN_ELSEWHERE);
        }
        if (ours.holdsChain(level, chain)) {
            ours.releaseChain(level, chain);
            say(player, "folkways.dispatch.chain_given_back", ChatFormatting.WHITE);
            return Attempt.went();
        }
        ours.takeChain(level, chain);
        say(player, "folkways.dispatch.chain_taken_on", ChatFormatting.GREEN);
        return Attempt.went();
    }

    private static void say(ServerPlayer player, String key, ChatFormatting colour) {
        player.displayClientMessage(Component.translatable(key).withStyle(colour), true);
    }
}
