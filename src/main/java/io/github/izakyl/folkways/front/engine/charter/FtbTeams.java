package io.github.izakyl.folkways.front.engine.charter;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

// FTB Teams, read through its API only; nothing here is loaded unless the mod is.
public final class FtbTeams implements Teams.Source {

    public static final String MOD_ID = "ftbteams";

    // A party gaining or losing a member, changing hands or breaking up can change who keeps the colonies it was
    // given, so everyone online settles the colony they act for again.
    public FtbTeams() {
        TeamEvent.PLAYER_JOINED_PARTY.register(event -> settle());
        TeamEvent.PLAYER_LEFT_PARTY.register(event -> settle());
        TeamEvent.OWNERSHIP_TRANSFERRED.register(event -> settle());
        TeamEvent.DELETED.register(event -> settle());
    }

    private static void settle() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> CurrentColony.refreshAll(server));
        }
    }

    @Override
    public Optional<Teams.Heard> partyOf(ServerPlayer player) {
        if (!FTBTeamsAPI.api().isManagerLoaded()) {
            return Optional.empty();
        }
        return FTBTeamsAPI.api().getManager().getTeamForPlayer(player)
            .filter(Team::isPartyTeam)
            .map(FtbTeams::heard);
    }

    @Override
    public Optional<Teams.Heard> team(MinecraftServer server, UUID id) {
        if (!FTBTeamsAPI.api().isManagerLoaded()) {
            return Optional.empty();
        }
        return FTBTeamsAPI.api().getManager().getTeamByID(id)
            .filter(Team::isValid)
            .filter(Team::isPartyTeam)
            .map(FtbTeams::heard);
    }

    private static Teams.Heard heard(Team team) {
        List<UUID> members = team.getMembers().stream()
            .filter(player -> team.getRankForPlayer(player).isMemberOrBetter())
            .sorted()
            .toList();
        return new Teams.Heard(team.getId(), team.getName(), team.getOwner(), members);
    }
}
