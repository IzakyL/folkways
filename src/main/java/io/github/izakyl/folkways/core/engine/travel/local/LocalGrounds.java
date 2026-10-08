package io.github.izakyl.folkways.core.engine.travel.local;

import io.github.izakyl.folkways.core.api.ground.Grounds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class LocalGrounds implements Grounds.Source {

    private static final Map<ServerLevel, List<LocalWatch>> WATCHING = new WeakHashMap<>();

    private LocalGrounds() {
    }

    public static void install() {
        Grounds.install(new LocalGrounds());
    }

    @Override
    public Grounds.Watch watch(ServerLevel level, BoundingBox box) {
        LocalWatch watch = new LocalWatch(level, box);
        WATCHING.computeIfAbsent(level, ignored -> new ArrayList<>()).add(watch);
        return watch;
    }

    static void forget(LocalWatch watch) {
        List<LocalWatch> watches = WATCHING.get(watch.level());
        if (watches != null && watches.remove(watch) && watches.isEmpty()) {
            WATCHING.remove(watch.level());
        }
    }

    public static void blockChanged(ServerLevel level, BlockPos cell) {
        List<LocalWatch> watches = WATCHING.get(level);
        if (watches == null) {
            return;
        }
        for (LocalWatch watch : watches) {
            watch.changed(cell);
        }
    }
}
