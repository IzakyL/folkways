package io.github.izakyl.folkways.core.front.api.ui;

import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

public final class RegisteringUi extends Event implements IModBusEvent {

    public interface Registry {
        void page(ResourceLocation page, Supplier<? extends Draw> factory);
        void inlay(Bay bay, ResourceLocation owner, Supplier<? extends Inlay> factory);
        void outline(Outline outline);

        void editor(ColonyPanel.Page page, Editor editor);
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

    /** An editor opened from a button on one of the book's own pages, in registration order. */
    public void editor(ColonyPanel.Page page, Editor editor) {
        writing();
        registry.editor(page, editor);
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
