package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.terms.Realm;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;

final class Trodden {

    private static final int MOST = 64;

    private static final int EVERY = 8;

    private final Queue<Trod> box = new ConcurrentLinkedQueue<>();

    private final AtomicInteger held = new AtomicInteger();

    private final AtomicInteger seen = new AtomicInteger();

    void note(ResourceLocation kind, Realm realm, LongList along) {
        if (along.isEmpty() || Math.floorMod(seen.incrementAndGet(), EVERY) != 0
            || held.get() >= MOST) {
            return;
        }
        held.incrementAndGet();
        box.add(new Trod(kind, realm, along));
    }

    List<Trod> drain() {
        List<Trod> taken = new ArrayList<>();
        for (Trod trod = box.poll(); trod != null; trod = box.poll()) {
            held.decrementAndGet();
            taken.add(trod);
        }
        return taken;
    }
}
