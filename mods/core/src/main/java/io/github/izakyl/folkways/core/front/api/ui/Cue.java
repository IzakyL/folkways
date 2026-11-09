package io.github.izakyl.folkways.core.front.api.ui;

import java.util.List;
import net.minecraft.network.chat.Component;

/** One line of a key hint: the inputs to press, drawn as icons, and what pressing them does. */
public record Cue(List<Key> keys, Component does, float held, String binding) {

    private static final float NOT_HELD = -1.0F;

    public enum Key {
        CLICK,
        ALT_CLICK,
        USE,
        WHEEL,
        SNEAK,
        JUMP,
        MODIFIER,
        ALTERNATE,
        OVERHEAD,
        WALK,
        DRAG,
        ESCAPE,
        TAB,
        BACKSPACE,
        DIGITS,
        PONDER
    }

    public Cue(List<Key> keys, Component does, float held) {
        this(keys, does, held, null);
    }

    public Cue {
        keys = List.copyOf(keys);
    }

    public static Cue of(String doesKey, Key... keys) {
        return of(Component.translatable(doesKey), keys);
    }

    public static Cue of(Component does, Key... keys) {
        return new Cue(List.of(keys), does, NOT_HELD, null);
    }

    /** A client-registered, rebindable input, resolved by its KeyMapping name when drawn. */
    public static Cue bound(String doesKey, String binding) {
        return new Cue(List.of(), Component.translatable(doesKey), NOT_HELD, binding);
    }

    /** The same line with a bar under it, filled as far as a key held down has got. */
    public Cue held(float progress) {
        return new Cue(keys, does, progress, binding);
    }

    public boolean holding() {
        return held > 0.0F;
    }
}
