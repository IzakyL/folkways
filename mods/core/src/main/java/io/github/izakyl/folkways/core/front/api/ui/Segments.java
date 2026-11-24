package io.github.izakyl.folkways.core.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * A small single choice laid in place of the button that would open a {@link Picker}: its options side by side, oak
 * tags joined in a strip, the chosen one pressed in and inked red. One press takes an option.
 *
 * <p>A choice is laid so when it has at most {@link #MOST} options and their words fit the room it is given
 * ({@link #fits}). Where the screen is built on the server as well, only the count may decide ({@link #few}), since
 * the server knows neither the font nor the player's words.
 */
public final class Segments extends UIElement {

    /** The most options a choice may have to be laid in place. */
    public static final int MOST = 3;
    public static final float HEIGHT = 18;
    private static final float PAD = 5;
    private static final float ICON = 16 + 2;
    private static final String CHOSEN = "folkways_chosen";

    private final Map<String, Button> segments = new LinkedHashMap<>();
    private Consumer<String> picked;
    private @Nullable String current;

    /** Each option is named {@code token.<key>}, its key written as a picker row's is. */
    public Segments(String token, List<Picker.Row> rows, @Nullable String current, Consumer<String> picked) {
        this.picked = picked;
        layout(layout -> layout.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(1)
            .flexShrink(1).minWidth(0));
        Tokens.name(this, token);
        for (Picker.Row row : rows) {
            Button press = new Button().setText(row.label());
            if (!row.icon().isEmpty()) {
                press.addPreIcon(new ItemStackTexture(row.icon()));
            }
            press.layout(layout -> layout.height(HEIGHT).paddingHorizontal(PAD).flexShrink(1).minWidth(0));
            press.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL));
            press.style(style -> style.tooltips(row.label()));
            press.setOnClick(event -> take(row.key()));
            Tokens.name(press, token + "." + keyToken(row.key()));
            segments.put(row.key(), press);
            addChildren(press);
        }
        mark(current);
    }

    /** Who is told of each option taken, in place of the one given at first. */
    public Segments onPick(Consumer<String> taken) {
        picked = taken;
        return this;
    }

    /** Whether a choice has few enough options to be laid in place, whatever its words. */
    public static boolean few(int options) {
        return options >= 2 && options <= MOST;
    }

    /** Whether a choice is few enough, and its words short enough, to be laid in {@code room}; client only. */
    public static boolean fits(List<Picker.Row> rows, float room) {
        return few(rows.size()) && width(rows) <= room;
    }

    /** How wide the strip stands with every option's words whole; client only. */
    public static float width(List<Picker.Row> rows) {
        var font = Minecraft.getInstance().font;
        float wide = rows.size() - 1;
        for (Picker.Row row : rows) {
            wide += 2 * PAD + font.width(row.label()) + (row.icon().isEmpty() ? 0 : ICON);
        }
        return wide;
    }

    /** Marks the option chosen, as the owner learns it; an unknown key marks none. */
    public void mark(@Nullable String key) {
        current = key;
        segments.forEach((each, press) -> {
            if (each.equals(key)) {
                press.addClass(CHOSEN);
            } else {
                press.removeClass(CHOSEN);
            }
        });
    }

    public @Nullable String current() {
        return current;
    }

    private void take(String key) {
        if (key.equals(current)) {
            return;
        }
        mark(key);
        picked.accept(key);
    }

    static String keyToken(String key) {
        return key.replace("#", "").replace(':', '.').replace('/', '.');
    }
}
