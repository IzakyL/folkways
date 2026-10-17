package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.front.api.CoreSettings;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.engine.Enrollments;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

public final class ColonySchemas {

    private ColonySchemas() {
    }

    public static Map<ResourceLocation, Schema> all() {
        Map<ResourceLocation, Schema> scopes = new LinkedHashMap<>();
        scopes.put(CoreSettings.CORE, CoreSettings.schema());
        for (Map.Entry<ResourceLocation, Enrollment> enrolled : Enrollments.all().entrySet()) {
            scopes.put(enrolled.getKey(), enrolled.getValue().settings());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(scopes));
    }

    public static Schema of(ResourceLocation scope) {
        return all().getOrDefault(scope, Schema.none());
    }

    public static Schema ofDelegation(ResourceLocation delegation) {
        return Enrollments.delegation(delegation).map(Delegation::schema).orElseGet(Schema::none);
    }

    static Map<ResourceLocation, ColonySettings> fresh() {
        Map<ResourceLocation, ColonySettings> stores = new LinkedHashMap<>();
        all().forEach((scope, schema) -> stores.put(scope, ColonySettings.byDefault(schema)));
        return stores;
    }
}
