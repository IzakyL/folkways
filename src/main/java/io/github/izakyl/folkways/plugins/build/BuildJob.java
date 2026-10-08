package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

record BuildJob(Kind kind, WorldPos focus, Stances footings,
                Map<WorldPos, BlockState> before, Map<WorldPos, BlockState> after, List<WorldPos> joint,
                List<Need> cost) {

    enum Kind {
        LAY,
        POUR,
        STRIP,
        DRAIN,
        RAISE,
        LOWER,
        JOIN
    }

    ResourceLocation doing() {
        return switch (kind) {
            case STRIP, DRAIN, LOWER -> BuildContent.CLEARING;
            case LAY, POUR, RAISE, JOIN -> BuildContent.BUILDING;
        };
    }

    Block block() {
        BlockState state = switch (kind) {
            case STRIP, DRAIN, LOWER -> before.get(focus);
            case LAY, POUR, RAISE, JOIN -> after.get(focus);
        };
        return state == null ? Blocks.AIR : state.getBlock();
    }

    static final int LAY_TICKS = 10;

    static final int STRIP_TICKS = 14;

    static final ItemSpec SCAFFOLDING = ItemSpec.of(ResourceLocation.withDefaultNamespace("scaffolding"));

    static final ItemSpec WATER_BUCKET = ItemSpec.of(ResourceLocation.withDefaultNamespace("water_bucket"));

    BuildJob {
        before = Collections.unmodifiableMap(new LinkedHashMap<>(before));
        after = Collections.unmodifiableMap(new LinkedHashMap<>(after));
        joint = List.copyOf(joint);
        cost = List.copyOf(cost);
    }

    BuildJob(Kind kind, WorldPos focus, Stances footings, Map<WorldPos, BlockState> before,
            Map<WorldPos, BlockState> after) {
        this(kind, focus, footings, before, after, List.of(), List.of());
    }

    BlockState standing() {
        return before.get(focus);
    }

    BlockState goal() {
        return after.get(focus);
    }

    List<Need> feed() {
        return switch (kind) {
            case LAY -> adjusts() ? List.of() : Costs.of(laid()).orElse(List.of());
            case POUR -> List.of(new Need(WATER_BUCKET, 1));
            case RAISE -> List.of(new Need(SCAFFOLDING, after.size()));
            case JOIN -> cost;
            case STRIP, DRAIN, LOWER -> List.of();
        };
    }

    int laborTicks() {
        return switch (kind) {
            case LAY -> broken().isPresent() ? LAY_TICKS * 2 : LAY_TICKS;
            case POUR -> LAY_TICKS;
            case RAISE -> LAY_TICKS * after.size();
            case JOIN -> LAY_TICKS * (int) Math.max(1L, cost.stream().mapToLong(Need::count).sum());
            case STRIP, DRAIN, LOWER -> STRIP_TICKS;
        };
    }

    List<ToolUse> tools() {
        return broken()
            .filter(BlockState::requiresCorrectToolForDrops)
            .map(state -> List.of(new ToolUse(new ToolNeed.SuitableFor(state), 1)))
            .orElse(List.of());
    }

    Optional<BlockState> broken() {
        return switch (kind) {
            case LAY -> adjusts() ? Optional.empty() : before.values().stream().filter(BuildJob::inTheWay).findFirst();
            case STRIP -> Optional.of(standing()).filter(state -> !state.isAir() && !state.liquid());
            case POUR, DRAIN, RAISE, LOWER, JOIN -> Optional.empty();
        };
    }

    boolean adjusts() {
        if (kind != Kind.LAY || goal().getBlock() instanceof LiquidBlock || !before.keySet().equals(after.keySet())) {
            return false;
        }
        for (Map.Entry<WorldPos, BlockState> cell : after.entrySet()) {
            if (!before.get(cell.getKey()).is(cell.getValue().getBlock())) {
                return false;
            }
        }
        return Costs.of(footprint(before, standing())).equals(Costs.of(laid()));
    }

    Footprint laid() {
        return footprint(after, goal());
    }

    private Footprint footprint(Map<WorldPos, BlockState> cells, BlockState anchor) {
        return Footprints.of(pos -> cells.getOrDefault(focus.at(pos), Blocks.AIR.defaultBlockState()),
            focus.cell(), anchor);
    }

    static boolean inTheWay(BlockState standing) {
        return !standing.isAir() && !standing.liquid() && !standing.canBeReplaced();
    }
}
