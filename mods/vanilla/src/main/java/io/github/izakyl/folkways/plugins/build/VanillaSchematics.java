package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.DirtPathBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.common.SpecialPlantable;

/**
 * The schematicannon's rules copied for a world without Create (Create 6.0.10): its printer level, {@code
 * setZeroAge}, ignored parts, default item requirements, trusted block entity data and {@code placeSchematicBlock}.
 * What only Create's own blocks and registries add is left out.
 */
final class VanillaSchematics implements SchematicPlacement.Adapter {

    private static final int NO_SHAPE_UPDATES = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @Override
    public SchematicPlacement.Printed print(ServerLevel level, StructureTemplate template, Vec3i size) {
        PrinterLevel printer = new PrinterLevel(level, size);
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
        template.placeInWorld(printer, BlockPos.ZERO, BlockPos.ZERO, settings, level.getRandom(), Block.UPDATE_CLIENTS);
        return printer;
    }

    @Override
    public BlockState zeroAge(BlockState state) {
        if (state.hasProperty(BlockStateProperties.AGE_1)) {
            return state.setValue(BlockStateProperties.AGE_1, 0);
        } else if (state.hasProperty(BlockStateProperties.AGE_2)) {
            return state.setValue(BlockStateProperties.AGE_2, 0);
        } else if (state.hasProperty(BlockStateProperties.AGE_3)) {
            return state.setValue(BlockStateProperties.AGE_3, 0);
        } else if (state.hasProperty(BlockStateProperties.AGE_5)) {
            return state.setValue(BlockStateProperties.AGE_5, 0);
        } else if (state.hasProperty(BlockStateProperties.AGE_7)) {
            return state.setValue(BlockStateProperties.AGE_7, 0);
        } else if (state.hasProperty(BlockStateProperties.AGE_15)) {
            return state.setValue(BlockStateProperties.AGE_15, 0);
        } else if (state.hasProperty(BlockStateProperties.AGE_25)) {
            return state.setValue(BlockStateProperties.AGE_25, 0);
        } else if (state.hasProperty(BlockStateProperties.LEVEL_HONEY)) {
            return state.setValue(BlockStateProperties.LEVEL_HONEY, 0);
        } else if (state.hasProperty(BlockStateProperties.HATCH)) {
            return state.setValue(BlockStateProperties.HATCH, 0);
        } else if (state.hasProperty(BlockStateProperties.STAGE)) {
            return state.setValue(BlockStateProperties.STAGE, 0);
        } else if (state.is(BlockTags.CAULDRONS)) {
            return Blocks.CAULDRON.defaultBlockState();
        } else if (state.hasProperty(BlockStateProperties.LEVEL_COMPOSTER)) {
            return state.setValue(BlockStateProperties.LEVEL_COMPOSTER, 0);
        } else {
            return state.hasProperty(BlockStateProperties.EXTENDED)
                ? state.setValue(BlockStateProperties.EXTENDED, false) : state;
        }
    }

    @Override
    public void turn(BlockEntity entity, Mirror mirror, Rotation rotation) {
        StructureCompat.of(entity.getBlockState()).ifPresent(handler -> handler.turn(entity, mirror, rotation));
    }

    @Nullable
    @Override
    public CompoundTag prepare(ServerLevel level, BlockState state, @Nullable BlockEntity entity) {
        var handler = StructureCompat.of(state);
        if (handler.isPresent() && entity != null) {
            return handler.get().safe(entity);
        }
        if (entity == null || !state.is(BlockTags.BANNERS) && !state.is(BlockTags.ALL_SIGNS)) {
            return null;
        }
        CompoundTag data = entity.saveWithFullMetadata(level.registryAccess());
        if (entity instanceof SignBlockEntity sign) {
            for (boolean front : new boolean[] {true, false}) {
                for (Component line : sign.getText(front).getMessages(false)) {
                    if (clicks(line)) {
                        return null;
                    }
                }
            }
            return data;
        }
        return entity.onlyOpCanSetNbt() ? null : data;
    }

    private static boolean clicks(Component line) {
        for (Component sibling : line.getSiblings()) {
            if (clicks(sibling)) {
                return true;
            }
        }
        return line.getStyle().getClickEvent() != null;
    }

    @Override
    public boolean ignored(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
            || state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
            || state.getBlock() instanceof PistonHeadBlock;
    }

