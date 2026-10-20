package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.ground.BlockChanges;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.local.LocalGrounds;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ColonyMemberWrites {

    private static final int MAX_CHANGED_CELLS = 4096;
    private static final Map<MinecraftServer, Changed> PENDING = new IdentityHashMap<>();

    private ColonyMemberWrites() {
    }

    public static void blockChanged(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) {
            return;
        }
        LocalGrounds.blockChanged(serverLevel, pos);
        BlockChanges.changed(serverLevel, pos);
        PENDING.computeIfAbsent(serverLevel.getServer(), ignored -> new Changed()).add(serverLevel, pos);
    }

    // Block setters can still be updating their neighbours. Release sites and notify their owners after those
    // writes finish, with new writes from a release left for the next tick.
    public static void reconcile(MinecraftServer server) {
        Changed changed = PENDING.remove(server);
        if (changed == null) {
            return;
        }
        List<ColonyData> colonies = List.copyOf(ColonyRegistry.get(server).colonies());
        if (colonies.isEmpty()) {
            return;
        }
        if (changed.all) {
            for (ColonyData colony : colonies) {
                colony.reconcile(server);
            }
            return;
        }
        changed.cells.forEach((level, cells) -> cells.forEach(cell -> {
            WorldPos at = WorldPos.of(level, cell);
            for (ColonyData colony : colonies) {
                colony.holdings().reconcile(server, at);
            }
        }));
    }

    public static void forgetServer() {
        PENDING.clear();
    }

    private static final class Changed {
        private final Map<ServerLevel, Set<BlockPos>> cells = new LinkedHashMap<>();
        private int count;
        private boolean all;

        private void add(ServerLevel level, BlockPos cell) {
            if (all) {
                return;
            }
            if (cells.computeIfAbsent(level, ignored -> new LinkedHashSet<>()).add(cell.immutable())) {
                count++;
            }
            // Large edits and world generation need only one pass over the holdings, not an unbounded list
            // of every block they wrote. Ordinary writes need no registry lookup inside the block setter.
            if (count >= MAX_CHANGED_CELLS) {
                all = true;
                cells.clear();
            }
        }
    }

    public static void observe(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)
                || !serverLevel.getServer().isSameThread()
                || !serverLevel.hasChunkAt(pos)) {
            return;
        }
        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }
        ColonyRegistry registry = ColonyRegistry.get(serverLevel.getServer());
        if (ColonyMembership.owner(blockEntity).filter(registry::isRazed).isPresent()) {
            ColonyMembership.leave(blockEntity);
        }
    }
}
