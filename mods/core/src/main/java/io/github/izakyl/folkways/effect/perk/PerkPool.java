package io.github.izakyl.folkways.effect.perk;

import io.github.izakyl.folkways.base.Ledger;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.effect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

public final class PerkPool {

    private static final Map<String, Perk> BY_ID = new LinkedHashMap<>();
    private static final Map<ResourceLocation, List<String>> VOCATION_POOLS = new LinkedHashMap<>();
    private static final List<String> GLOBAL_POOL = new ArrayList<>();

    public static final String STOIC = "global_stoic";

    // Bumped whenever what a rank grants may have changed, such as the server's ladders reloading: effects worked out
    // from ranks before it are stale.
    private static volatile int grants;

    private PerkPool() {
    }

    public static void register(ResourceLocation vocation, Perk perk) {
        claim(perk);
        VOCATION_POOLS.computeIfAbsent(vocation, key -> new ArrayList<>()).add(perk.id());
    }

    public static void install(Perk... perks) {
        for (Perk perk : perks) {
            Perk existing = BY_ID.putIfAbsent(perk.id(), perk);
            if (existing != null) {
                throw new IllegalStateException("perk " + perk.id() + " is already registered");
            }
            GLOBAL_POOL.add(perk.id());
        }
    }

    public static void legacy(Perk perk) {
        if (BY_ID.putIfAbsent(perk.id(), perk) != null) {
            throw new IllegalStateException("perk " + perk.id() + " is already registered");
        }
    }

    private static void claim(Perk perk) {
        Ledger.writing("perk " + perk.id());
        Perk existing = BY_ID.putIfAbsent(perk.id(), perk);
        if (existing != null) {
            throw new IllegalStateException("perk " + perk.id() + " is already registered");
        }
    }

    public static void verify(Collection<ResourceLocation> trades) {
        for (Map.Entry<ResourceLocation, List<String>> pool : VOCATION_POOLS.entrySet()) {
            if (!trades.contains(pool.getKey())) {
                throw new IllegalStateException("perks " + pool.getValue() + " were declared for "
                    + pool.getKey() + ", which is not a trade anything declared");
            }
        }
    }

    public static Optional<Perk> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static Set<ResourceLocation> vocations() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(VOCATION_POOLS.keySet()));
    }

    public static int depth(ResourceLocation vocation) {
        return depthOf(VOCATION_POOLS.getOrDefault(vocation, List.of()));
    }

    public static int globalDepth() {
        return depthOf(GLOBAL_POOL);
    }

    private static int depthOf(List<String> pool) {
        int depth = 0;
        for (String id : pool) {
            depth += BY_ID.get(id).maxRank();
        }
        return depth;
    }

    public static int maxRank(String id) {
        Perk perk = BY_ID.get(id);
        return perk == null ? 0 : perk.maxRank();
    }

    public static int grants() {
        return grants;
    }

    public static void regrant() {
        grants++;
    }

    // What these ranks grant together, each change under the perk it came from. A perk no longer declared, or held at
    // no rank, grants nothing.
    public static Effects effects(Map<String, Integer> ranks) {
        List<Effects.Part> parts = new ArrayList<>();
        ranks.forEach((id, rank) -> {
            Perk perk = BY_ID.get(id);
            if (perk == null || rank < 1) {
                return;
            }
            for (Modifier modifier : perk.grant().at(Math.min(rank, perk.maxRank()))) {
                parts.add(new Effects.Part(id, modifier));
            }
        });
        return Effects.of(parts);
    }

    public static List<String> vocationCandidates(ResourceLocation vocation, Map<String, Integer> ranks) {
        return candidates(VOCATION_POOLS.getOrDefault(vocation, List.of()), ranks);
    }

    public static List<String> candidatesFor(ResourceLocation vocation, Map<String, Integer> ranks) {
        List<String> pool = new ArrayList<>(VOCATION_POOLS.getOrDefault(vocation, List.of()));
        pool.addAll(GLOBAL_POOL);
        return candidates(pool, ranks);
    }

    public static boolean global(String id) {
        return GLOBAL_POOL.contains(id) || id.equals(GlobalPerks.PACKMULE);
    }

    public static List<String> globalCandidates(Map<String, Integer> ranks) {
        return candidates(GLOBAL_POOL, ranks);
    }

    private static List<String> candidates(List<String> pool, Map<String, Integer> ranks) {
        List<String> candidates = new ArrayList<>();
        for (String id : pool) {
            if (ranks.getOrDefault(id, 0) < BY_ID.get(id).maxRank()) {
                candidates.add(id);
            }
        }
        return candidates;
    }
}
