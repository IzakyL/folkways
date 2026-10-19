import dev.blockwright.api.Context;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ViewportEvent;

public final class Task {
    private static boolean installed;
    private static int calls;
    public static Object run(Context ctx) {
        var options = ((Minecraft) ctx.client()).options;
        options.hideGui = true;
        options.renderDistance().set(20);
        options.fov().set(55);
        if (!installed) {
            NeoForge.EVENT_BUS.addListener((ViewportEvent.RenderFog event) -> {
                calls++;
                event.setNearPlaneDistance(512);
                event.setFarPlaneDistance(1024);
                event.setCanceled(true);
            });
            installed = true;
        }
        return Map.of("ok", true, "renderDistance", options.renderDistance().get(), "calls", calls,
            "fogEnd", com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd());
    }
}
