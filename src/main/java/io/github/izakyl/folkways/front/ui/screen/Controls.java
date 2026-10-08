package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Switch;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import net.minecraft.network.chat.Component;

final class Controls {

    static final int NUMBER_WIDTH = 34;

    private static final int DIGIT = 6;
    private static final int NUMBER_PADDING = 10;

    static final int SWITCH_WIDTH = 22;
    static final int SWITCH_HEIGHT = 11;

    private Controls() {
    }

    static TextField count(int min, int max) {
        return ranged(new TextField(), min, max);
    }

    static TextField ranged(TextField field, int min, int max) {
        field.setNumbersOnlyInt(min, max);
        field.style(style -> style.appendTooltips(
            Component.translatable("folkways.settings.gesture.type"),
            Component.translatable("folkways.settings.gesture.wheel")));

        int room = widthFor(max);
        field.layout(layout -> layout.width(room).flexShrink(1).minWidth(0));
        return field;
    }

    private static int widthFor(int max) {
        int digits = Math.max(2, Integer.toString(Math.max(1, max)).length());
        return Math.min(NUMBER_WIDTH, digits * DIGIT + NUMBER_PADDING);
    }

    static Switch flag() {
        Switch toggle = new Switch();
        toggle.layout(layout -> layout.width(SWITCH_WIDTH).height(SWITCH_HEIGHT).flexShrink(0));
        toggle.style(style -> style.tooltips(
            Component.translatable("folkways.settings.gesture.toggle")));
        return toggle;
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
