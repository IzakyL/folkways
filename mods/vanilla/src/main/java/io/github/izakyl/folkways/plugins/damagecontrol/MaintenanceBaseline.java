package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.shell.structure.StructureSnapshots;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.plugins.build.MaintenanceOrders;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;

final class MaintenanceBaseline {
    private static final TagKey<Block> EXCLUDED = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways", "damage_control_excluded"));

    private MaintenanceBaseline() {
    }

    static boolean accepts(BlockState state) {
        // Only ordinary vanilla building blocks in the first format; machines and growing plants stay out.
        return !state.isAir() && !state.is(EXCLUDED) && !state.hasBlockEntity() && state.getFluidState().isEmpty()
            && !(state.getBlock() instanceof BushBlock)
            && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")
            && !FireSurvey.fire(state);
    }

    static StructureSnapshots.Snapshot record(ServerLevel level, ZoneView zone) {
        var snapshot = StructureSnapshots.capture(level, zone.at(zone.cells().iterator().next()), zone.cells(),
            MaintenanceBaseline::accepts);
        return new StructureSnapshots.Snapshot(snapshot.origin(),
            MaintenanceOrders.resolve(level, snapshot.cells()), 0);
    }
}
