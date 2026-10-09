package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.item.ItemStack;

// Elements that take an item dragged in from a recipe viewer, as a filter slot does: a button that names one item
// takes the dragged item as if it were picked from its search.
public final class ItemDrops {

    public interface Drop {
        boolean accepts(ItemStack stack);

        void take(ItemStack stack);
    }

    public record Target(UIElement element, Drop drop) {
    }

    private static final Map<UIElement, Drop> DROPS = new WeakHashMap<>();

    private ItemDrops() {
    }

    public static void on(UIElement element, Drop drop) {
        DROPS.put(element, drop);
    }

    // The ones shown on this screen now: displayed, as are all they sit in.
    public static List<Target> shown(ModularUI ui) {
        List<Target> shown = new ArrayList<>();
        if (ui == null) {
            return shown;
        }
        DROPS.forEach((element, drop) -> {
            if (element.getModularUI() == ui && displayed(element)) {
                shown.add(new Target(element, drop));
            }
        });
        return shown;
    }

    // Inside the element and every element it sits in, so a button scrolled out of its list takes nothing.
    public static boolean contains(UIElement element, double x, double y) {
        for (UIElement at = element; at != null; at = at.getParent()) {
            if (x < at.getPositionX() || y < at.getPositionY()
                    || x >= at.getPositionX() + at.getSizeWidth() || y >= at.getPositionY() + at.getSizeHeight()) {
                return false;
            }
        }
        return true;
    }

    private static boolean displayed(UIElement element) {
        for (UIElement at = element; at != null; at = at.getParent()) {
            if (!at.isDisplayed()) {
                return false;
            }
        }
        return element.getSizeWidth() > 0 && element.getSizeHeight() > 0;
    }
}
