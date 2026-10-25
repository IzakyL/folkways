package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.front.api.CoreSettings;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.engine.Enrollments;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
        for (var vocation : Vocations.all()) {
            var declared = new ArrayList<>(scopes.getOrDefault(vocation.id(), Schema.none()).settings());
            declared.add(CoreSettings.tool(vocation));
            scopes.put(vocation.id(), new Schema(declared));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(scopes));
    }

    public static Schema of(ResourceLocation scope) {
        return all().getOrDefault(scope, Schema.none());
    }

    public static Schema ofDelegation(ResourceLocation delegation) {
        return Enrollments.delegation(delegation).map(Delegation::schema).orElseGet(Schema::none);
    }

    static ColonySettings conform(ResourceLocation scope, ColonySettings settings) {
        return settings.conformedTo(of(scope),
            Vocations.of(scope).isPresent()
                ? Set.of(CoreSettings.TOOL_KEY) : Set.of());
    }

    static Map<ResourceLocation, ColonySettings> fresh() {
        Map<ResourceLocation, ColonySettings> stores = new LinkedHashMap<>();
        all().forEach((scope, schema) -> stores.put(scope, conform(scope, ColonySettings.empty())));
        return stores;
    }
}
