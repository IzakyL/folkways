package io.github.izakyl.folkways.chiselsandbits;

import com.simibubi.create.api.contraption.transformable.MovedBlockTransformerRegistries;
import com.simibubi.create.api.schematic.nbt.SafeNbtWriterRegistry;
import com.simibubi.create.api.schematic.requirement.SchematicRequirementRegistries;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import java.util.List;
import mod.chiselsandbits.block.ChiseledBlock;
import mod.chiselsandbits.registrars.ModBlockEntityTypes;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

final class CreateBridge {
    private CreateBridge() {
    }

    static boolean schematic(Level level) {
        return level instanceof SchematicLevel;
    }

    static void install(CandBSchematicBridge bridge) {
        for (var block : BuiltInRegistries.BLOCK) {
            if (block instanceof ChiseledBlock) {
                SchematicRequirementRegistries.BLOCKS.register(block, (state, entity) -> {
                    try {
                        List<ItemRequirement.StackRequirement> bits = CandBMaterials.of(CandBSchematicBridge.checked(entity)).stream()
                            .<ItemRequirement.StackRequirement>map(BitRequirement::new).toList();
                        return new ItemRequirement(bits);
                    } catch (RuntimeException invalid) {
                        return refused();
                    }
                });
            }
        }
        var type = ModBlockEntityTypes.CHISELED.get();
        SafeNbtWriterRegistry.REGISTRY.register(type, (entity, tag, registries) -> tag.merge(bridge.safe(entity)));
        MovedBlockTransformerRegistries.BLOCK_ENTITY_TRANSFORMERS.register(type, (entity, transform) -> {
            if (entity instanceof SchematicBits bits && !bits.folkways$valid()
                && (entity.getLevel() == null || schematic(entity.getLevel()))) return;
            if (transform.rotationAxis != null && transform.rotationAxis != Direction.Axis.Y) {
                var storage = CandBSchematicBridge.checked(entity).payload().storage().createSnapshot();
                if (transform.mirror != null && transform.mirror != net.minecraft.world.level.block.Mirror.NONE) {
                    storage.mirror(transform.mirror == net.minecraft.world.level.block.Mirror.FRONT_BACK
                        ? Direction.Axis.X : Direction.Axis.Z);
                }
                storage.rotate(transform.rotationAxis, Math.floorMod(transform.angle / 90, 4));
                entity.loadWithComponents(CandBStructureCodec.encode(storage), entity.getLevel() == null
                    ? net.minecraft.core.RegistryAccess.EMPTY : entity.getLevel().registryAccess());
                return;
            }
            bridge.turn(entity, transform.mirror, transform.rotation);
        });
    }

    private static ItemRequirement refused() {
        // A broken cell must stop the cannon for missing materials, rather than be skipped as INVALID or NONE.
        return new ItemRequirement(new ItemRequirement.StackRequirement(new ItemStack(Items.BARRIER),
            ItemRequirement.ItemUseType.DAMAGE) {
            @Override
            public boolean matches(ItemStack stack) {
                return false;
            }
        });
    }
}
