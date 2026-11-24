package io.github.izakyl.folkways.core.front.client.ponder;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.client.Lessons;
import io.github.izakyl.folkways.core.front.ui.screen.PageKinds.BookPage;
import io.github.izakyl.folkways.core.front.ui.screen.PageKinds;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PonderTabs {

    private static Map<String, BookPage> byToken;
    private static Screen watching;

    private PonderTabs() {
    }

    @SubscribeEvent
    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        aim(event.getScreen());
    }

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        PonderHint.renderPrompt(event.getGuiGraphics(), (int) event.getMouseX(), (int) event.getMouseY());
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        PonderHint.key(event.getKeyCode(), event.getScanCode(), true);
    }

    @SubscribeEvent
    public static void onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        PonderHint.key(event.getKeyCode(), event.getScanCode(), false);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen != watching) {
            watching = screen;
            PonderHint.clear();
        }
        PonderHint.tick();
    }

    private static void aim(Screen screen) {
        ModularUI ui = ModularUI.of(screen);
        if (ui == null) {
            PonderHint.clearHover();
            return;
        }
        Map<String, BookPage> pages = pages();
        for (UIElement element : ui.getLastHoveredElements()) {
            String token = element.getId();
            BookPage kind = token == null ? null : pages.get(token);
            if (kind != null) {
                ResourceLocation lesson = kind.lesson().orElse(kind.id());
                PonderHint.hover(Lessons.has(lesson) ? lesson : null,
                    Component.translatable(kind.nameKey()));
                return;
            }
        }
        PonderHint.clearHover();
    }

    private static Map<String, BookPage> pages() {
        if (byToken == null) {
            List<BookPage> declared = PageKinds.all();
            Map<String, BookPage> built = new LinkedHashMap<>();
            for (BookPage kind : declared) {
                built.put(kind.nameKey(), kind);
            }
            byToken = Map.copyOf(built);
        }
        return byToken;
    }
}
