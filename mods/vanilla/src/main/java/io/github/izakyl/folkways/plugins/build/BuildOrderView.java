package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.front.api.Ghost;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

public record BuildOrderView(UUID id, BlockPos min, BlockPos max, int placed, int total) {

    static Optional<BuildOrderView> of(UUID order, BuildTarget target, Set<BlockPos> met, Set<Joint> joined) {
        if (target.cells().isEmpty() && target.unloaded().isEmpty()) {
            return Optional.empty();
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int placed = 0;
        List<BlockPos> cells = new ArrayList<>(target.cells().keySet());
        cells.addAll(target.unloaded());
        for (BlockPos pos : cells) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
            BlockState wanted = target.cells().get(pos);
            if (wanted != null && met.contains(pos)) {
                placed++;
            }
        }
        int total = cells.size() + target.joints().size();
        for (Joint joint : target.joints()) {
            if (joined.contains(joint)) {
                placed++;
            }
        }
        return Optional.of(new BuildOrderView(order,
            new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), placed, total));
    }

    static List<Ghost> pending(ServerLevel level, BuildTarget target, Set<BlockPos> met, Set<Joint> joined) {
        List<Ghost> owed = new ArrayList<>();
        Map<BlockPos, SchematicPlacement.Launch> shots = target.shots();
        for (Map.Entry<BlockPos, BlockState> cell : target.cells().entrySet()) {
            BlockPos pos = cell.getKey();
            BlockState wanted = cell.getValue();
            SchematicPlacement.Launch shot = shots.get(pos);
            if (met.contains(pos) || wanted.isAir() || shot == null || !level.hasChunkAt(pos)
                || target.costs(level, shot, met).isEmpty()) {
                continue;
            }
            owed.add(new Ghost(pos, wanted,
                new io.github.izakyl.folkways.core.api.structure.StructurePayload(target.dataAt(pos).tag())));
        }
        for (Joint joint : target.joints()) {
            BlockState start = target.cells().get(joint.from());
            BlockState end = target.cells().get(joint.to());
            if (joined.contains(joint) || start == null || end == null
                || !level.hasChunkAt(joint.from()) || !level.hasChunkAt(joint.to())) {
                continue;
            }
            Joints.sketch(joint.from(), start, joint.to(), end).forEach((pos, state) -> {
                if (!met.contains(pos) && level.getBlockState(pos).canBeReplaced()) {
                    owed.add(new Ghost(pos, state));
                }
            });
        }
        return List.copyOf(owed);
    }

    static Map<ItemSpec, Long> owed(ServerLevel level, BuildTarget target, Set<BlockPos> met) {
        Map<ItemSpec, Long> owed = new LinkedHashMap<>();
        Set<BlockPos> quoted = new LinkedHashSet<>();
        for (SchematicPlacement.Launch shot : target.shots().values()) {
            BlockPos anchor = shot.anchor();
            if (!quoted.add(anchor) || met.contains(anchor) || !level.hasChunkAt(anchor)
                || target.satisfied(level, anchor)) {
                continue;
            }
            for (Need need : target.costs(level, shot, met).orElse(List.of())) {
                owed.merge(need.spec(), need.count(), Long::sum);
            }
            // A waterlogged block laid where no water stands has a bucket let into it after.
            for (BlockPos cell : shot.produces()) {
                if (!met.contains(cell) && Step.soaked(target.wanted(cell))
                        && level.getFluidState(cell).getType() != Fluids.WATER) {
                    owed.merge(ItemSpec.of(Items.WATER_BUCKET), 1L, Long::sum);
                }
            }
        }
        return owed;
    }
}
