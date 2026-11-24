package io.github.izakyl.folkways.core.shell.letitwalk;

import dev.letitwalk.content.WalkerCoreBlockEntity;
import dev.letitwalk.walker.WalkerTracker;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.izakyl.folkways.core.world.api.FrameCopies;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Let it Walk! keeps one registry of walkers for the whole server, keyed by the core's storage cell. A copy of a walker
 * shares that cell, so removing the copy drops the original's entries too, until its next tick puts them back. The
 * originals are re-entered at once, so nothing driving the real walker sees it gone.
 */
final class WalkerCopies implements FrameCopies.Hook {

    private record Tracked(UUID hull, BlockPos core, UUID walker) {
    }

    static void install() {
        FrameCopies.hook(new WalkerCopies());
    }

    @Override
    public Runnable captured(ServerLevel level, Collection<UUID> originals) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return () -> { };
        }
        List<Tracked> tracked = new ArrayList<>();
        for (UUID id : originals) {
            SubLevel hull = container.getSubLevel(id);
            BlockPos core = hull == null ? null : WalkerTracker.coreOf(hull);
            if (core != null && level.isLoaded(core) && level.getBlockEntity(core) instanceof WalkerCoreBlockEntity be) {
                // Read without asking: asking a core for its id gives it one.
                var saved = be.saveWithoutMetadata(level.registryAccess());
                tracked.add(new Tracked(id, core, saved.hasUUID("walkerId") ? saved.getUUID("walkerId") : null));
            }
        }
        return () -> {
            for (Tracked entry : tracked) {
                SubLevel hull = container.getSubLevel(entry.hull());
                if (hull == null || hull.isRemoved() || !level.isLoaded(entry.core())
                        || !(level.getBlockEntity(entry.core()) instanceof WalkerCoreBlockEntity)) {
                    continue;
                }
                WalkerTracker.track(hull, entry.core());
                if (entry.walker() != null) {
                    WalkerTracker.trackId(entry.walker(), level, entry.core());
                }
            }
        };
    }
}
