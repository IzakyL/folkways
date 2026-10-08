package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Notice;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** The book, in member mode, pointed at any piece of a package network: the colony takes the whole network on or gives it back. */
final class NetworkHandover {

    private NetworkHandover() {
    }

    static Attempt toggle(ServerPlayer player, ServerLevel level, DispatchPresence ours, Optional<UUID> pointed) {
        if (pointed.isEmpty()) {
            return Attempt.refused(DispatchRefusal.NO_NETWORK);
        }
        UUID network = pointed.get();
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
        Optional<PackageNetwork.Flaw> flaw = PackageNetworks.get().flawOf(level, network);
        if (flaw.isPresent()) {
            return Attempt.refused(flaw.get().kind(), Notice.text(flaw.get().at().toShortString()));
        }
        ours.take(network);
        say(player, "folkways.dispatch.taken_on", ChatFormatting.GREEN);
        return Attempt.went();
    }

    private static void say(ServerPlayer player, String key, ChatFormatting colour) {
        player.displayClientMessage(Component.translatable(key).withStyle(colour), true);
    }
}
