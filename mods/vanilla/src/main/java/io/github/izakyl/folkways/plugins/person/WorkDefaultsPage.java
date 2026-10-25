package io.github.izakyl.folkways.plugins.person;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.resident.body.WorkPriorities;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.engine.colony.WorkDefaults;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.PriorityCells;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class WorkDefaultsPage implements Draw {

    @Override
    public UIElement build(Player player) {
        UIElement cells = PriorityCells.row();
        for (ResourceLocation trade : Vocations.ids()) {
            Label cell = PriorityCells.of(trade);
            Tokens.name(cell, "folkways.work_defaults." + Tokens.of(trade));
            cell.addServerEventListener(UIEvents.CLICK, event -> editDefault(player, trade, true, 0));
            cell.addServerEventListener(UIEvents.MOUSE_WHEEL,
                event -> editDefault(player, trade, false, (int) Math.signum(event.deltaY)));
            cell.addSyncValue(DataBindingBuilder.intValS2C(() -> authority(player)
                    .map(acting -> defaultStanding(acting.colony(), trade)).orElse(0))
                .onSyncReceived(standing -> PriorityCells.show(cell, standing)).build().getSyncValue());
            cells.addChildren(cell);
        }
        UIElement table = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthAuto()
            .minWidth(PriorityCells.WIDTH * Vocations.ids().size()).gapAll(1));
        table.addChildren(Rows.row().addChildren(PriorityCells.header(player.level().isClientSide())),
            Rows.row().addChildren(cells));
        ScrollerView matrix = Rows.wideBox();
        matrix.layout(layout -> layout.height((Rows.ROW_HEIGHT + 1) * 2 + 10).flexShrink(0));
        matrix.addScrollViewChild(table);
        return Rows.page().addChildren(Rows.note(Component.translatable("folkways.work_defaults.hint")), matrix);
    }

    private static int defaultStanding(Colony colony, ResourceLocation trade) {
        Participation participation = Participations.between(PersonBody.ID, Stake.vocation(trade));
        if (participation == Participation.NONE) {
            return 0;
        }
        WorkPriorities.Rule rule = WorkDefaults.of(colony, PersonBody.ID).of(trade);
        return rule.allowed() || participation == Participation.ALWAYS ? rule.priority().number() : -rule.priority().number();
    }

    static void editDefault(Player player, ResourceLocation trade, boolean toggle, int notches) {
        authority(player).ifPresent(acting -> {
            WorkPriorities.Rule current = WorkDefaults.of(acting.colony(), PersonBody.ID).of(trade);
            int level = Math.clamp(current.priority().number() + Integer.signum(notches), 1, 4);
            WorkDefaults.set(acting.colony(), PersonBody.ID, trade, toggle ? !current.allowed() : current.allowed(), level);
        });
    }

    private static Optional<ColonyAuthority> authority(Player player) {
        return player instanceof ServerPlayer serverPlayer
            ? ColonyAuthority.of(serverPlayer, Optional.empty())
            : Optional.empty();
    }
}
