package io.github.izakyl.folkways.core.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.HierarchicalStyleMatcher;
import com.lowdragmc.lowdraglib2.gui.ui.style.PropertyRegistry;
import com.lowdragmc.lowdraglib2.gui.ui.style.StyleRule;
import com.lowdragmc.lowdraglib2.gui.ui.style.StyleValue;
import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DeskTextures {

    private DeskTextures() {
    }

    public static boolean isDesk(ModularUI ui) {
        return ui != null && (Desk.on(ui) || ui.ui.getRootElement().hasClass("folkways_desk"));
    }

    public static void installPopups(UIElement root) {
        // Local rules reach dialogs added later too; LSS sprite() cannot repeat the paper at its own texel size.
        StyleValue<IGuiTexture> background = new StyleValue<>("folkways_core:desk_popup") {
            @Override
            protected IGuiTexture doCompute(String value) {
                return new GuiTextureGroup(paper(false),
                    SpriteTexture.of("folkways_core:textures/gui/desk/popup.png")
                        .setSprite(0, 0, 24, 24).setBorder(4));
            }
        };
        List<StyleRule> rules = new ArrayList<>();
        for (String selector : List.of(".__selector_dialog__", ".__search-component_dialog__", "menu",
                ".__dialog_content-container__", ".__dialog_button-container__")) {
            rules.add(new StyleRule(HierarchicalStyleMatcher.parse(".folkways_desk " + selector),
                Map.of(PropertyRegistry.BACKGROUND, background)));
        }
        root.addLocalStylesheet(new Stylesheet(rules));
    }

    static GuiTextureGroup panel() {
        // Paper repeats at its own texel size; only the oak rails stretch between the brass corners.
        return new GuiTextureGroup(paper(false),
            SpriteTexture.of("folkways_core:textures/gui/desk/panel.png").setSprite(0, 0, 48, 48).setBorder(10));
    }

    public static GuiTextureGroup combatPanel() {
        return new GuiTextureGroup(paper(false),
            SpriteTexture.of("folkways_core:textures/gui/desk/combat_panel.png").setSprite(0, 0, 48, 48).setBorder(10));
    }

    static SpriteTexture paper(boolean dark) {
        String name = dark ? "paper_dark" : "paper";
        return SpriteTexture.of("folkways_core:textures/gui/desk/" + name + ".png")
            .setSprite(0, 0, 64, 64).setWrapMode(SpriteTexture.WrapMode.REPEAT);
    }
}
