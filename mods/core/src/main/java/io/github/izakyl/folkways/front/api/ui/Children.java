package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import java.util.List;
import java.util.Map;

/** Reconciles visual children without detaching subtrees whose parent and order already agree. */
public final class Children {

    private Children() {
    }

    public static void reconcile(UIElement parent, List<UIElement> children) {
        reconcile(Map.of(parent, children));
    }

    public static void reconcile(Map<UIElement, List<UIElement>> children) {
        // Move survivors before removing obsolete containers, which may still contain those survivors.
        children.forEach((parent, wanted) -> {
            for (UIElement child : wanted) {
                if (child.getParent() != parent) {
                    parent.addChild(child);
                }
            }
        });
        children.forEach((parent, wanted) -> {
            for (UIElement child : parent.getSafeChildren()) {
                if (!wanted.contains(child)) {
                    parent.removeChild(child);
                }
            }
        });
        children.forEach((parent, wanted) -> {
            for (int index = 0; index < wanted.size(); index++) {
                UIElement child = wanted.get(index);
                if (parent.getChildren().get(index) != child) {
                    parent.removeChild(child);
                    parent.addChildAt(child, index);
                }
            }
        });
    }
}
