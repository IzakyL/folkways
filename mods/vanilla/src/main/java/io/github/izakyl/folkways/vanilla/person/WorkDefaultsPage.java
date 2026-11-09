package io.github.izakyl.folkways.vanilla.person;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.resident.api.WorkPriorities;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.PriorityCells;
import io.github.izakyl.folkways.core.front.api.ui.PriorityTable;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.engine.authority.ColonyAuthority;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class WorkDefaultsPage implements Draw {

    @Override
    public UIElement build(Player player) {
        PriorityTable columns = new PriorityTable("folkways.work_defaults", player.level().isClientSide());
        List<ResourceLocation> trades = Vocations.ids();
        int[] standings = new int[trades.size()];
        Label[] groups = new Label[columns.columns().size()];
        UIElement cells = columns.row(index -> {
            ResourceLocation trade = trades.get(index);
            Label cell = PriorityCells.of(trade);
            Tokens.name(cell, "folkways.work_defaults." + Tokens.of(trade));
            cell.addServerEventListener(UIEvents.CLICK, event -> editDefault(player, trade, true, 0));
            cell.addServerEventListener(UIEvents.MOUSE_WHEEL,
                event -> editDefault(player, trade, false, (int) Math.signum(event.deltaY)));
            cell.addSyncValue(DataBindingBuilder.intValS2C(() -> authority(player)
                    .map(acting -> defaultStanding(acting.colony(), trade)).orElse(0))
                .onSyncReceived(standing -> {
                    PriorityCells.show(cell, standing);
                    standings[index] = standing;
                    // The group's own cell follows its vocations, so it needs nothing synced of its own.
                    for (int run = 0; run < groups.length; run++) {
                        if (groups[run] != null) {
                            columns.show(groups[run], PriorityTable.of(columns.columns().get(run), standings));
                        }
                    }
                }).build().getSyncValue());
            return cell;
        }, run -> {
            PriorityTable.Run group = columns.columns().get(run);
            List<ResourceLocation> members = group.members().stream().map(Vocation::id).toList();
            Label cell = columns.groupCell(run);
            Tokens.name(cell, "folkways.work_defaults.group." + Tokens.of(group.group().id()));
            cell.addServerEventListener(UIEvents.CLICK, event -> editGroup(player, members, true, 0));
            cell.addServerEventListener(UIEvents.MOUSE_WHEEL,
                event -> editGroup(player, members, false, (int) Math.signum(event.deltaY)));
            groups[run] = cell;
            return cell;
        });
        UIElement table = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthAuto()
            .minWidth(columns.width()).gapAll(1));
        columns.onResize(() -> table.layout(layout -> layout.minWidth(columns.width())));
        table.addChildren(columns.band(0), columns.header(), Rows.row().addChildren(cells));
        ScrollerView matrix = Rows.wideBox();
        matrix.layout(layout -> layout.height((Rows.ROW_HEIGHT + 1) * 2 + PriorityTable.GROUP_HEIGHT + 11).flexShrink(0));
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

    // Sets every vocation of a group future residents take up alike; each keeps its own rule.
    static void editGroup(Player player, List<ResourceLocation> members, boolean toggle, int notches) {
        authority(player).ifPresent(acting -> {
            WorkPriorities defaults = WorkDefaults.of(acting.colony(), PersonBody.ID);
            List<ResourceLocation> taken = members.stream().filter(trade -> Participations.between(PersonBody.ID,
                Stake.vocation(trade)) != Participation.NONE).toList();
            if (taken.isEmpty()) {
                return;
            }
            boolean allow = PriorityTable.allowAll(taken.stream().map(trade -> defaultStanding(acting.colony(), trade) > 0).toList());
            int level = PriorityTable.rankAll(taken.stream().map(trade -> defaults.of(trade).priority().number()).toList(), notches);
            for (ResourceLocation trade : taken) {
                WorkPriorities.Rule current = defaults.of(trade);
                WorkDefaults.set(acting.colony(), PersonBody.ID, trade, toggle ? allow : current.allowed(),
                    toggle ? current.priority().number() : level);
            }
        });
    }

    private static Optional<ColonyAuthority> authority(Player player) {
        return player instanceof ServerPlayer serverPlayer
            ? ColonyAuthority.of(serverPlayer, Optional.empty())
            : Optional.empty();
    }
}
