package io.github.izakyl.folkways.chiselsandbits;

import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Need;
import java.util.List;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

public final class CandBSchematicBridge implements StructureCompat.Handler {
    @Override
    public net.minecraft.resources.ResourceLocation id() {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways", "chiselsandbits");
    }
    public static boolean schematic(Level level) {
        return ModList.get().isLoaded("create") && CreateBridge.schematic(level);
    }

    public static ChiseledBlockEntity checked(BlockEntity entity) {
        if (!(entity instanceof ChiseledBlockEntity chiseled)
            || entity.getLevel() == null && !((SchematicBits) entity).folkways$valid()
            || entity.getLevel() != null && schematic(entity.getLevel()) && !((SchematicBits) entity).folkways$valid()) {
            throw StructureCompat.invalid();
        }
        try {
            CandBStructureCodec.counts(chiseled.payload().storage());
        } catch (RuntimeException invalid) {
            throw StructureCompat.invalid();
        }
        return chiseled;
    }

    @Override
    public BlockEntity detached(HolderLookup.Provider registries, BlockPos pos, BlockState state, StructurePayload payload) {
        ChiseledBlockEntity entity = new ChiseledBlockEntity(pos, state);
        CompoundTag data = payload.tag();
        data.putBoolean("folkways:schematic", true);
        entity.loadWithComponents(data, registries);
        checked(entity);
        return entity;
    }

    @Override
    public CompoundTag capture(BlockEntity entity) {
        return CandBStructureCodec.encode(checked(entity).payload().storage());
    }

    @Override
    public CompoundTag safe(BlockEntity entity) {
        var storage = checked(entity).payload().storage().createSnapshot();
        // C&B setPlacedBy turns a cannon shot half a turn because it has no player.
        CandBStructureCodec.turn(storage, Mirror.NONE, Rotation.CLOCKWISE_180);
        return CandBStructureCodec.encode(storage);
    }

    @Override
    public List<Need> materials(BlockEntity entity) {
        return CandBMaterials.of(checked(entity).payload().storage()).stream()
            .map(stack -> new Need(ItemSpec.exact(stack), stack.getCount())).toList();
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
