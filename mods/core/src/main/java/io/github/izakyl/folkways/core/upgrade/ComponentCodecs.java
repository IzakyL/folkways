package io.github.izakyl.folkways.core.upgrade;

import com.mojang.serialization.Codec;
import io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds;

/** Persistent component compatibility; network and current value encodings stay unchanged. */
public final class ComponentCodecs {
    private ComponentCodecs() {
    }

    public static <A> Codec<A> bookGesture(Codec<A> current) {
        // The released gesture values are bare enum names, so normalization preserves them.
        return NamespaceIds.codec(current);
    }

    public static <A> Codec<A> filterTag(Codec<A> current) {
        return NamespaceIds.codec(current);
    }
}
