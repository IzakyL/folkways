package io.github.izakyl.folkways.core.shell.create.rail;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

/** Colony railroad operations consulted by the native passenger and train adapters. */
public interface RailOperations {
    public static void install(RailOperations operations) {
        Holder.operations = operations;
    }

    public static RailOperations get() {
        return Holder.operations;
    }

    public default Optional<TransitSnapshot> snapshot(Colony colony) {
        return Optional.empty();
    }

    default boolean holds(Colony colony, UUID train) {
        return false;
    }

    default boolean handOver(Player player, InteractionHand hand, BlockPos pos,
            AbstractContraptionEntity contraption) {
        return false;
    }

    public default List<Component> crew(UUID train) {
        return List.of();
    }

    final class Holder {
        private static RailOperations operations = new RailOperations() {
        };

        private Holder() {
        }
    }
}
