package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Switch;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.CoreSettings;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySchemas;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.front.engine.colony.ColonyZone;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class SettingsPage implements Draw {

    static final int CONTROL_MIN = 28;

    @FunctionalInterface
    public interface ItemListEditor {
        void open(Player player, ResourceLocation scope, String key);
    }

    public sealed interface Scope {

        record AtColony(ResourceLocation scope) implements Scope {
        }

        record OnPath(UUID path, ResourceLocation delegation) implements Scope {
        }

        record OnZone(UUID zone, ResourceLocation delegation) implements Scope {
        }
    }

    private static volatile ItemListEditor editor = (player, scope, key) -> {
    };

    private SettingsPage() {
    }

    public static SettingsPage of() {
        return new SettingsPage();
    }

    public static void install(ItemListEditor installed) {
        editor = installed;
    }

    @Override
    public UIElement build(Player player) {
        UIElement table = Rows.table();
        for (Schema.Setting setting : CoreSettings.schema().settings()) {
            table.addChildren(Rows.row().addChildren(
                Rows.icon(iconOf(setting.icon())),
                Rows.name(setting.name()),
                control(player, CoreSettings.CORE, setting)));
        }

        Button resetLayout = new Button()
            .setText(Component.translatable("folkways.settings.reset_layout"))
            .setOnClick(event -> Desk.resetLayout());
        Tokens.name(resetLayout, "folkways.settings.reset_layout");
        table.addChildren(Rows.row().addChildren(
            Rows.icon(ItemStack.EMPTY),
            Rows.name(Component.translatable("folkways.settings.layout")),
            resetLayout));

        Razing razing = new Razing();
        table.addChildren(Rows.row().addChildren(
            Rows.icon(ItemStack.EMPTY),
            Rows.name(Component.translatable("folkways.settings.colony")),
            razing.asker()));

        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(table);

        UIElement page = Rows.page().addChildren(scroller, razing.confirmation(player, scroller));
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        return page;
    }

    static UIElement control(Player player, ResourceLocation scope, Schema.Setting setting) {
        Scope where = new Scope.AtColony(scope);
        UIElement control = switch (setting) {
            case Schema.Setting.Items items -> opener(player, scope, items);
            case Schema.Setting.Count count -> {
                TextField field = Controls.count(count.min(), count.max());
                field.bind(DataBindingBuilder.string(
                    () -> Integer.toString(countOf(player, where, count)),
                    typed -> setCount(player, where, count, typed)).build());
                yield field;
            }
            case Schema.Setting.Choice choice -> picker(player, where, choice);
            case Schema.Setting.Flag flag -> {
                Switch toggle = Controls.flag();
                toggle.bind(DataBindingBuilder.bool(
                    () -> flagOf(player, where, flag),
                    on -> setFlag(player, where, flag, on)).build());
                yield toggle;
            }
        };
        Tokens.name(control, (setting instanceof Schema.Setting.Items
            ? "folkways.settings.open." : "folkways.settings.value.") + setting.key());
        return control;
    }

    private static UIElement picker(Player player, Scope where, Schema.Setting.Choice choice) {
        Label shown = Rows.value(Component.empty());
        shown.bind(DataBindingBuilder.componentS2C(() -> label(player, where, choice)).build());
        Button press = new Button().noText();
        press.layout(layout -> layout.widthAuto().minWidth(CONTROL_MIN).flexShrink(0));
        press.addChildren(shown);
        ResourceLocation[] current = { choice.byDefault() };
        press.addSyncValue(DataBindingBuilder.stringS2C(() -> choiceOf(player, where, choice).toString())
            .onSyncReceived(id -> {
                ResourceLocation parsed = ResourceLocation.tryParse(id);
                if (parsed != null) {
                    current[0] = parsed;
                }
            }).build().getSyncValue());
        ChoicePicker.Asking asking = new ChoicePicker.Asking(press, (key, option) -> {
            if (key.equals(choice.key())) {
                setChoice(player, where, choice, option);
            }
        });
        press.setOnClick(event -> ChoicePicker.open(press, choice, current[0],
            option -> asking.ask(choice.key(), option)));
        return press;
    }

    private static UIElement opener(Player player, ResourceLocation scope,
            Schema.Setting.Items items) {
        Label shown = Rows.value(Component.empty());
        shown.bind(DataBindingBuilder.componentS2C(() -> label(player, scope, items)).build());
        Button press = new Button().noText();
        press.layout(layout -> layout.widthAuto().minWidth(CONTROL_MIN).flexShrink(0));
        press.addChildren(shown);
        press.setOnClick(event -> editor.open(player, scope, items.key()));
        press.setOnServerClick(event -> editor.open(player, scope, items.key()));
        return press;
    }

    static Component label(Player player, ResourceLocation scope, Schema.Setting setting) {
        return label(player, new Scope.AtColony(scope), setting);
    }

    static Component label(Player player, Scope where, Schema.Setting setting) {
        ColonySettings held = filled(player, where).orElse(null);
        if (held == null) {
            return Component.empty();
        }
        return switch (setting) {
            case Schema.Setting.Flag flag -> Component.translatable(
                flagOf(held, flag) ? "folkways.action.on" : "folkways.action.off");
            case Schema.Setting.Count count -> Component.literal(Integer.toString(countOf(held, count)));
            case Schema.Setting.Choice choice -> ChoicePicker.labelOf(choiceOf(held, choice));
            case Schema.Setting.Items items -> Component.translatable("folkways.settings.entries",
                held.entries(items.key()).size());
        };
    }

    static void setCount(Player player, Scope where, Schema.Setting.Count count, String typed) {
        Integer wanted = Controls.parse(typed);
        if (wanted == null) {
            return;
        }
        put(player, where, count, count.key(), new ColonySettings.Value.Count(
            Math.max(count.min(), Math.min(count.max(), wanted))));
    }

    static void setChoice(Player player, Scope where, Schema.Setting.Choice choice, ResourceLocation option) {
        if (choice.options().contains(option)) {
            put(player, where, choice, choice.key(), new ColonySettings.Value.Choice(option));
        }
    }

    static void setItem(Player player, Scope where, Schema.Setting.Items items, ResourceLocation item) {
        if (BuiltInRegistries.ITEM.containsKey(item)) {
            put(player, where, items, items.key(),
                new ColonySettings.Value.Items(List.of(ItemFilter.item(item))));
        }
    }

    static void setFlag(Player player, Scope where, Schema.Setting.Flag flag, boolean on) {
        put(player, where, flag, flag.key(), new ColonySettings.Value.Flag(on));
    }

    private static void put(Player player, Scope where, Schema.Setting setting, String key,
            ColonySettings.Value value) {
        ColonySettings held = filled(player, where).orElse(null);
        if (held == null || value.equals(current(held, setting))) {
            return;
        }
        Pages.colonyOf(player).ifPresent(colony -> write(colony, where, key, value));
    }

    private static ColonySettings.Value current(ColonySettings held, Schema.Setting setting) {
        return switch (setting) {
            case Schema.Setting.Flag flag -> new ColonySettings.Value.Flag(flagOf(held, flag));
            case Schema.Setting.Count count -> new ColonySettings.Value.Count(countOf(held, count));
            case Schema.Setting.Choice choice -> new ColonySettings.Value.Choice(choiceOf(held, choice));
            case Schema.Setting.Items ignored -> null;
        };
    }

    static int countOf(Player player, Scope where, Schema.Setting.Count count) {
        return filled(player, where).map(held -> countOf(held, count)).orElse(count.byDefault());
    }

    static ResourceLocation choiceOf(Player player, Scope where, Schema.Setting.Choice choice) {
        return filled(player, where).map(held -> choiceOf(held, choice)).orElse(choice.byDefault());
    }

    static boolean flagOf(Player player, Scope where, Schema.Setting.Flag flag) {
        return filled(player, where).map(held -> flagOf(held, flag)).orElse(flag.byDefault());
    }

    private static Optional<ColonySettings> filled(Player player, Scope where) {
        return Pages.colonyOf(player).flatMap(colony -> switch (where) {
            case Scope.AtColony at -> Optional.of(ColonyFront.of(colony).settings(at.scope()));
            case Scope.OnZone on -> zoneOf(colony, on.zone()).map(ColonyZone::settings);
            case Scope.OnPath on -> ColonyFront.of(colony).path(on.path()).map(io.github.izakyl.folkways.front.engine.colony.ColonyPath::settings);
        });
    }

    private static void write(Colony colony, Scope where, String key, ColonySettings.Value value) {
        ColonyFront front = ColonyFront.of(colony);
        switch (where) {
            case Scope.AtColony at -> front.setSetting(at.scope(), key, value);
            case Scope.OnPath on -> front.path(on.path()).ifPresent(path -> {
                Schema schema = schemaOf(on.delegation());
                if (schema.find(key).isPresent()) {
                    front.setDrawn(path.id(), path.settings().with(key, value).conformedTo(schema));
                }
            });
            case Scope.OnZone on -> front.zone(on.zone()).ifPresent(zone -> {
                Schema schema = schemaOf(on.delegation());
                if (schema.find(key).isPresent()) {
                    front.setDrawn(zone.id(), zone.settings().with(key, value).conformedTo(schema));
                }
            });
        }
    }

    static Schema schemaOf(ResourceLocation delegation) {
        return ColonySchemas.ofDelegation(delegation);
    }

    static Optional<ColonyZone> zoneOf(Colony colony, UUID id) {
        return ColonyFront.of(colony).zone(id);
    }

    private static boolean flagOf(ColonySettings held, Schema.Setting.Flag flag) {
        return held.valueOf(flag.key()).orElse(null) instanceof ColonySettings.Value.Flag set
            ? set.value() : flag.byDefault();
    }

    private static int countOf(ColonySettings held, Schema.Setting.Count count) {
        return held.valueOf(count.key()).orElse(null) instanceof ColonySettings.Value.Count set
            ? set.value() : count.byDefault();
    }

    private static ResourceLocation choiceOf(ColonySettings held, Schema.Setting.Choice choice) {
        return held.valueOf(choice.key()).orElse(null) instanceof ColonySettings.Value.Choice set
            ? set.value() : choice.byDefault();
    }

    static ItemStack iconOf(ResourceLocation id) {
        return new ItemStack(BuiltInRegistries.ITEM.getOptional(id).orElse(Items.BARRIER));
    }
}
