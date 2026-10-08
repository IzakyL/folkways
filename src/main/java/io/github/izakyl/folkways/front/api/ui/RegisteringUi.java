package io.github.izakyl.folkways.front.api.ui;

import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
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

    public void outline(Outline outline) {
        writing();
        registry.outline(outline);
    }
}