    @Override
    public Costs.Quote requirement(BlockState state, @Nullable BlockEntity entity) {
        var handler = StructureCompat.of(state);
        if (handler.isPresent()) {
            try {
                return Costs.Quote.of(handler.get().materials(entity));
            } catch (IllegalArgumentException invalid) {
                return Costs.Quote.unsupported();
            }
        }
        Block block = state.getBlock();
        if (block == Blocks.AIR) {
            return Costs.Quote.of(List.of());
        }
        Item item = block.asItem();
        if (item == Items.AIR) {
            return Costs.Quote.skipped();
        }
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
            && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return consume(item, 2);
        } else if (block instanceof TurtleEggBlock) {
            return consume(item, state.getValue(TurtleEggBlock.EGGS));
        } else if (block instanceof SeaPickleBlock) {
            return consume(item, state.getValue(SeaPickleBlock.PICKLES));
        } else if (block instanceof SnowLayerBlock) {
            return consume(item, state.getValue(SnowLayerBlock.LAYERS));
        } else if (block instanceof FarmBlock || block instanceof DirtPathBlock) {
            return consume(Items.DIRT, 1);
        } else if (entity instanceof BannerBlockEntity banner) {
            return Costs.strict(banner.getItem());
        } else if (block == Blocks.TALL_GRASS) {
            return consume(Items.SHORT_GRASS, 2);
        } else if (block == Blocks.LARGE_FERN) {
            return consume(Items.FERN, 2);
        }
        return consume(item, 1);
    }

    private static Costs.Quote consume(Item item, int count) {
        return Costs.Quote.of(List.of(Costs.need(item, count)));
    }

    @Override
    public void place(ServerLevel level, BlockPos pos, BlockState state, ItemStack stack, @Nullable CompoundTag data) {
        Block block = state.getBlock();
        boolean alreadyPlaced = false;
        if (block instanceof ChiseledBookShelfBlock) {
            for (BooleanProperty slot : ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES) {
                state = state.setValue(slot, false);
            }
        }
        if (state.hasProperty(BlockStateProperties.EXTENDED)) {
            state = state.setValue(BlockStateProperties.EXTENDED, false);
        }
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        if (block == Blocks.COMPOSTER) {
            state = Blocks.COMPOSTER.defaultBlockState();
        } else if (block != Blocks.SEA_PICKLE && block instanceof SpecialPlantable plantable) {
            alreadyPlaced = true;
            if (plantable.canPlacePlantAtPosition(stack, level, pos, null)) {
                plantable.spawnPlantAtPosition(stack, level, pos, null);
            }
        } else if (state.is(BlockTags.CAULDRONS)) {
            state = Blocks.CAULDRON.defaultBlockState();
        }
        if (level.dimensionType().ultraWarm() && state.getFluidState().is(FluidTags.WATER)) {
            level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F,
                2.6F + (level.random.nextFloat() - level.random.nextFloat()) * 0.8F);
            for (int i = 0; i < 8; i++) {
                level.addParticle(ParticleTypes.LARGE_SMOKE, pos.getX() + Math.random(), pos.getY() + Math.random(),
                    pos.getZ() + Math.random(), 0.0D, 0.0D, 0.0D);
            }
            Block.dropResources(state, level, pos);
            return;
        }
        if (!alreadyPlaced) {
            if (state.getBlock() instanceof BaseRailBlock) {
                placeRailWithoutUpdate(level, state, pos);
            } else {
                level.setBlock(pos, state, NO_SHAPE_UPDATES);
            }
            if (data != null) {
                BlockEntity entity = level.getBlockEntity(pos);
                if (entity != null) {
                    data.putInt("x", pos.getX());
                    data.putInt("y", pos.getY());
                    data.putInt("z", pos.getZ());
                    entity.loadWithComponents(data, level.registryAccess());
                }
            }
            try {
                state.getBlock().setPlacedBy(level, pos, state, null, stack);
            } catch (Exception ignored) {
                // The cannon places without a placer; a block that insists on one keeps what it already has.
            }
        }
    }

    private static void placeRailWithoutUpdate(ServerLevel level, BlockState state, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        int index = chunk.getSectionIndex(pos.getY());
        LevelChunkSection section = chunk.getSection(index);
        if (section == null) {
            section = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
            chunk.getSections()[index] = section;
        }
        BlockState old = section.setBlockState(SectionPos.sectionRelative(pos.getX()),
            SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()), state);
        chunk.setUnsaved(true);
        level.markAndNotifyBlock(pos, chunk, old, state, 82, 512);
        level.setBlock(pos, state, 82);
        level.neighborChanged(pos, level.getBlockState(pos.below()).getBlock(), pos.below());
    }
}
