package io.github.izakyl.folkways.front.engine.charter;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.engine.net.CurrentColonyPacket;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

// The colony a player is acting for, of those they keep: what any book in hand opens on and what its clicks reach.
// It goes with the player, not the book. It is settled again only when something could change it (the player
// logs in or chooses, a colony's keepers change, a colony ends, a team changes), and the client is told each time.
@EventBusSubscriber(modid = FolkwaysMod.CORE_ID)
public final class CurrentColony {

    // What this client was last told.
    private static volatile Optional<UUID> onClient = Optional.empty();

    // What each player's client was last told.
    private static final Map<ServerPlayer, Optional<UUID>> TOLD = new WeakHashMap<>();

    private CurrentColony() {
    }

    public static Optional<UUID> id(Player player) {
        if (player.level().isClientSide) {
            return onClient;
        }
        return player instanceof ServerPlayer serving ? of(serving).map(Colony::id) : Optional.empty();
    }

    // The chosen colony while the player still keeps it.
    public static Optional<Colony> of(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return chosen(player)
            .filter(id -> !Colonies.razed(server, id))
            .flatMap(id -> Colonies.of(server, id))
            .filter(colony -> Charters.member(server, colony, player.getUUID()));
    }

    public static void choose(ServerPlayer player, Colony colony) {
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        tell(player);
    }

    // A colony the player no longer keeps gives way to one they still do, or to none.
    public static void refresh(ServerPlayer player) {
        if (of(player).isEmpty()) {
            Optional<UUID> next = Charters.joined(player.getServer(), player.getUUID()).stream()
                .findFirst().map(Colony::id);
            if (!next.equals(chosen(player))) {
                player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, next);
            }
        }
        tell(player);
    }

    // Those of the players who are online; the rest settle when they log in.
    public static void refresh(MinecraftServer server, Collection<UUID> players) {
        for (UUID id : players) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                refresh(player);
            }
        }
    }

    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refresh(player);
        }
    }

    public static void heard(Optional<UUID> colony) {
        onClient = colony;
    }

    public static void forgetOnClient() {
        onClient = Optional.empty();
    }

    private static Optional<UUID> chosen(ServerPlayer player) {
        return player.getData(FolkwaysPlayerAttachments.CURRENT_COLONY);
    }

    private static void tell(ServerPlayer player) {
        Optional<UUID> now = of(player).map(Colony::id);
        if (now.equals(TOLD.get(player))) {
            return;
        }
        TOLD.put(player, now);
        PacketDistributor.sendToPlayer(player, new CurrentColonyPacket(now));
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TOLD.remove(player);
            LegacyBooks.takeUpCarried(player);
            refresh(player);
            Invitations.tellPending(player);
        }
    }

    // A respawned player is a new entity, whose client has not been told yet.
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            refresh(player);
        }
    }
}
