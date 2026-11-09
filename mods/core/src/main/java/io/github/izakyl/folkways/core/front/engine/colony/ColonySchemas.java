package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.front.api.CoreSettings;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
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

    // Schemas come from installed plugins and their resource/configuration-dependent declarations (such as
    // crop choices and tool kits). New fields, changed types, ranges and choices therefore need defaults even
    // for data a previous writer produced correctly. Unknown keys survive an absent plugin; omitted tool
    // overrides keep inheriting the current kit. Persisted value shapes are validated before this boundary.
    static ColonySettings conform(ResourceLocation scope, ColonySettings settings) {
        return settings.conformedTo(of(scope),
            Vocations.of(scope).isPresent()
                ? Set.of(CoreSettings.TOOL_KEY) : Set.of());
    }

    static ColonySettings conformDelegation(ResourceLocation delegation, ColonySettings settings) {
        return settings.conformedTo(ofDelegation(delegation));
    }

    static Map<ResourceLocation, ColonySettings> fresh() {
        Map<ResourceLocation, ColonySettings> stores = new LinkedHashMap<>();
        all().forEach((scope, schema) -> stores.put(scope, conform(scope, ColonySettings.empty())));
        return stores;
    }
}
