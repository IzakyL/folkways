package io.github.izakyl.folkways.core.resident.internal;

import io.github.izakyl.folkways.base.persist.Required;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.effect.perk.PerkPool;
import io.github.izakyl.folkways.effect.perk.Perks;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

final class BodyPerks implements Perks {

    private static final int[] XP_LEVELS = {0, 180, 480, 900, 1500, 2400, 3300, 4500, 6000, 7800, 9600};

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
        int laborRemainder;
        final Map<String, Integer> ranks = new LinkedHashMap<>();
    }

    private final Map<ResourceLocation, Track> byVocation = new LinkedHashMap<>();
    private final Track global = new Track();
    private Effects effects;
    private int effectsGranted;

    public static int xpForLevel(int level) {
        return XP_LEVELS[Math.clamp(level, 0, 10)];
    }

    public int maxLevel(ResourceLocation vocation) {
        return 10;
    }

    public static int maxGlobalLevel() {
        return PerkPool.globalDepth();
    }

    public void worked(ResourceLocation vocation, int ticks, RandomSource random) {
        Track track = track(vocation);
        long total = (long) track.laborRemainder + Math.max(0, ticks);
        track.laborRemainder = (int) (total % 20);
        addXp(vocation, (int) Math.min(Integer.MAX_VALUE, total / 20), random);
    }

    public void addXp(ResourceLocation vocation, int amount, RandomSource random) {
        if (amount <= 0) {
            return;
        }
        Track track = track(vocation);
        global.xp = (int) Math.min(Integer.MAX_VALUE, (long) global.xp + amount);
        track.xp = (int) Math.min(Integer.MAX_VALUE, (long) track.xp + amount);
        int level = levelForXp(track.xp, maxLevel(vocation));
        while (track.level < level) {
            int next = track.level + 1;
            List<String> pool = PerkPool.vocationCandidates(vocation, track.ranks);
            int known = track.ranks.size();
            // Leave enough of the first five draws to discover three distinct specialities.
            int missing = 3 - known;
            if (next == 1 || next <= 5 && missing >= 6 - next) {
                List<String> unseen = pool.stream().filter(id -> !track.ranks.containsKey(id)).toList();
                if (!unseen.isEmpty()) {
                    pool = unseen;
                }
            }
            if (!pool.isEmpty()) {
                String id = pool.get(random.nextInt(pool.size()));
                track.ranks.merge(id, 1, Integer::sum);
            }
            track.level = next;
            if (next == 3 || next == 6 || next == 10) {
                List<String> shared = PerkPool.globalCandidates(global.ranks);
                if (!shared.isEmpty()) {
                    String id = shared.get(random.nextInt(shared.size()));
                    global.ranks.merge(id, 1, Integer::sum);
                    global.level++;
                }
            }
        }
        effects = null;
    }

    private Track track(ResourceLocation vocation) {
        return byVocation.computeIfAbsent(vocation, key -> new Track());
    }

    public int level(ResourceLocation vocation) {
        return track(vocation).level;
    }

    public int xp(ResourceLocation vocation) {
        return track(vocation).xp;
    }

    public int nextLevelXp(ResourceLocation vocation) {
        int level = level(vocation);
        return level < maxLevel(vocation) ? xpForLevel(level + 1) : 0;
    }

    public Map<String, Integer> ranks(ResourceLocation vocation) {
        return Map.copyOf(track(vocation).ranks);
    }

    public Map<String, Integer> globalRanks() {
        return Map.copyOf(global.ranks);
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

    public Effects effects() {
        int granted = PerkPool.grants();
        if (effects == null || effectsGranted != granted) {
            effects = PerkPool.effects(ranks());
            effectsGranted = granted;
        }
        return effects;
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
        effects = null;
        byVocation.clear();
        CompoundTag vocations = Required.compound(tag, TAG_VOCATIONS);
        for (ResourceLocation vocation : Vocations.ids()) {
            Track track = new Track();
            loadTrack(track, vocations, vocation.toString(), maxLevel(vocation),
                id -> PerkPool.vocationCandidates(vocation, Map.of()).contains(id));
            byVocation.put(vocation, track);
        }
        Required.compound(tag, TAG_GLOBAL);
        loadTrack(global, tag, TAG_GLOBAL, maxGlobalLevel(), PerkPool::global);
    }

    private static CompoundTag saveTrack(Track track) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_XP, track.xp);
        tag.putInt("labor_remainder", track.laborRemainder);
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

    private static void loadTrack(Track track, CompoundTag parent, String key, int maxLevel,
            Predicate<String> belongs) {
        track.xp = 0;
        track.level = 0;
        track.laborRemainder = 0;
        track.ranks.clear();
        if (!parent.contains(key)) {
            return;
        }
        CompoundTag tag = Required.compound(parent, key);
        track.xp = Required.integer(tag, TAG_XP);
        track.laborRemainder = Required.integer(tag, "labor_remainder");
        if (track.xp < 0 || track.laborRemainder < 0 || track.laborRemainder >= 20) {
            throw new IllegalArgumentException("Invalid persisted perk progress for " + key);
        }
        track.level = Mth.clamp(Required.integer(tag, TAG_LEVEL), 0, maxLevel);
        ListTag owned = Required.list(tag, TAG_OWNED, Tag.TAG_COMPOUND);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < owned.size(); i++) {
            CompoundTag entry = owned.getCompound(i);
            String id = Required.string(entry, TAG_ID);
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Duplicate persisted perk " + id);
            }
            int maxRank = PerkPool.maxRank(id);
            if (maxRank > 0) {
                if (!belongs.test(id)) {
                    throw new IllegalArgumentException("Perk " + id + " does not belong to " + key);
                }
                track.ranks.put(id, Mth.clamp(Required.integer(entry, TAG_RANK), 1, maxRank));
            }
        }
    }
}
