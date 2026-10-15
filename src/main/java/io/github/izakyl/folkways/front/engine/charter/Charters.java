package io.github.izakyl.folkways.front.engine.charter;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

// The colony's charter as it stands: read from the colony, and from its team when one keeps it.
public final class Charters {

    public static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "charter");

    private Charters() {
    }

    public static Charter of(MinecraftServer server, Colony colony) {
        Charter written = written(colony);
        if (written.team().isEmpty()) {
            return written;
        }
        Optional<Teams.Heard> heard = Teams.team(server, written.team().get());
        if (heard.isEmpty()) {
            // The team is gone, or its mod is: the colony keeps itself again, with the last keepers it heard of.
            return written;
        }
        Charter now = written.heard(heard.get().owner(), heard.get().members());
        if (!now.founder().equals(written.founder())
                || !new HashSet<>(now.members()).equals(new HashSet<>(written.members()))) {
            keep(colony, now);
        }
        return now;
    }

    // As kept, without asking the team.
    public static Charter written(Colony colony) {
        return Charter.load(colony.kept(OWNER));
    }

    public static void keep(Colony colony, Charter charter) {
        colony.keep(OWNER, charter.save());
    }

    // The team that keeps it, if it still stands.
    public static Optional<Teams.Heard> team(MinecraftServer server, Colony colony) {
        return written(colony).team().flatMap(team -> Teams.team(server, team));
    }

    public static boolean member(MinecraftServer server, Colony colony, UUID player) {
        return of(server, colony).member(player);
    }

    public static boolean founder(MinecraftServer server, Colony colony, UUID player) {
        return of(server, colony).founder(player);
    }

    // Every colony the player keeps, oldest first.
    public static List<Colony> joined(MinecraftServer server, UUID player) {
        return Colonies.all(server).stream()
            .filter(colony -> !Colonies.razed(server, colony.id()))
            .filter(colony -> member(server, colony, player))
            .toList();
    }

    public static Colony found(ServerPlayer player, String name) {
        Colony colony = Colonies.mint(player.getServer());
        keep(colony, Charter.founded(name, player.getUUID()));
        return colony;
    }

    // What the colony is called: the name its keepers gave it, or its founder's colony until they give one.
    public static Component title(MinecraftServer server, Charter charter) {
        if (!charter.name().isBlank()) {
            return Component.literal(charter.name());
        }
        return charter.founder()
            .map(founder -> Component.translatable("folkways.charter.unnamed", nameOf(server, founder)))
            .orElseGet(() -> Component.translatable("folkways.panel.title"));
    }

    public static String nameOf(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return Optional.ofNullable(server.getProfileCache())
            .flatMap(cache -> cache.get(player))
            .map(GameProfile::getName)
            .orElseGet(() -> player.toString().substring(0, 8));
    }

    public static Optional<GameProfile> profileNamed(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        return Optional.ofNullable(server.getProfileCache()).flatMap(cache -> cache.get(name));
    }

    public static Optional<CharterRefusal> rename(MinecraftServer server, Colony colony, UUID by, String typed) {
        Charter charter = of(server, colony);
        if (!charter.member(by)) {
            return Optional.of(CharterRefusal.NOT_MEMBER);
        }
        String name = typed.trim();
        if (name.isEmpty() || name.length() > Charter.MAX_NAME) {
            return Optional.of(CharterRefusal.BAD_NAME);
        }
        keep(colony, charter.named(name));
        return Optional.empty();
    }

    // Any keeper may ask another in, while the colony keeps its own list; they keep it once they agree.
    public static Optional<CharterRefusal> invite(MinecraftServer server, Colony colony, UUID by, String typed) {
        Charter charter = of(server, colony);
        if (!charter.member(by)) {
            return Optional.of(CharterRefusal.NOT_MEMBER);
        }
        if (charter.team().isPresent()) {
            return Optional.of(CharterRefusal.KEPT_BY_TEAM);
        }
        Optional<GameProfile> who = profileNamed(server, typed.trim());
        if (who.isEmpty()) {
            return Optional.of(CharterRefusal.NO_SUCH_PLAYER);
        }
        UUID asked = who.get().getId();
        if (charter.member(asked)) {
            return Optional.of(CharterRefusal.ALREADY_MEMBER);
        }
        if (charter.invitation(asked).isPresent()) {
            return Optional.of(CharterRefusal.ALREADY_INVITED);
        }
        keep(colony, charter.inviting(asked, by));
        Invitations.tell(server, colony, asked);
        return Optional.empty();
    }

    public static Optional<CharterRefusal> accept(MinecraftServer server, Colony colony, UUID by) {
        Charter charter = of(server, colony);
        if (charter.invitation(by).isEmpty() || Colonies.razed(server, colony.id())) {
            return Optional.of(CharterRefusal.NOT_INVITED);
        }
        if (charter.team().isPresent()) {
            return Optional.of(CharterRefusal.KEPT_BY_TEAM);
        }
        change(server, colony, charter, charter.with(by));
        return Optional.empty();
    }

    public static Optional<CharterRefusal> decline(MinecraftServer server, Colony colony, UUID by) {
        Charter charter = of(server, colony);
        if (charter.invitation(by).isEmpty()) {
            return Optional.of(CharterRefusal.NOT_INVITED);
        }
        keep(colony, charter.uninvited(by));
        return Optional.empty();
    }

    // Any keeper may take back an invitation not yet answered.
    public static Optional<CharterRefusal> withdraw(MinecraftServer server, Colony colony, UUID by, UUID whom) {
        Charter charter = of(server, colony);
        if (!charter.member(by)) {
            return Optional.of(CharterRefusal.NOT_MEMBER);
        }
        if (charter.invitation(whom).isEmpty()) {
            return Optional.of(CharterRefusal.NOT_INVITED);
        }
        keep(colony, charter.uninvited(whom));
        return Optional.empty();
    }

    // Every standing colony that has asked the player in, oldest first.
    public static List<Colony> invitations(MinecraftServer server, UUID player) {
        return Colonies.all(server).stream()
            .filter(colony -> !Colonies.razed(server, colony.id()))
            .filter(colony -> written(colony).invitation(player).isPresent())
            .toList();
    }

    // Writes a change to who keeps the colony, and settles the colony each player it touches acts for.
    private static void change(MinecraftServer server, Colony colony, Charter before, Charter after) {
        keep(colony, after);
        Set<UUID> touched = new HashSet<>(before.members());
        touched.addAll(after.members());
        CurrentColony.refresh(server, touched);
    }

    public static Optional<CharterRefusal> dismiss(MinecraftServer server, Colony colony, UUID by, UUID whom) {
        Charter charter = of(server, colony);
        if (charter.team().isPresent()) {
            return Optional.of(CharterRefusal.KEPT_BY_TEAM);
        }
        if (!charter.founder(by) || by.equals(whom)) {
            return Optional.of(CharterRefusal.FOUNDER_ONLY);
        }
        if (!charter.member(whom)) {
            return Optional.of(CharterRefusal.NOT_MEMBER);
        }
        change(server, colony, charter, charter.without(whom));
        return Optional.empty();
    }

    // Leaving hands the founding on; the last keeper cannot leave, only end the colony.
    public static Optional<CharterRefusal> leave(MinecraftServer server, Colony colony, UUID by) {
        Charter charter = of(server, colony);
        if (charter.team().isPresent()) {
            return Optional.of(CharterRefusal.KEPT_BY_TEAM);
        }
        if (!charter.member(by)) {
            return Optional.of(CharterRefusal.NOT_MEMBER);
        }
        if (charter.members().size() <= 1) {
            return Optional.of(CharterRefusal.LAST_MEMBER);
        }
        change(server, colony, charter, charter.without(by));
        return Optional.empty();
    }

    public static Optional<CharterRefusal> handOver(MinecraftServer server, Colony colony, UUID by, UUID to) {
        Charter charter = of(server, colony);
        if (charter.team().isPresent()) {
            return Optional.of(CharterRefusal.KEPT_BY_TEAM);
        }
        if (!charter.founder(by)) {
            return Optional.of(CharterRefusal.FOUNDER_ONLY);
        }
        if (!charter.member(to)) {
            return Optional.of(CharterRefusal.NOT_MEMBER);
        }
        keep(colony, charter.foundedBy(to));
        return Optional.empty();
    }

    // Hands the colony to the founder's party: from then on whoever is in it keeps the colony, and its owner holds
    // what the founder held.
    public static Optional<CharterRefusal> entrust(MinecraftServer server, Colony colony, ServerPlayer by) {
        Charter charter = of(server, colony);
        if (charter.team().isPresent()) {
            return Optional.of(CharterRefusal.KEPT_BY_TEAM);
        }
        if (!charter.founder(by.getUUID())) {
            return Optional.of(CharterRefusal.FOUNDER_ONLY);
        }
        Optional<Teams.Heard> party = Teams.partyOf(by);
        if (party.isEmpty()) {
            return Optional.of(CharterRefusal.NO_PARTY);
        }
        change(server, colony, charter, charter.keptBy(Optional.of(party.get().id()))
            .heard(party.get().owner(), party.get().members()));
        return Optional.empty();
    }

    // Takes the colony back from its team, with the keepers the team last had; its owner's to do, or, the team gone,
    // the founder it last named.
    public static Optional<CharterRefusal> release(MinecraftServer server, Colony colony, UUID by) {
        Charter charter = of(server, colony);
        if (charter.team().isEmpty()) {
            return Optional.empty();
        }
        if (!charter.founder(by)) {
            return Optional.of(CharterRefusal.TEAM_OWNER_ONLY);
        }
        keep(colony, charter.keptBy(Optional.empty()));
        return Optional.empty();
    }
}
