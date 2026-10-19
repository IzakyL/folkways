package io.github.izakyl.folkways.plugins.person.look;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

// One look worn by a resident who is in no world, turning slowly on the spot.
@OnlyIn(Dist.CLIENT)
final class LookPreview extends TransformTexture {

    private static final long TURN_MILLIS = 12_000L;
    private static final long TICK_MILLIS = 50L;
    // How many blocks of height the box has room for: a resident and some air over its head.
    private static final float BLOCKS_TALL = 2.4F;
    private static final float BLOCKS_WIDE = 1.6F;

    @Nullable
    private static ResidentEntity body;

    // Shared with every copy the styles make of this texture, so all of them wear what was last shown.
    private final ResourceLocation[] shown;

    private LookPreview(ResourceLocation[] shown) {
        this.shown = shown;
    }

    // Draws the preview in a box, and answers with the way to say which look it wears.
    static Consumer<ResourceLocation> in(UIElement box) {
        ResourceLocation[] shown = { null };
        box.style(style -> style.background(new LookPreview(shown)));
        return look -> shown[0] = look;
    }

    @Override
    protected void drawInternal(GuiGraphics graphics, float mouseX, float mouseY, float x, float y,
            float width, float height, float partialTicks) {
        ResourceLocation look = shown[0];
        ResidentEntity wearer = look == null ? null : wearer();
        if (wearer == null || width <= 0f || height <= 0f) {
            return;
        }
        wearer.setLookId(ResourceKey.create(ResidentLooks.REGISTRY, look));
        long now = Util.getMillis();
        float turned = 180f + (now % TURN_MILLIS) * 360f / TURN_MILLIS;
        wearer.tickCount = (int) (now / TICK_MILLIS);
        wearer.yBodyRot = turned;
        wearer.yBodyRotO = turned;
        wearer.setYRot(turned);
        wearer.yRotO = turned;
        wearer.yHeadRot = turned;
        wearer.yHeadRotO = turned;
        wearer.setXRot(0f);
        wearer.xRotO = 0f;

        float scale = Math.min(height / BLOCKS_TALL, width / BLOCKS_WIDE);
        graphics.enableScissor(Math.round(x), Math.round(y), Math.round(x + width), Math.round(y + height));
        InventoryScreen.renderEntityInInventory(graphics, x + width / 2f, y + height / 2f, scale,
            new Vector3f(0f, wearer.getBbHeight() / 2f, 0f),
            new Quaternionf().rotateZ((float) Math.PI), null, wearer);
        graphics.disableScissor();
    }

    // Every look this client knows of: the data packs' and the ones the server offered.
    static List<ResourceLocation> known() {
        var level = Minecraft.getInstance().level;
        return level == null ? List.of() : List.copyOf(ResidentLooks.pool(level.registryAccess()).keySet());
    }

    static Optional<Component> disabledReason(ResourceLocation look) {
        var level = Minecraft.getInstance().level;
        boolean model = level != null && ResidentLooks.entry(level.registryAccess(),
            ResourceKey.create(ResidentLooks.REGISTRY, look))
            .map(entry -> entry.appearance() instanceof ResidentLook.Model).orElse(false);
        return model && !ModList.get().isLoaded("geckolib")
            ? Optional.of(Component.translatable("folkways.looks.requires_geckolib")) : Optional.empty();
    }

    @Nullable
    private static ResidentEntity wearer() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            body = null;
        } else if (body == null || body.level() != level) {
            body = PersonBody.RESIDENT.get().create(level);
        }
        return body;
    }

    @Override
    public IGuiTexture copy() {
        return new LookPreview(shown);
    }
}
