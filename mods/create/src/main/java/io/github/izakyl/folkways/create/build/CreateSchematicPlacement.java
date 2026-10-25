package io.github.izakyl.folkways.create.build;

import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.kinetics.simpleRelays.AbstractSimpleShaftBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.schematics.SchematicProcessor;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.foundation.utility.BlockHelper;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.plugins.build.BlockData;
import io.github.izakyl.folkways.plugins.build.Costs;
import io.github.izakyl.folkways.plugins.build.SchematicPlacement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Placement through the schematicannon's own code: its printer level, requirements, data and placement. */
final class CreateSchematicPlacement implements SchematicPlacement.Adapter {

    private static final ResourceLocation SHAFT = ResourceLocation.fromNamespaceAndPath("create", "shaft");

    // Further than any belt Create lets a connector make.
    private static final int LONGEST_BELT = 256;

    /** Converts Create's template origin to the corner expected by a normalized Folkways blueprint. */
    static BlockPos corner(BlockPos anchor, Vec3i size, StructurePlaceSettings settings) {
        // The printer places at anchor + calculateRelativePosition(settings, cell). Blueprint.transformed
        // subtracts the transformed volume's minimum, so its build order must add that minimum back here.
        // Use the whole template, including empty margins, rather than the printer's occupied bounds.
        BlockPos near = StructureTemplate.calculateRelativePosition(settings, BlockPos.ZERO);
        BlockPos far = StructureTemplate.calculateRelativePosition(settings, new BlockPos(size).offset(-1, -1, -1));
        return anchor.offset(BlockPos.min(near, far));
    }

    @Override
    public SchematicPlacement.Printed print(ServerLevel level, StructureTemplate template, Vec3i size) {
        SchematicLevel printer = new SchematicLevel(BlockPos.ZERO, level);
        StructurePlaceSettings settings = new StructurePlaceSettings();
        settings.addProcessor(SchematicProcessor.INSTANCE);
        template.placeInWorld(printer, BlockPos.ZERO, BlockPos.ZERO, settings, printer.getRandom(), Block.UPDATE_CLIENTS);
        BoundingBox bounds = printer.getBounds();
        printer.setBounds(new BoundingBox(Math.min(bounds.minX(), 0), Math.min(bounds.minY(), 0),
            Math.min(bounds.minZ(), 0), Math.max(bounds.maxX(), size.getX() - 1),
            Math.max(bounds.maxY(), size.getY() - 1), Math.max(bounds.maxZ(), size.getZ() - 1)));
        return new SchematicPlacement.Printed() {
            @Override
            public BlockState state(BlockPos pos) {
                return printer.getBlockState(pos);
            }

            @Nullable
            @Override
            public BlockEntity entity(BlockPos pos) {
                return printer.getBlockEntity(pos);
            }
        };
    }

    @Override
    public BlockState zeroAge(BlockState state) {
        return BlockHelper.setZeroAge(state);
    }

    @Override
    public void turn(BlockEntity entity, Mirror mirror, Rotation rotation) {
        new StructureTransform(BlockPos.ZERO, Direction.Axis.Y, rotation, mirror).apply(entity);
    }

    @Nullable
    @Override
    public CompoundTag prepare(ServerLevel level, BlockState state, @Nullable BlockEntity entity) {
        return BlockHelper.prepareBlockEntityData(level, state, entity);
    }

    // SchematicannonBlockEntity#shouldIgnoreBlockState, for a block with a valid, non-empty requirement.
    @Override
    public boolean ignored(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
            || state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
            || state.getBlock() instanceof PistonHeadBlock
            || isBelt(state) && state.getValue(BeltBlock.PART) == BeltPart.MIDDLE;
    }

    @Override
    public Costs.Quote requirement(BlockState state, @Nullable BlockEntity entity) {
        ItemRequirement requirement = ItemRequirement.of(state, entity);
        if (requirement.isInvalid()) {
            return Costs.Quote.skipped();
        }
        List<Need> needs = new ArrayList<>();
        for (ItemRequirement.StackRequirement stack : requirement.getRequiredItems()) {
            if (stack.stack.isEmpty()) {
                continue;
            }
            // The colony cannot wear a tool down, so a requirement that only damages one is not representable.
            if (stack.usage != ItemRequirement.ItemUseType.CONSUME) {
                return Costs.Quote.unsupported();
            }
            if (stack instanceof ItemRequirement.StrictNbtStackRequirement) {
                Costs.Quote exact = Costs.strict(stack.stack);
                if (exact.needs().isEmpty()) {
                    return exact;
                }
                needs.addAll(exact.needs().get());
            } else {
                needs.add(Costs.need(stack.stack));
            }
        }
        return Costs.Quote.of(needs);
    }

