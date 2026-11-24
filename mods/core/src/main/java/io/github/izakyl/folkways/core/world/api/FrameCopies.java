package io.github.izakyl.folkways.core.world.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

/**
 * Working copies of moving structures in another level, for trying a machine without touching it. A capture reads the
 * structures, and the ones they bring along (a walker's legs), from their own level at once; placing it rebuilds them
 * whole in another level at the same pose, with the same storage cells and the same motion, under fresh identities
 * from a {@link CopiedIds}. The copies are never saved and are removed again with everything they hold.
 */
public final class FrameCopies {

    /** Copies one kind of moving structure; a structure mod's adapter installs one. Server thread only. */
    public interface Copier {
        /**
         * Reads the given structures now, with the ones they depend on; empty when none of them is this copier's.
         * Throws {@code IllegalArgumentException("copy_too_large")} past the limits.
         */
        Optional<Capture> capture(ServerLevel level, Collection<UUID> frames, CopiedIds ids, Limits limits);

        /** Removes every structure of this kind from a level that holds only copies. */
        int clear(ServerLevel level);
    }

    /** How much one capture may take: structures, and the non-air blocks in them. */
    public record Limits(int frames, int blocks) {
    }

    /** A structure read for copying. Storage is the box of cells it keeps its blocks in; world is where it stands. */
    public record Captured(UUID original, UUID copy, int blocks, BoundingBox storage, AABB world) {
    }

    public interface Capture {
        List<Captured> frames();

        /** Builds the copies in a level; one that fails to load is missing from the result's copies. */
        Placed place(ServerLevel into);
    }

    public interface Placed {
        List<Copy> copies();

        /** Removes every copy, its physics body, tickets and anything held for it. Safe to call again. */
        void remove();
    }

    public interface Copy {
        UUID original();

        UUID id();

        /** Whether the copy still exists; a structure can break apart or be removed by what it does. */
        boolean present();

        /** Where it stands now: the position of its pose and its orientation, in the world. */
        Optional<Pose> pose();

        /** Removes this copy now, as when it leaves the area the copy may run in. */
        void remove();
    }

    public record Pose(Vec3 position, Quaterniond orientation) {
    }

    /**
     * Something outside the copied structures that copying disturbs, such as a mod's registry of structures keyed by a
     * storage cell the copy shares. Told at capture which structures are being copied, it answers what to restore once
     * their copies are removed.
     */
    public interface Hook {
        Runnable captured(ServerLevel level, Collection<UUID> originals);
    }

    private static final List<Copier> COPIERS = new CopyOnWriteArrayList<>();
    private static final List<Hook> HOOKS = new CopyOnWriteArrayList<>();

    private FrameCopies() {
    }

    public static Runnable install(Copier copier) {
        COPIERS.add(copier);
        return () -> COPIERS.remove(copier);
    }

    public static Runnable hook(Hook hook) {
        HOOKS.add(hook);
        return () -> HOOKS.remove(hook);
    }

    /** Whether any structure kind can be copied in this game. */
    public static boolean available() {
        return !COPIERS.isEmpty();
    }

    /** Reads the structures with every copier that knows them. */
    public static List<Capture> capture(ServerLevel level, Collection<UUID> frames, CopiedIds ids, Limits limits) {
        List<Capture> captures = new ArrayList<>();
        for (Copier copier : COPIERS) {
            copier.capture(level, frames, ids, limits).ifPresent(captures::add);
        }
        return captures;
    }

    /** What every hook restores once copies of these structures are gone. */
    public static List<Runnable> restorers(ServerLevel level, Collection<UUID> originals) {
        List<Runnable> restore = new ArrayList<>();
        for (Hook hook : HOOKS) {
            restore.add(hook.captured(level, originals));
        }
        return restore;
    }

    public static int clear(ServerLevel level) {
        int cleared = 0;
        for (Copier copier : COPIERS) {
            cleared += copier.clear(level);
        }
        return cleared;
    }
}
