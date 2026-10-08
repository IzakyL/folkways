package io.github.izakyl.folkways.plugins.build;

import com.simibubi.create.content.schematics.client.tools.ISchematicTool;
import com.simibubi.create.content.schematics.client.tools.SchematicToolBase;
import com.simibubi.create.foundation.gui.AllIcons;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Books;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

public final class CreateHandoffTool extends SchematicToolBase {

    public static final ISchematicTool TOOL = new CreateHandoffTool();

    private CreateHandoffTool() {
    }

    public static AllIcons icon() {
        return new HandOffIcon();
    }

    public static MutableComponent displayName() {
        return Component.translatable(BlueprintHandoffPanel.TOKEN + ".tool");
    }

    public static List<Component> description() {
        return List.of(
            Component.translatable(BlueprintHandoffPanel.TOKEN + ".description.0"),
            Component.translatable(BlueprintHandoffPanel.TOKEN + ".description.1"));
    }

    @Override
    public boolean handleRightClick() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        if (Books.heldColony(player).isEmpty()) {
            player.displayClientMessage(
                Component.translatable(BlueprintHandoffPanel.TOKEN + ".no_book"), true);
            return true;
        }
        BlueprintHandoffPanel.open(CreateBlueprintHandoff::handOff);
        return true;
    }

    @Override
    public boolean handleMouseWheel(double delta) {
        return false;
    }

    // Drawn in the ink of Create's own tool icons rather than taken from the book's item texture, so it sits in
    // their row as one of them.
    private static final class HandOffIcon extends AllIcons {
        private static final ResourceLocation GLYPH =
            ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "textures/gui/handoff_tool.png");

        private HandOffIcon() {
            super(0, 0);
        }

        @Override
        public void render(GuiGraphics graphics, int x, int y) {
            graphics.blit(GLYPH, x, y, 0, 0.0F, 0.0F, 16, 16, 16, 16);
        }
    }
}
