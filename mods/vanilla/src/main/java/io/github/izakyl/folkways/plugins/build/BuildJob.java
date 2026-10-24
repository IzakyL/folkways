package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One piece of work. A laying job is one schematicannon shot at {@code focus}: {@code shot} are the target cells it
 * leaves placed, and the rest of {@code after} is what it clears of the blocks standing there.
 */
record BuildJob(Kind kind, WorldPos focus, Stances footings,
                Map<WorldPos, BlockState> before, Map<WorldPos, BlockState> after, List<WorldPos> joint,
                List<Need> cost, Map<WorldPos, BlockData> data, Set<WorldPos> shot) {

    BuildJob(Kind kind, WorldPos focus, Stances footings, Map<WorldPos, BlockState> before,
            Map<WorldPos, BlockState> after, List<WorldPos> joint, List<Need> cost) {
        this(kind, focus, footings, before, after, joint,
            kind == Kind.LAY ? quoted(focus, after) : cost, Map.of(), kind == Kind.LAY ? after.keySet() : Set.of());
    }

    enum Kind {
        LAY,
        STRIP,
        DRAIN,
        RAISE,
        LOWER,
        JOIN,
        SOAK
    }

    ResourceLocation doing() {
        return switch (kind) {
            case STRIP, DRAIN, LOWER -> BuildContent.CLEARING;
            case LAY, RAISE, JOIN, SOAK -> BuildContent.BUILDING;
        };
    }

    Block block() {
        BlockState state = switch (kind) {
            case STRIP, DRAIN, LOWER -> before.get(focus);
            case LAY, RAISE, JOIN, SOAK -> after.get(focus);
        };
        return state == null ? Blocks.AIR : state.getBlock();
    }

    static final int LAY_TICKS = 10;

    static final ItemSpec SCAFFOLDING = ItemSpec.of(ResourceLocation.withDefaultNamespace("scaffolding"));

    static final ItemSpec BUCKET = ItemSpec.of(ResourceLocation.withDefaultNamespace("bucket"));

    BuildJob {
        before = Collections.unmodifiableMap(new LinkedHashMap<>(before));
        after = Collections.unmodifiableMap(new LinkedHashMap<>(after));
        joint = List.copyOf(joint);
        cost = List.copyOf(cost);
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        shot = Collections.unmodifiableSet(new LinkedHashSet<>(shot));
    }

    BuildJob(Kind kind, WorldPos focus, Stances footings, Map<WorldPos, BlockState> before,
            Map<WorldPos, BlockState> after) {
        this(kind, focus, footings, before, after, List.of(), List.of());
    }

    private static List<Need> quoted(WorldPos focus, Map<WorldPos, BlockState> after) {
        Function<BlockPos, BlockState> states = pos -> after.getOrDefault(focus.at(pos), Blocks.AIR.defaultBlockState());
        BlockState anchor = states.apply(focus.cell());
        if (anchor.getBlock() instanceof LiquidBlock) {
            return Costs.poured(anchor).orElse(List.of());
        }
        return SchematicPlacement.adapter().launch(states, focus.cell())
            .flatMap(launch -> quoted(launch, states))
            .orElse(List.of());
    }

    /** A shot's cost reckoned without a level: from the states alone, as for a block entity with no data. */
    static Optional<List<Need>> quoted(SchematicPlacement.Launch launch, Function<BlockPos, BlockState> states) {
        BlockState anchor = states.apply(launch.anchor());
        if (anchor.getBlock() instanceof LiquidBlock) {
            return Costs.poured(anchor);
        }
        List<Need> needs = new ArrayList<>();
        for (BlockPos cell : launch.charged()) {
            Costs.Quote quote = SchematicPlacement.adapter().requirement(states.apply(cell), null);
            if (quote.needs().isEmpty()) {
                return Optional.empty();
            }
            needs.addAll(quote.needs().get());
        }
        return Optional.of(needs);
    }

    BlockState standing() {
        return before.get(focus);
    }

    BlockState goal() {
        return after.get(focus);
    }

    List<Need> feed() {
        return switch (kind) {
            case LAY -> adjusts() ? List.of() : cost;
            case RAISE -> List.of(new Need(SCAFFOLDING, after.size()));
            case JOIN, SOAK -> cost;
            case STRIP, DRAIN, LOWER -> List.of();
        };
    }

    // What the job leaves in the pack: what it breaks, the bucket a pour empties, the scaffold it takes down.
    List<Amount> leaves() {
        return switch (kind) {
            case STRIP -> Leaves.ofBlocks(before.values().stream()
                .filter(state -> !state.isAir() && !state.liquid()).toList());
            case LAY -> adjusts() ? List.of() : laidLeaves();
            case LOWER -> List.of(new Amount(SCAFFOLDING, after.size()));
            case SOAK -> List.of(new Amount(BUCKET, 1));
            case DRAIN, RAISE, JOIN -> List.of();
        };
    }

    private List<Amount> laidLeaves() {
        List<Amount> left = new ArrayList<>(Leaves.ofBlocks(
            before.values().stream().filter(BuildJob::inTheWay).toList()));
        for (Need need : feed()) {
            if (need.spec().item().map(BuiltInRegistries.ITEM::get).filter(BucketItem.class::isInstance).isPresent()) {
                left.add(new Amount(BUCKET, need.count()));
            }
        }
        return Leaves.merged(left);
    }

    int laborTicks() {
        // One clock per affected cell, independent of walking distance, material price, or sort order.
        // A door or a column changes several cells atomically and pays the same per-cell clock.
        return LAY_TICKS * Math.max(1, after.size());
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
            case DRAIN, RAISE, LOWER, JOIN, SOAK -> Optional.empty();
        };
    }

    /**
     * Whether the shot only resets blocks already standing there in another state, a fence joined differently or a
     * door left open: the same blocks for the same requirement, so nothing is spent and nothing broken.
     */
    boolean adjusts() {
        if (kind != Kind.LAY || goal() == null || goal().getBlock() instanceof LiquidBlock
            || !before.keySet().equals(after.keySet())) {
            return false;
        }
        for (Map.Entry<WorldPos, BlockState> cell : after.entrySet()) {
            BlockState standing = before.get(cell.getKey());
            if (!standing.is(cell.getValue().getBlock())
                || !SchematicPlacement.adapter().requirement(standing, null)
                    .equals(SchematicPlacement.adapter().requirement(cell.getValue(), null))) {
                return false;
            }
        }
        return true;
    }

    static boolean inTheWay(BlockState standing) {
        return !standing.isAir() && !standing.liquid() && !standing.canBeReplaced();
    }
}
