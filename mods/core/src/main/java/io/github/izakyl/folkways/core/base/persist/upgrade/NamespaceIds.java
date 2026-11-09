package io.github.izakyl.folkways.core.base.persist.upgrade;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exact, release-frozen id mappings supplied by each installed module's v3 steps. */
public final class NamespaceIds {
    private static final Pattern TEXT_IDS = Pattern.compile(
        "(?<![a-z0-9_./:*?-])folkways:[a-z0-9_./*?-]+(?![a-z0-9_./:*?-])");
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-persist");
    private static final Map<String, String> IDS = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private NamespaceIds() {
    }

    public static void register(Map<String, String> ids) {
        ids.forEach((oldId, newId) -> {
            String previous = IDS.putIfAbsent(oldId, newId);
            if (previous != null && !previous.equals(newId)) {
                throw new IllegalStateException("Conflicting namespace migration for " + oldId);
            }
        });
    }

    public static String current(String id) {
        return current(id, IDS);
    }

    private static String current(String id, Map<String, String> ids) {
        // Removed component entries use !<id> as their key in DataComponentPatch.
        String decoration = "";
        if (id.startsWith("!") || id.startsWith("#") || id.startsWith("ifolkways:")
                || id.startsWith("tfolkways:")) {
            decoration = id.substring(0, 1);
        }
        String bare = id.substring(decoration.length());
        String replacement = ids.get(bare);
        if (replacement != null) {
            return decoration + replacement;
        }
        if (bare.startsWith("folkways:") && !IDS.containsKey(bare) && WARNED.add(bare)) {
            LOGGER.warn("Unknown legacy Folkways id {}; leaving it unchanged", bare);
        }
        return id;
    }

    public static String rewriteText(String text) {
        return TEXT_IDS.matcher(text).replaceAll(match -> Matcher.quoteReplacement(current(match.group())));
    }

    public static void rewrite(CompoundTag raw, Map<String, String> ids) {
        rewriteTag(raw, ids);
    }

    /** Normalize an unversioned value using only the exact tables of installed modules. */
    public static void rewrite(CompoundTag raw) {
        rewrite(raw, IDS);
    }

    private static Tag rewriteTag(Tag raw, Map<String, String> ids) {
        if (raw instanceof CompoundTag compound) {
            if (!RawUpgrades.supported(compound)) {
                return raw;
            }
            for (String key : new ArrayList<>(compound.getAllKeys())) {
                Tag value = compound.get(key);
                value = rewriteTag(value, ids);
                String renamed = current(key, ids);
                if (!key.equals(renamed)) {
                    // A current key supplied alongside a legacy key always wins.
                    if (!compound.contains(renamed)) {
                        compound.put(renamed, value);
                    }
                    compound.remove(key);
                } else {
                    compound.put(key, value);
                }
            }
        } else if (raw instanceof ListTag list) {
            for (int index = 0; index < list.size(); index++) {
                list.set(index, rewriteTag(list.get(index), ids));
            }
        } else if (raw instanceof StringTag string) {
            String value = string.getAsString();
            String renamed = current(value, ids);
            if (!value.equals(renamed)) {
                return StringTag.valueOf(renamed);
            }
        }
        return raw;
    }

    /** Normalize a string id only while decoding; preserve the value codec's encoding and ops. */
    public static <A> Codec<A> codec(Codec<A> codec) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
                T normalized = ops.getStringValue(input)
                    .map(value -> ops.createString(current(value))).result().orElse(input);
                return codec.decode(ops, normalized);
            }

            @Override
            public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
                return codec.encode(input, ops, prefix);
            }
        };
    }

    /** User-editable config ids share the explicit table and warn once per old value. */
    public static String config(String id) {
        String normalized = current(id);
        if (!normalized.equals(id) && WARNED.add("config/" + id)) {
            LOGGER.warn("Legacy Folkways config id {} is now {}; update the configuration", id, normalized);
        }
        return normalized;
    }
}
