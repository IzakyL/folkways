package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * The chain nets the colony was given: every chain conveyor of each, as last walked. A net is walked again from what
 * is kept of it, so it is still the colony's when chains are added to it or one of its own is broken.
 */
final class ChainNets {

    private static final String TAG_CHAINS = "chains";

    private final Set<WorldPos> chains = new LinkedHashSet<>();

    synchronized boolean holds(WorldPos chain) {
        return chains.contains(chain);
    }

    synchronized boolean holdsAny(ServerLevel level, Collection<BlockPos> net) {
        return net.stream().anyMatch(at -> chains.contains(WorldPos.of(level, at)));
    }

    synchronized Set<BlockPos> in(ServerLevel level) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (WorldPos chain : chains) {
            if (chain.in(level)) {
                found.add(chain.block(level));
            }
        }
        return found;
    }

    synchronized boolean take(ServerLevel level, Collection<BlockPos> net) {
        boolean added = false;
        for (BlockPos at : net) {
            added |= chains.add(WorldPos.of(level, at));
        }
        return added;
    }

    synchronized boolean drop(ServerLevel level, Collection<BlockPos> net) {
        boolean dropped = false;
        for (BlockPos at : net) {
            dropped |= chains.remove(WorldPos.of(level, at));
        }
        return dropped;
    }

    // Replaces what is kept in this level, loaded or not, with the nets as walked now.
    synchronized boolean rewalked(ServerLevel level, Set<BlockPos> loaded, Set<BlockPos> walked) {
        Set<WorldPos> now = new LinkedHashSet<>();
        for (WorldPos chain : chains) {
            if (!chain.in(level) || !loaded.contains(chain.block(level))) {
                now.add(chain);
            }
        }
        for (BlockPos at : walked) {
            now.add(WorldPos.of(level, at));
        }
        if (now.equals(chains)) {
            return false;
        }
        chains.clear();
        chains.addAll(now);
        return true;
    }

    synchronized void clear() {
        chains.clear();
    }

    synchronized CompoundTag save() {
        return Writer.of().children(TAG_CHAINS, List.copyOf(chains), WorldPos::save).tag();
    }

    synchronized void load(Reader reader) {
        chains.clear();
        reader.children(TAG_CHAINS).forEach(one -> WorldPos.load(one).ifPresent(chains::add));
    }
}
