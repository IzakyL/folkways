package io.github.izakyl.folkways.core.api.resident.body;

import io.github.izakyl.folkways.core.api.perk.PerkPool;
import io.github.izakyl.folkways.core.api.perk.Perks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

final class BodyPerks implements Perks {

    private static final int XP_STEP = 32;

    private static final String TAG_VOCATIONS = "vocations";
    private static final String TAG_GLOBAL = "global";
    private static final String TAG_XP = "xp";
    private static final String TAG_LEVEL = "level";
    private static final String TAG_OWNED = "owned";
    private static final String TAG_ID = "id";
    private static final String TAG_RANK = "rank";

    private static final class Track {
        int xp;
        int level;
        final Map<String, Integer> ranks = new LinkedHashMap<>();
    }

    private final Map<ResourceLocation, Track> byVocation = new LinkedHashMap<>();
    private final Track global = new Track();

    public static int xpForLevel(int level) {
        return XP_STEP * level * (level + 1) / 2;
    }

    public static int maxLevel(ResourceLocation vocation) {
        return PerkPool.depth(vocation);
    }

    public static int maxGlobalLevel() {
        return PerkPool.globalDepth();
    }

    public void addXp(ResourceLocation vocation, int amount, RandomSource random) {
        Track track = track(vocation);
        advance(track, amount, maxLevel(vocation),
            () -> PerkPool.vocationCandidates(vocation, track.ranks), random);
        advance(global, amount, maxGlobalLevel(),
            () -> PerkPool.globalCandidates(global.ranks), random);
    }

    private Track track(ResourceLocation vocation) {
        return byVocation.computeIfAbsent(vocation, key -> new Track());
    }

    private static void advance(Track track, int amount, int maxLevel, Supplier<List<String>> candidates,
                                RandomSource random) {
        track.xp = Math.max(0, track.xp + amount);
        int newLevel = levelForXp(track.xp, maxLevel);
        while (track.level < newLevel) {
            track.level++;
            pick(candidates.get(), random).ifPresent(id -> track.ranks.merge(id, 1, Integer::sum));
        }
    }

    private static Optional<String> pick(List<String> candidates, RandomSource random) {
        return candidates.isEmpty()
            ? Optional.empty()
            : Optional.of(candidates.get(random.nextInt(candidates.size())));
    }

    public int level(ResourceLocation vocation) {
        return track(vocation).level;
    }

    public int rank(String perkId) {
        Integer owned = global.ranks.get(perkId);
        if (owned != null) {
            return owned;
        }
        for (Track track : byVocation.values()) {
            Integer drawn = track.ranks.get(perkId);
            if (drawn != null) {
                return drawn;
            }
        }
        return 0;
    }

    public Map<String, Integer> ranks() {
        Map<String, Integer> ranks = new LinkedHashMap<>(global.ranks);
        for (Track track : byVocation.values()) {
            ranks.putAll(track.ranks);
        }
        return Map.copyOf(ranks);
    }

    private static int levelForXp(int xp, int maxLevel) {
        int level = 0;
        while (level < maxLevel && xp >= xpForLevel(level + 1)) {
            level++;
        }
        return level;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag vocations = new CompoundTag();
        byVocation.forEach((vocation, track) -> vocations.put(vocation.toString(), saveTrack(track)));
        tag.put(TAG_VOCATIONS, vocations);
        tag.put(TAG_GLOBAL, saveTrack(global));
        return tag;
    }

    public void load(CompoundTag tag) {
        byVocation.clear();
        CompoundTag vocations = tag.getCompound(TAG_VOCATIONS);
        for (ResourceLocation vocation : PerkPool.vocations()) {
            Track track = new Track();
            loadTrack(track, vocations, vocation.toString(), maxLevel(vocation));
            byVocation.put(vocation, track);
        }
        loadTrack(global, tag, TAG_GLOBAL, maxGlobalLevel());
    }

    private static CompoundTag saveTrack(Track track) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_XP, track.xp);
        tag.putInt(TAG_LEVEL, track.level);
        ListTag owned = new ListTag();
        track.ranks.forEach((id, rank) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString(TAG_ID, id);
            entry.putInt(TAG_RANK, rank);
            owned.add(entry);
        });
        tag.put(TAG_OWNED, owned);
        return tag;
    }

    private static void loadTrack(Track track, CompoundTag parent, String key, int maxLevel) {
        track.xp = 0;
        track.level = 0;
        track.ranks.clear();
        if (!parent.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag tag = parent.getCompound(key);
        track.xp = Math.max(0, tag.getInt(TAG_XP));
        track.level = Mth.clamp(tag.getInt(TAG_LEVEL), 0, maxLevel);
        ListTag owned = tag.getList(TAG_OWNED, Tag.TAG_COMPOUND);
        for (int i = 0; i < owned.size(); i++) {
            CompoundTag entry = owned.getCompound(i);
            String id = entry.getString(TAG_ID);
            int maxRank = PerkPool.maxRank(id);
            if (maxRank > 0) {
                track.ranks.put(id, Mth.clamp(entry.getInt(TAG_RANK), 1, maxRank));
            }
        }
    }
}
