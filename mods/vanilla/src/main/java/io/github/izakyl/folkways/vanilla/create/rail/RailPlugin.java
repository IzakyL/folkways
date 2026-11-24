package io.github.izakyl.folkways.vanilla.create.rail;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.shell.create.rail.RailOperations;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitSnapshot;
import io.github.izakyl.folkways.vanilla.create.rail.domain.ConductorSeat;
import io.github.izakyl.folkways.vanilla.create.rail.domain.RailContent;
import io.github.izakyl.folkways.vanilla.create.rail.domain.RailHighlight;
import io.github.izakyl.folkways.vanilla.create.rail.domain.RailPresence;
import io.github.izakyl.folkways.vanilla.create.rail.domain.RailTooltip;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;

public final class RailPlugin {

    private RailPlugin() {
    }

    public static void install(IEventBus modBus) {
        RailOperations.install(new RailOperations() {
            @Override
            public Optional<TransitSnapshot> snapshot(Colony colony) {
                return colony.service(RailContent.ID, RailPresence.class).map(RailPresence::seen);
            }

            @Override
            public boolean holds(Colony colony, UUID train) {
                return colony.service(RailContent.ID, RailPresence.class).filter(rail -> rail.holds(train)).isPresent();
            }

            @Override
            public boolean handOver(Player player, InteractionHand hand, BlockPos pos,
                    AbstractContraptionEntity contraption) {
                return ConductorSeat.handle(player, hand, pos, contraption);
            }

            @Override
            public List<Component> crew(UUID train) {
                return RailTooltip.lines(train);
            }
        });
        modBus.addListener(Declaring.class, RailContent::declare);
        modBus.addListener(Registering.class, RailContent::enroll);
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(RailHighlight::install));
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(RailScene::declare);
        }
    }
}
