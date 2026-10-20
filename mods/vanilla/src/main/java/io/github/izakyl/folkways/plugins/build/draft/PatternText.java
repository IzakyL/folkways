package io.github.izakyl.folkways.plugins.build.draft;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import io.github.izakyl.folkways.front.api.notice.Notice;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.network.chat.Component;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

/** Pattern-owned translations, isolated by their complete dictionary so old notices survive reloads. */
public final class PatternText {

    public static final String PREFIX = "folkways.pattern_text.";
    private static final int HASH_LENGTH = 64;
    private static final ConcurrentMap<String, Face> DICTIONARIES = new ConcurrentHashMap<>();

    private PatternText() {
    }

    public static String register(Face face) {
        Hasher hash = Hashing.sha256().newHasher();
        hash.putInt(face.words().size());
        new TreeMap<>(face.words()).forEach((language, words) -> {
            hashWord(hash, language);
            hash.putInt(words.size());
            new TreeMap<>(words).forEach((key, value) -> {
                hashWord(hash, key);
                hashWord(hash, value);
            });
        });
        String prefix = PREFIX + hash.hash() + ".";
        DICTIONARIES.putIfAbsent(prefix, face);
        return prefix;
    }

    private static void hashWord(Hasher hash, String word) {
        byte[] bytes = word.getBytes(StandardCharsets.UTF_8);
        hash.putInt(bytes.length).putBytes(bytes);
    }

    public static String key(Face face, String localKey) throws EvalException {
        if (face.words().values().stream().noneMatch(words -> words.containsKey(localKey))) {
            throw Starlark.errorf("pattern lang has no word for '%s'", localKey);
        }
        String scoped = register(face) + localKey;
        if (scoped.length() > Notice.MAX_KEY) {
            throw Starlark.errorf("pattern translation key '%s' is too long (at most %d characters)",
                localKey, Notice.MAX_KEY - PREFIX.length() - HASH_LENGTH - 1);
        }
        return scoped;
    }

    public static Optional<String> word(String language, String scopedKey) {
        int end = PREFIX.length() + HASH_LENGTH;
        if (!scopedKey.startsWith(PREFIX) || scopedKey.length() <= end || scopedKey.charAt(end) != '.') {
            return Optional.empty();
        }
        Face face = DICTIONARIES.get(scopedKey.substring(0, end + 1));
        return face == null ? Optional.empty() : face.word(language, scopedKey.substring(end + 1));
    }

    public static Component component(Notice notice) {
        Object[] args = new Object[notice.args().size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = switch (notice.args().get(i)) {
                case Notice.Arg.Literal literal -> literal.text();
                case Notice.Arg.Named named -> translated(named.key());
            };
        }
        return Component.translatableWithFallback(notice.key(), word(Face.FALLBACK, notice.key())
            .orElse(notice.key()), args);
    }

    private static Component translated(String key) {
        return Component.translatableWithFallback(key, word(Face.FALLBACK, key).orElse(key));
    }
}
