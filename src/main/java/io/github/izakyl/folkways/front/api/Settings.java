package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public interface Settings {

    boolean flag(String key);

    int count(String key);

    ResourceLocation choice(String key);

    Optional<ItemSpec> items(String key);
}
