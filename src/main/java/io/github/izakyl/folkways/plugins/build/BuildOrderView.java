package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.front.api.Ghost;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public record BuildOrderView(UUID id, BlockPos min, BlockPos max, int placed, int total) {

    static Optional<BuildOrderView> of(ServerLevel level, BlueprintBuildOrder order, Blueprint blueprint) {
        BuildTarget target = BuildTarget.of(order, blueprint).in(level);
        if (target.cells().isEmpty() && target.unloaded().isEmpty() || !order.anchor().in(level)) {
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
            if (wanted != null && level.hasChunkAt(pos) && BuildPlanner.satisfied(level.getBlockState(pos), wanted)) {
                placed++;
            }
        }
        int total = cells.size() + target.joints().size();
        for (Joint joint : target.joints()) {
            if (level.hasChunkAt(joint.from()) && Joints.joiner().joined(level, joint.from(), joint.to())) {
                placed++;
            }
        }
        return Optional.of(new BuildOrderView(order.id(),
            new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), placed, total));
    }

    static List<Ghost> pending(ServerLevel level, BlueprintBuildOrder order, Blueprint blueprint) {
        BuildTarget target = BuildTarget.of(order, blueprint).in(level);
        if (!order.anchor().in(level)) {
            return List.of();
        }
        List<Ghost> owed = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> cell : target.cells().entrySet()) {
            BlockPos pos = cell.getKey();
            BlockState wanted = cell.getValue();
            if (wanted.isAir() || BuildPlanner.unlayable(target.footprint(pos)) || !level.hasChunkAt(pos)) {
                continue;
            }
            if (!BuildPlanner.satisfied(level.getBlockState(pos), wanted)) {
                owed.add(new Ghost(pos, wanted));
            }
        }
        for (Joint joint : target.joints()) {
            BlockState start = target.cells().get(joint.from());
            BlockState end = target.cells().get(joint.to());
            if (start == null || end == null || !level.hasChunkAt(joint.from()) || !level.hasChunkAt(joint.to())
                || Joints.joiner().joined(level, joint.from(), joint.to())) {
                continue;
            }
            Joints.sketch(joint.from(), start, joint.to(), end).forEach((pos, state) -> {
                if (level.getBlockState(pos).canBeReplaced()) {
                    owed.add(new Ghost(pos, state));
                }
            });
        }
        return List.copyOf(owed);
    }

    static Map<ResourceLocation, Long> owed(ServerLevel level, BlueprintBuildOrder order, Blueprint blueprint) {
        Map<ResourceLocation, Long> owed = new LinkedHashMap<>();
        if (!order.anchor().in(level)) {
            return owed;
        }
        BuildTarget target = BuildTarget.of(order, blueprint).in(level);
        for (Map.Entry<BlockPos, BlockState> cell : target.cells().entrySet()) {
            BlockPos pos = cell.getKey();
            if (cell.getValue().isAir() || !level.hasChunkAt(pos)
                || BuildPlanner.satisfied(level.getBlockState(pos), cell.getValue())) {
                continue;
            }
            Footprint print = target.footprint(pos);
            if (!print.anchor().equals(pos) || BuildPlanner.unlayable(print)) {
                continue;
            }
            for (Need need : Costs.of(print).orElse(List.of())) {
                Goods.members(need.spec()).stream().findFirst().ifPresent(item ->
                    owed.merge(BuiltInRegistries.ITEM.getKey(item), need.count(), Long::sum));
            }
        }
        return owed;
    }
}
