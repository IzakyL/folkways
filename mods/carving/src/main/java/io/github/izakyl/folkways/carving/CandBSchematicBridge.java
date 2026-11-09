package io.github.izakyl.folkways.carving;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.world.api.StructurePayload;
import java.util.List;
import java.util.Optional;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class CandBSchematicBridge implements StructureCompat.Handler {
    @Override
    public ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath("folkways_carving", "chiselsandbits");
    }

    @Override
    public StructureCompat.Processing processing() {
        return CandBStructureCodec.processing();
    }

    @Override
    public void inspect(StructurePayload payload) {
        voxels(payload);
    }

    @Override
    public void inspectPlacement(StructurePayload payload) {
        CandBStructureCodec.placement(payload.tag());
    }

    public static ChiseledBlockEntity checked(BlockEntity entity) {
        if (!(entity instanceof ChiseledBlockEntity chiseled)
            || entity.getLevel() == null && !((SchematicBits) entity).folkways$valid()) {
            throw StructureCompat.invalid();
        }
        return chiseled;
    }

    @Override
    public BlockEntity detached(HolderLookup.Provider registries, BlockPos pos, BlockState state, StructurePayload payload) {
        ChiseledBlockEntity entity = new ChiseledBlockEntity(pos, state);
        CompoundTag data = payload.tag();
        data.putBoolean("folkways_carving:schematic", true);
        entity.loadWithComponents(data, registries);
        checked(entity);
        return entity;
    }

    @Override
    public CompoundTag capture(BlockEntity entity) {
        return CandBStructureCodec.encode(checked(entity).payload().storage());
    }

    @Override
    public CompoundTag placementData(HolderLookup.Provider registries, BlockPos pos, BlockState state,
            StructurePayload payload) {
        CompoundTag source = payload.tag();
        CompoundTag ready = new CompoundTag();
        ready.put("data", source.get("data").copy());
        // Imported native NBT need not carry our marker. Live placement must still read synchronously;
        // only native voxel data and resolution travel on, never arbitrary entity components or inventories.
        ready.putBoolean("folkways_carving:schematic", true);
        if (source.contains("folkways_carving:resolution")) {
            ready.putInt("folkways_carving:resolution", source.getInt("folkways_carving:resolution"));
        }
        return ready;
    }

    @Override
    public List<Need> materials(BlockEntity entity) {
        var volume = voxels(entity);
        return volume.counts().entrySet().stream()
            .map(entry -> new Need(entry.getKey(), (entry.getValue() + volume.perBlock() - 1) / volume.perBlock()))
            .toList();
    }

    @Override
    public boolean carves() {
        return true;
    }

    @Override
    public StructureCompat.Voxels voxels(StructurePayload payload) {
        try {
            var decoded = CandBStructureCodec.validated(payload.tag());
            return decoded.volume();
        } catch (StructureCompat.Failure failed) {
            throw failed;
        } catch (RuntimeException failed) {
            throw StructureCompat.invalid();
        }
    }

    @Override
    public StructureCompat.Voxels voxels(BlockEntity entity) {
        try {
            var carving = checked(entity);
            int size = carving.payload().storage().getSize();
            var cached = ((SchematicBits) carving).folkways$counts();
            return CandBMaterials.volume(cached.isEmpty() ? CandBStructureCodec.counts(carving.payload().storage()) : cached,
                size * size * size);
        } catch (StructureCompat.Failure failed) {
            throw failed;
        } catch (RuntimeException failed) {
            throw StructureCompat.invalid();
        }
    }

    @Override
    public Optional<ItemSpec> solid(BlockState state) {
        if (state.isAir() || !new CandBVoxelizer().supports(state)) {
            return Optional.empty();
        }
        return Optional.of(ItemSpec.exact(new ItemStack(state.getBlock())));
    }

    @Override
    public boolean matches(BlockEntity entity, StructurePayload payload) {
        try {
            if (!(entity instanceof ChiseledBlockEntity carving)) {
                return false;
            }
            return CandBStructureCodec.validated(payload.tag()).matches(checked(carving).payload().storage());
        } catch (StructureCompat.Failure failed) {
            throw failed;
        } catch (RuntimeException failed) {
            throw StructureCompat.invalid();
        }
    }

    @Override
    public double changed(BlockEntity entity, StructurePayload payload) {
        try {
            return CandBStructureCodec.validated(payload.tag()).changed(checked(entity).payload().storage());
        } catch (StructureCompat.Failure failed) {
            throw failed;
        } catch (RuntimeException failed) {
            throw StructureCompat.invalid();
        }
    }

    @Override
    public StructurePayload transformed(StructurePayload payload, Mirror mirror, Rotation rotation) {
        var storage = CandBStructureCodec.decode(payload.tag());
        CandBStructureCodec.turn(storage, mirror, rotation);
        return new StructurePayload(CandBStructureCodec.encode(storage));
    }

    @Override
    public void turn(BlockEntity entity, Mirror mirror, Rotation rotation) {
        var storage = checked(entity).payload().storage().createSnapshot();
        CandBStructureCodec.turn(storage, mirror, rotation);
        // Loading the replacement stays synchronous and never attaches a temporary entity to the server level.
        entity.loadWithComponents(CandBStructureCodec.encode(storage),
            entity.getLevel() == null ? net.minecraft.core.RegistryAccess.EMPTY : entity.getLevel().registryAccess());
    }
}
