package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.Cue;
import io.github.izakyl.folkways.front.api.ui.Cues;
import io.github.izakyl.folkways.front.api.ui.Rows;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class Controls {

    static final int NUMBER_WIDTH = 34;

    private static final int DIGIT = 6;
    private static final int NUMBER_PADDING = 10;


    private Controls() {
    }

    static TextField count(int min, int max) {
        TextField field = new TextField();
        Cues.on(field, Cue.of("folkways.keys.type", Cue.Key.DIGITS), Cue.of("folkways.keys.adjust", Cue.Key.WHEEL));
        return ranged(field, min, max);
    }

    static TextField ranged(TextField field, int min, int max) {
        field.setNumbersOnlyInt(min, max);
        field.textFieldStyle(style -> style.placeholder(Component.empty()));

        int room = widthFor(max);
        field.layout(layout -> layout.width(room).flexShrink(1).minWidth(0));
        return field;
    }

    private static int widthFor(int max) {
        int digits = Math.max(2, Integer.toString(Math.max(1, max)).length());
        return Math.min(NUMBER_WIDTH, digits * DIGIT + NUMBER_PADDING);
    }

    static Toggle tick() {
        Toggle box = new Toggle().noText();
        box.layout(layout -> layout.flexShrink(0));
        return box;
    }

    // What a number may be set between, said quietly beside its field.
    static Label range(int min, int max) {
        Label range = Rows.value(Component.translatable("folkways.draft.range", min, max));
        range.textStyle(style -> style.textColor(Rows.QUIET));
        return range;
    }

    // A choice's button wears the icon of what is picked before its name.
    static UIElement worn(ResourceLocation option) {
        return Rows.icon(ChoicePicker.iconOf(option));
    }

    static String stamped(String key, String value) {
        return key + "=" + value;
    }

    static String stampedKey(String stamped) {
        int at = stamped.indexOf('=');
        return at < 0 ? "" : stamped.substring(0, at);
    }

    static String stampedValue(String stamped) {
        int at = stamped.indexOf('=');
        return at < 0 ? "" : stamped.substring(at + 1);
    }

    static Integer parse(String typed) {
        try {
            return Integer.valueOf(typed.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static final class Echo {

        private final String[] seen;

        Echo(int slots) {
            seen = new String[slots];
        }

        boolean fresh(int slot, String text) {
            if (text.equals(seen[slot])) {
                return false;
            }
            seen[slot] = text;
            return true;
        }

        void forget(int slot) {
            seen[slot] = null;
        }
    }
}
