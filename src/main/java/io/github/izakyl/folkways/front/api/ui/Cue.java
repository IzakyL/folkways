package io.github.izakyl.folkways.front.api.ui;

import java.util.List;
import net.minecraft.network.chat.Component;

/** One line of a key hint: the inputs to press, drawn as icons, and what pressing them does. */
public record Cue(List<Key> keys, Component does, float held) {

    private static final float NOT_HELD = -1.0F;

    public enum Key {
        CLICK,
        ALT_CLICK,
        USE,
        WHEEL,
        SNEAK,
        MODIFIER,
        ALTERNATE,
        OVERHEAD,
        WALK,
        DRAG,
        ESCAPE,
        DIGITS,
        PONDER
    }

    public Cue {
        keys = List.copyOf(keys);
    }

    public static Cue of(String doesKey, Key... keys) {
        return of(Component.translatable(doesKey), keys);
    }

    public static Cue of(Component does, Key... keys) {
        return new Cue(List.of(keys), does, NOT_HELD);
    }

    /** The same line with a bar under it, filled as far as a key held down has got. */
    public Cue held(float progress) {
        return new Cue(keys, does, progress);
    }

    public boolean holding() {
        return held > 0.0F;
    }
}
