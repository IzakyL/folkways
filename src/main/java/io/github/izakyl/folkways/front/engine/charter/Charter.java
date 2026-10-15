package io.github.izakyl.folkways.front.engine.charter;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

// Who keeps a colony: the players who may act for it, and the one among them who founded it and alone may end it,
// turn a member out or hand the founding on. When a team is named, the team says who they are, and what is written
// here is the last word it gave, kept for when the team is gone. Players asked in are not keepers until they agree.
public record Charter(String name, Optional<UUID> founder, List<UUID> members, Optional<UUID> team,
        List<Invitation> invited) {

    public static final int MAX_NAME = 32;

    public static final Charter UNCLAIMED =
        new Charter("", Optional.empty(), List.of(), Optional.empty(), List.of());

    private static final String TAG_NAME = "name";
    private static final String TAG_FOUNDER = "founder";
    private static final String TAG_MEMBERS = "members";
    private static final String TAG_TEAM = "team";
    private static final String TAG_INVITED = "invited";
    private static final String TAG_PLAYER = "player";
    private static final String TAG_BY = "by";
    private static final String TAG_TOLD = "told";

    // A player asked in by a keeper; told once they have heard of it in chat.
    public record Invitation(UUID player, UUID by, boolean told) {

        CompoundTag save() {
            return Writer.of().uuid(TAG_PLAYER, player).uuid(TAG_BY, by).flag(TAG_TOLD, told).tag();
        }

        static Optional<Invitation> load(Reader reader) {
            Optional<UUID> player = reader.uuid(TAG_PLAYER);
            Optional<UUID> by = reader.uuid(TAG_BY);
            return player.isEmpty() || by.isEmpty() ? Optional.empty()
                : Optional.of(new Invitation(player.get(), by.get(), reader.flag(TAG_TOLD).orElse(false)));
        }
    }

    public Charter {
        members = List.copyOf(members);
        invited = List.copyOf(invited);
    }

    public static Charter founded(String name, UUID founder) {
        return new Charter(name, Optional.of(founder), List.of(founder), Optional.empty(), List.of());
    }

    // No founder and no members: the first player to use a book bound to it takes it up.
    public boolean unclaimed() {
        return founder.isEmpty() && members.isEmpty();
    }

    public boolean member(UUID player) {
        return members.contains(player);
    }

    public boolean founder(UUID player) {
        return founder.filter(player::equals).isPresent();
    }

    public Optional<Invitation> invitation(UUID player) {
        return invited.stream().filter(invitation -> invitation.player().equals(player)).findFirst();
    }

    public Charter named(String to) {
        return new Charter(to, founder, members, team, invited);
    }

    // Joining answers the player's invitation, if there was one.
    public Charter with(UUID player) {
        List<UUID> more = new ArrayList<>(members);
        if (!more.contains(player)) {
            more.add(player);
        }
        return new Charter(name, founder, more, team, uninvited(player).invited);
    }

    // A founder who leaves hands the founding to the member who has kept the colony longest.
    public Charter without(UUID player) {
        List<UUID> fewer = new ArrayList<>(members);
        fewer.remove(player);
        Optional<UUID> next = founder.filter(found -> !found.equals(player))
            .or(() -> fewer.stream().findFirst());
        return new Charter(name, next, fewer, team, invited);
    }

    public Charter foundedBy(UUID player) {
        Charter joined = with(player);
        return new Charter(name, Optional.of(player), joined.members, team, joined.invited);
    }

    public Charter inviting(UUID player, UUID by) {
        List<Invitation> more = new ArrayList<>(uninvited(player).invited);
        more.add(new Invitation(player, by, false));
        return new Charter(name, founder, members, team, more);
    }

    public Charter uninvited(UUID player) {
        List<Invitation> fewer = invited.stream()
            .filter(invitation -> !invitation.player().equals(player))
            .toList();
        return new Charter(name, founder, members, team, fewer);
    }

    public Charter told(UUID player) {
        List<Invitation> marked = invited.stream()
            .map(invitation -> invitation.player().equals(player)
                ? new Invitation(invitation.player(), invitation.by(), true) : invitation)
            .toList();
        return new Charter(name, founder, members, team, marked);
    }

    // Given to a team, the colony asks no one in itself: whoever it had asked is asked no longer.
    public Charter keptBy(Optional<UUID> to) {
        return new Charter(name, founder, members, to, to.isPresent() ? List.of() : invited);
    }

    // What the team says now, written over what it said last.
    public Charter heard(UUID owner, List<UUID> heard) {
        return new Charter(name, Optional.of(owner), heard, team, invited);
    }

    public CompoundTag save() {
        return Writer.of()
            .string(TAG_NAME, name)
            .uuid(TAG_FOUNDER, founder)
            .uuids(TAG_MEMBERS, members)
            .uuid(TAG_TEAM, team)
            .children(TAG_INVITED, invited, Invitation::save)
            .tag();
    }

    public static Charter load(CompoundTag tag) {
        Reader reader = Reader.of(tag);
        return new Charter(reader.string(TAG_NAME).orElse(""), reader.uuid(TAG_FOUNDER),
            reader.uuids(TAG_MEMBERS), reader.uuid(TAG_TEAM),
            reader.children(TAG_INVITED).stream().flatMap(child -> Invitation.load(child).stream()).toList());
    }
}
