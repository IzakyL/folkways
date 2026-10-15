package io.github.izakyl.folkways.front.engine.charter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

// A teams mod a colony's keepers can be handed to. Without one installed there are no teams, and every colony keeps
// its own list.
public final class Teams {

    // What a team says of itself now: its name, who owns it, and who is in it as a member or above.
    public record Heard(UUID id, Component name, UUID owner, List<UUID> members) {
    }

    public interface Source {

        // The party a player is in; a team of one, that each player has to themselves, is not one.
        Optional<Heard> partyOf(ServerPlayer player);

        // Empty when the team is gone.
        Optional<Heard> team(MinecraftServer server, UUID team);
    }

    private static Optional<Source> installed = Optional.empty();

    private Teams() {
    }

    public static void install(Source source) {
        installed = Optional.of(source);
    }

    public static boolean present() {
        return installed.isPresent();
    }

    public static Optional<Heard> partyOf(ServerPlayer player) {
        return installed.flatMap(source -> source.partyOf(player));
    }

    public static Optional<Heard> team(MinecraftServer server, UUID team) {
        return installed.flatMap(source -> source.team(server, team));
    }
}
