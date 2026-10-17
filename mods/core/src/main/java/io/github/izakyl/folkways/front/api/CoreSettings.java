package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

public final class CoreSettings {

    public static final ResourceLocation CORE =
        ResourceLocation.fromNamespaceAndPath("folkways", "colony");

    public static final String TOOL_KEY = "tool";

    private CoreSettings() {
    }

    public static Schema.Setting.Items tool() {
        return new Schema.Setting.Items(
            TOOL_KEY, "folkways.settings.tool", vanilla("iron_pickaxe"), declaredKit());
    }

    public static Schema schema() {
        return new Schema(List.of(tool()));
    }

    private static List<ItemFilter> declaredKit() {
        Set<ItemFilter> kit = new LinkedHashSet<>();
        for (Vocation trade : Vocations.all()) {
            for (ItemSpec tool : trade.kit()) {
                collect(tool, kit);
            }
        }
        return List.copyOf(kit);
    }

    private static void collect(ItemSpec tool, Set<ItemFilter> into) {
        tool.item().ifPresent(id -> into.add(ItemFilter.item(id)));
        tool.tag().ifPresent(tag -> into.add(ItemFilter.tag(tag.location())));
        tool.anyOf().forEach(one -> collect(one, into));
    }

    private static ResourceLocation vanilla(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }
}
