package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.core.api.resident.body.Body;

public interface Inlay {

    UIElement build(Nook nook);

    // True when this inlay names the subject itself, so the window's own rename line stands aside.
    default boolean names(Body subject) {
        return false;
    }
}
