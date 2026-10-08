package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public record Blueprint(UUID id, String name, List<BlueprintBlock> blocks, List<UnloadedBlock> unloaded,
        List<Joint> joints, BlueprintVolume volume, long createdGameTime) {
    private static final String TAG_ID = "id";
    private static final String TAG_NAME = "name";
    private static final String TAG_BLOCKS = "blocks";
    private static final String TAG_SIZE = "size";
    private static final String TAG_CREATED_GAME_TIME = "createdGameTime";
    private static final String TAG_JOINTS = "joints";
    private static final String TAG_FROM = "from";
    private static final String TAG_TO = "to";
    private static final int MAX_NAME_LENGTH = 64;

    public Blueprint {
        id = Objects.requireNonNull(id);
        name = normalizeName(name);
        blocks = blocks.stream()
            .sorted(Comparator.comparingInt((BlueprintBlock block) -> block.offset().getY())
                .thenComparingInt(block -> block.offset().getX())
                .thenComparingInt(block -> block.offset().getZ()))
            .toList();
        unloaded = List.copyOf(unloaded);
        joints = List.copyOf(joints);
        volume = Objects.requireNonNull(volume);
    }

    public Blueprint(UUID id, String name, List<BlueprintBlock> blocks, List<UnloadedBlock> unloaded,
            BlueprintVolume volume, long createdGameTime) {
        this(id, name, blocks, unloaded, List.of(), volume, createdGameTime);
    }

    public Blueprint(UUID id, String name, List<BlueprintBlock> blocks, BlueprintVolume volume, long createdGameTime) {
        this(id, name, blocks, List.of(), List.of(), volume, createdGameTime);
    }

    public Optional<Blueprint> transformed(Rotation rotation, Mirror mirror) {
        if (rotation == Rotation.NONE && mirror == Mirror.NONE) {
            return Optional.of(this);
        }
        Vec3i size = volume.size();
        BlockPos near = StructureTemplate.transform(BlockPos.ZERO, mirror, rotation, BlockPos.ZERO);
        BlockPos far = StructureTemplate.transform(
            new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1), mirror, rotation, BlockPos.ZERO);
        BlockPos corner = BlockPos.min(near, far);
        Function<BlockPos, BlockPos> moved = offset ->
            StructureTemplate.transform(offset, mirror, rotation, BlockPos.ZERO).subtract(corner);
        List<BlueprintBlock> turned = blocks.stream()
            .map(cell -> new BlueprintBlock(moved.apply(cell.offset()), cell.state().mirror(mirror).rotate(rotation)))
            .toList();
        List<UnloadedBlock> turnedUnloaded = unloaded.stream()
            .map(cell -> cell.turned(moved.apply(cell.offset()), mirror, rotation))
            .toList();
        List<Joint> turnedJoints = joints.stream()
            .map(joint -> new Joint(moved.apply(joint.from()), moved.apply(joint.to())))
            .toList();
        boolean swapsAxes = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        return BlueprintVolume
            .of(swapsAxes ? size.getZ() : size.getX(), size.getY(), swapsAxes ? size.getX() : size.getZ())
            .map(space -> new Blueprint(id, name, turned, turnedUnloaded, turnedJoints, space, createdGameTime));
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        Vec3i size = volume.size();
        List<CompoundTag> cells = Stream.concat(blocks.stream().map(block -> block.save(provider)),
            unloaded.stream().map(UnloadedBlock::save)).toList();
        return Writer.of()
            .uuid(TAG_ID, id)
            .string(TAG_NAME, name)
            .longValue(TAG_CREATED_GAME_TIME, createdGameTime)
            .intArray(TAG_SIZE, new int[] {size.getX(), size.getY(), size.getZ()})
            .children(TAG_BLOCKS, cells, Function.identity())
            .children(TAG_JOINTS, joints, joint -> Writer.of()
                .blockPos(TAG_FROM, joint.from())
                .blockPos(TAG_TO, joint.to())
                .tag())
            .tag();
    }

    public static Optional<Blueprint> load(Reader reader, HolderLookup.Provider provider) {
        Optional<UUID> id = reader.uuid(TAG_ID);
        HolderGetter<Block> known = provider.lookupOrThrow(Registries.BLOCK);
        List<BlueprintBlock> blocks = new ArrayList<>();
        List<UnloadedBlock> unloaded = new ArrayList<>();
        for (Reader cell : reader.children(TAG_BLOCKS)) {
            Optional<BlockPos> offset = cell.blockPos(BlueprintBlock.TAG_OFFSET);
            Optional<CompoundTag> state = cell.blob(BlueprintBlock.TAG_STATE);
            if (offset.isEmpty() || state.isEmpty()) {
                continue;
            }
            UnloadedBlock written = new UnloadedBlock(offset.get(), state.get(), UnloadedBlock.turnsOf(cell));
            BlueprintBlock.read(known, state.get()).ifPresentOrElse(
                read -> blocks.add(new BlueprintBlock(offset.get(), written.turn(read))),
                () -> unloaded.add(written));
        }
        if (id.isEmpty() || blocks.isEmpty() && unloaded.isEmpty()) {
            return Optional.empty();
        }
        List<Joint> joints = new ArrayList<>();
        for (Reader joint : reader.children(TAG_JOINTS)) {
            Optional<BlockPos> from = joint.blockPos(TAG_FROM);
            Optional<BlockPos> to = joint.blockPos(TAG_TO);
            if (from.isPresent() && to.isPresent() && !from.get().equals(to.get())) {
                joints.add(new Joint(from.get(), to.get()));
            }
        }
        List<BlockPos> offsets = new ArrayList<>();
        blocks.forEach(block -> offsets.add(block.offset()));
        unloaded.forEach(block -> offsets.add(block.offset()));
        Optional<BlueprintVolume> volume = reader.intArray(TAG_SIZE)
            .filter(stored -> stored.length == 3)
            .map(stored -> BlueprintVolume.of(stored[0], stored[1], stored[2]))
            .orElseGet(() -> BlueprintVolume.around(offsets));
        return volume.map(parsed -> new Blueprint(id.get(), reader.string(TAG_NAME).orElse(""), blocks, unloaded,
            joints, parsed, reader.longValue(TAG_CREATED_GAME_TIME).orElse(0L)));
    }

    private static String normalizeName(String value) {
        String normalized = value == null || value.isBlank() ? "blueprint" : value.trim();
        if (normalized.length() > MAX_NAME_LENGTH) {
            return normalized.substring(0, MAX_NAME_LENGTH);
        }
        return normalized;
    }
}
