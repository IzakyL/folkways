package io.github.izakyl.folkways.war;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

final class RaidInstance {
    enum Phase { WARNING, WAVES, VICTORY, DEFEAT, WITHDRAWN, CANCELLED }
    record Member(UUID id, String role, boolean dead) { }

    final UUID id;
    final long seed;
    final WorldPos target;
    final WorldPos origin;
    final RaidDefinition definition;
    final List<Member> members = new ArrayList<>();
    Phase phase = Phase.WARNING;
    int wave;
    int wait;
    int elapsed;
    boolean paused;

    RaidInstance(UUID id, long seed, WorldPos target, WorldPos origin, RaidDefinition definition, int warning) {
        this.id = id;
        this.seed = seed;
        this.target = target;
        this.origin = origin;
        this.definition = definition;
        wait = warning;
    }

    boolean active() { return phase == Phase.WARNING || phase == Phase.WAVES; }
    int remaining() { return (int) members.stream().filter(member -> !member.dead()).count(); }
    void died(UUID entity) {
        members.replaceAll(member -> member.id().equals(entity) ? new Member(entity, member.role(), true) : member);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id); tag.putLong("seed", seed);
        tag.put("target", target.save()); tag.put("origin", origin.save());
        tag.putString("definition", definition.snapshot()); tag.putString("phase", phase.name());
        tag.putInt("wave", wave); tag.putInt("wait", wait); tag.putInt("elapsed", elapsed);
        ListTag saved = new ListTag();
        for (Member member : members) {
            CompoundTag row = new CompoundTag();
            row.putUUID("id", member.id()); row.putString("role", member.role()); row.putBoolean("dead", member.dead());
            saved.add(row);
        }
        tag.put("members", saved);
        return tag;
    }

    static RaidInstance load(CompoundTag tag) {
        RaidInstance raid = new RaidInstance(tag.getUUID("id"), tag.getLong("seed"),
            WorldPos.load(Reader.of(tag.getCompound("target"))).orElseThrow(),
            WorldPos.load(Reader.of(tag.getCompound("origin"))).orElseThrow(),
            RaidDefinition.parse(tag.getString("definition")), 0);
        raid.phase = Phase.valueOf(tag.getString("phase"));
        raid.wave = tag.getInt("wave"); raid.wait = Math.max(0, tag.getInt("wait")); raid.elapsed = Math.max(0, tag.getInt("elapsed"));
        if (raid.wave < 0 || raid.wave > raid.definition.waves().size()) { throw new IllegalArgumentException("Invalid saved raid wave"); }
        for (Tag value : tag.getList("members", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            raid.members.add(new Member(row.getUUID("id"), row.getString("role"), row.getBoolean("dead")));
        }
        return raid;
    }
}