    @Override
    public void place(ServerLevel level, BlockPos pos, BlockState state, ItemStack stack, @Nullable CompoundTag data) {
        BlockHelper.placeSchematicBlock(level, state, pos, stack, data);
    }

    /**
     * A belt is shot whole from its last segment, as the cannon launches it. Its other end and its pulleys are
     * charged with it, as the cannon first shoots each of them as a bare shaft; its plain middle is never aimed at.
     */
    @Override
    public Optional<SchematicPlacement.Launch> launch(Function<BlockPos, BlockState> target, BlockPos pos) {
        BlockState state = target.apply(pos);
        if (!isBelt(state)) {
            return SchematicPlacement.Adapter.super.launch(target, pos);
        }
        if (state.getValue(BeltBlock.PART) == BeltPart.MIDDLE || !isBelt(
                SchematicannonBlockEntity.stripBeltIfNotLast(state))) {
            return Optional.empty();
        }
        List<BlockPos> segments = segments(target, pos);
        Set<BlockPos> produces = new LinkedHashSet<>(segments);
        List<BlockPos> charged = new ArrayList<>(shafts(target, segments));
        charged.addFirst(pos.immutable());
        return Optional.of(new SchematicPlacement.Launch(pos, produces, charged));
    }

    // LaunchedItem.ForBelt#place: a shaft where the belt begins, the belt connected along its length, the casings.
    @Override
    public void fire(ServerLevel level, SchematicPlacement.Launch launch, Function<BlockPos, BlockState> states,
            Function<BlockPos, BlockData> data, ItemStack stack) {
        BlockState state = states.apply(launch.anchor());
        if (!isBelt(state)) {
            SchematicPlacement.Adapter.super.fire(level, launch, states, data, stack);
            return;
        }
        List<BlockPos> segments = segments(states, launch.anchor());
        List<BeltBlockEntity.CasingType> casings = new ArrayList<>();
        for (BlockPos segment : segments) {
            BlockEntity stored = data.apply(segment).entity(level, segment, states.apply(segment));
            casings.add(stored instanceof BeltBlockEntity belt ? belt.casing : BeltBlockEntity.CasingType.NONE);
        }
        Direction.Axis axis = state.getValue(BeltBlock.SLOPE) == BeltSlope.SIDEWAYS
            ? Direction.Axis.Y
            : state.getValue(BeltBlock.HORIZONTAL_FACING).getClockWise().getAxis();
        BlockState shaft = BuiltInRegistries.BLOCK.get(SHAFT).defaultBlockState().setValue(AbstractSimpleShaftBlock.AXIS, axis);
        // The shafts the cannon shot before this last segment: a pulley stays one when the belt is connected.
        for (BlockPos segment : shafts(states, segments)) {
            place(level, segment, SchematicannonBlockEntity.stripBeltIfNotLast(states.apply(segment)), ItemStack.EMPTY, null);
        }
        level.setBlockAndUpdate(launch.anchor(), shaft);
        BeltConnectorItem.createBelts(level, launch.anchor(), segments.getLast());
        for (int i = 0; i < segments.size(); i++) {
            if (casings.get(i) != BeltBlockEntity.CasingType.NONE
                && level.getBlockEntity(segments.get(i)) instanceof BeltBlockEntity belt) {
                belt.setCasingType(casings.get(i));
            }
        }
    }

    // The segments besides the shot one that the cannon aims at by themselves: the other end and the pulleys.
    private static List<BlockPos> shafts(Function<BlockPos, BlockState> target, List<BlockPos> segments) {
        return segments.subList(1, segments.size()).stream()
            .filter(segment -> target.apply(segment).getValue(BeltBlock.PART) != BeltPart.MIDDLE)
            .toList();
    }

    private static boolean isBelt(BlockState state) {
        return state.getBlock() instanceof BeltBlock;
    }

    // The belt's cells from the segment it is shot from to its other end.
    private static List<BlockPos> segments(Function<BlockPos, BlockState> target, BlockPos from) {
        BlockState state = target.apply(from);
        BlockPos step = BeltBlock.nextSegmentPosition(state, BlockPos.ZERO, state.getValue(BeltBlock.PART) == BeltPart.START);
        List<BlockPos> segments = new ArrayList<>();
        BlockPos at = from.immutable();
        segments.add(at);
        while (segments.size() < LONGEST_BELT) {
            at = at.offset(step);
            BlockState next = target.apply(at);
            if (!isBelt(next)) {
                break;
            }
            segments.add(at);
            BeltPart part = next.getValue(BeltBlock.PART);
            if (part == BeltPart.START || part == BeltPart.END) {
                break;
            }
        }
        return segments;
    }
}
