package io.github.izakyl.folkways.core.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

public final class Ledger<V> {

    public enum Window {
        DECLARING,
        UNTIL_READ
    }

    private static volatile boolean open;

    public static void open() {
        open = true;
    }

    public static void close() {
        open = false;
    }

    public static void writing(Object what) {
        if (!open) {
            throw new IllegalStateException(what
                + " cannot be declared outside the window Declaring is dispatched in");
        }
    }

    private final String what;
    private final Window window;
    private final Function<V, ResourceLocation> idOf;
    private final Map<ResourceLocation, V> held = new LinkedHashMap<>();
    private boolean read;

    public Ledger(String what, Function<V, ResourceLocation> idOf) {
        this(what, Window.DECLARING, idOf);
    }

    public static <V> Ledger<V> sealedOnceRead(String what, Function<V, ResourceLocation> idOf) {
        return new Ledger<>(what, Window.UNTIL_READ, idOf);
    }

    private Ledger(String what, Window window, Function<V, ResourceLocation> idOf) {
        this.what = what;
        this.window = window;
        this.idOf = idOf;
    }

    public V claim(V value) {
        return claim(idOf.apply(value), value);
    }

    public V claim(ResourceLocation id, V value) {
        guard(id);
        if (held.putIfAbsent(id, value) != null) {
            throw new IllegalStateException(id + " is already " + what);
        }
        return value;
    }

    private void guard(ResourceLocation id) {
        switch (window) {
            case DECLARING -> writing(id);
            case UNTIL_READ -> {
                if (read) {
                    throw new IllegalStateException(id + " cannot be declared: "
                        + what + " has already been read from");
                }
            }
        }
    }

    private void sealIfRead() {
        if (window == Window.UNTIL_READ) {
            read = true;
        }
    }

    public Optional<V> of(ResourceLocation id) {
        sealIfRead();
        return Optional.ofNullable(held.get(id));
    }

    public V required(ResourceLocation id) {
        sealIfRead();
        V value = held.get(id);
        if (value == null) {
            throw new IllegalStateException(id + " is not " + what + " anything declared");
        }
        return value;
    }

    public List<V> all() {
        sealIfRead();
        return List.copyOf(new ArrayList<>(held.values()));
    }

    public Collection<V> values() {
        sealIfRead();
        return held.values();
    }

    public List<ResourceLocation> ids() {
        sealIfRead();
        return List.copyOf(new ArrayList<>(held.keySet()));
    }
}
