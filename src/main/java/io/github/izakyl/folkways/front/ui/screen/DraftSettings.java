package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

final class DraftSettings {
    private final Schema schema;
    private ColonySettings values;
    private final UIElement root = Rows.table();
    private final List<Runnable> collect = new ArrayList<>();

    DraftSettings(Schema schema) {
        this.schema = schema;
        values = ColonySettings.byDefault(schema);
    }

    UIElement build() {
        for (Schema.Setting setting : schema.settings()) {
            UIElement control;
            switch (setting) {
                case Schema.Setting.Count count -> {
                    var number = Controls.count(count.min(), count.max());
                    number.setValue(Integer.toString(count.byDefault()), false);
                    collect.add(() -> {
                        try {
                            values = values.with(count.key(), new ColonySettings.Value.Count(
                                Integer.parseInt(number.getValue())));
                        } catch (NumberFormatException ignored) { }
                    });
                    control = number;
                }
                case Schema.Setting.Flag flag -> {
                    Button button = new Button().setText(flagLabel(flag.key()));
                    button.setOnClick(event -> {
                        values = values.with(flag.key(), new ColonySettings.Value.Flag(!values.flag(flag.key())));
                        button.setText(flagLabel(flag.key()));
                    });
                    control = button;
                }
                case Schema.Setting.Choice choice -> {
                    Button button = new Button().setText(ChoicePicker.labelOf(choice.byDefault()));
                    button.setOnClick(event -> ChoicePicker.open(button, choice, values.choice(choice.key()),
                        option -> {
                            values = values.with(choice.key(), new ColonySettings.Value.Choice(option));
                            button.setText(ChoicePicker.labelOf(option));
                        }));
                    control = button;
                }
                case Schema.Setting.Items items -> control = Rows.value(Component.translatable(
                    "folkways.settings.entries", items.byDefault().size()));
            }
            control.layout(layout -> layout.flexShrink(0));
            Tokens.name(control, "folkways.draft.value." + setting.key());
            root.addChildren(Rows.row().addChildren(Rows.icon(SettingsPage.iconOf(setting.icon())),
                Rows.name(setting.name()), control));
        }
        root.setDisplay(false);
        return root;
    }

    void display(boolean shown) { root.setDisplay(shown && !schema.settings().isEmpty()); }

    ColonySettings settings() {
        collect.forEach(Runnable::run);
        return values.conformedTo(schema);
    }

    private Component flagLabel(String key) {
        return Component.translatable(values.flag(key) ? "folkways.action.on" : "folkways.action.off");
    }
}
