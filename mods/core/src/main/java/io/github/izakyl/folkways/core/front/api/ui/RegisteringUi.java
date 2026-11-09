package io.github.izakyl.folkways.core.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

public final class RegisteringUi extends Event implements IModBusEvent {

    public interface Registry {
        void page(ResourceLocation page, Supplier<? extends Draw> factory);
        void inlay(Bay bay, ResourceLocation owner, Supplier<? extends Inlay> factory);
        void outline(Outline outline);
    }

    private final Registry registry;
    private boolean open = true;

    public RegisteringUi(Registry registry) {
        this.registry = registry;
    }

    public void close() {
        open = false;
    }

    private void writing() {
        if (!open) {
            throw new IllegalStateException("UI registered after RegisteringUi completed");
        }
    }

    public void page(ResourceLocation page, Supplier<? extends Draw> factory) {
        writing();
        registry.page(page, factory);
    }

    public void inlay(Bay bay, ResourceLocation owner, Supplier<? extends Inlay> factory) {
        writing();
        registry.inlay(bay, owner, factory);
    }

    public void zoneSettings(List<ResourceLocation> delegations,
            BiFunction<Player, Supplier<UUID>, UIElement> factory) {
        writing();
        ZonePanels.register(delegations, factory);
    }

    public void outline(Outline outline) {
        writing();
        registry.outline(outline);
    }

    public void mode(OverheadTools.Mode mode) {
        writing();
        OverheadTools.registerMode(mode);
    }

    public void tool(OverheadTools.Tool tool) {
        writing();
        OverheadTools.register(tool);
    }
}
