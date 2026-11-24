package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Facility;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A colony's settings as a whole, one scope at a time (a module's own, or a trade's tools), each as a card the book's
 * settings page shows and presses like any facility's. A scope has no body: it is set, never run.
 */
public final class ColonyScopes {
    // The source the rows a module's panel lists on its scope's card are asked for under.
    private static final String ROWS = "settings";

    private ColonyScopes() {
    }

    public static void install() {
        Operations.register(ColonyScopes::find);
        Operations.listing(ColonyScopes::all);
    }

    /** The stable id the card of a scope's settings is reached by. */
    public static UUID id(ResourceLocation scope) {
        return UUID.nameUUIDFromBytes(("folkways_core:colony_settings/" + scope).getBytes(StandardCharsets.UTF_8));
    }

    /** Every scope that declares at least one setting, in declaration order. */
    public static List<Scope> all(Colony colony) {
        List<Scope> scopes = new ArrayList<>();
        ColonySchemas.all().forEach((scope, schema) -> {
            if (!schema.settings().isEmpty()) {
                scopes.add(new Scope(colony, scope, schema));
            }
        });
        return scopes;
    }

    static Optional<Scope> find(Colony colony, UUID id) {
        return all(colony).stream().filter(scope -> scope.id().equals(id)).findFirst();
    }

    public record Scope(Colony colony, ResourceLocation scope, Schema schema) implements Operating, Facility {

        @Override
        public UUID id() {
            return ColonyScopes.id(scope);
        }

        @Override
        public Facility facility() {
            return this;
        }

        // A trade by its name, a module by its scope name, the colony's own as the colony.
        @Override
        public Component title() {
            if (scope.equals(io.github.izakyl.folkways.core.front.api.CoreSettings.CORE)) {
                return Component.translatable("folkways.settings.colony");
            }
            if (io.github.izakyl.folkways.core.vocation.api.Vocations.of(scope).isPresent()) {
                return Component.translatable("folkways.vocation."
                    + io.github.izakyl.folkways.core.front.api.ui.Tokens.of(scope));
            }
            return Component.translatable("folkways.settings.scope." + scope.getNamespace() + "." + scope.getPath());
        }

        // The schema's settings, then the rows the module's panel lists on its own scope's card.
        @Override
        public Card card() {
            List<Card.Element> elements = new ArrayList<>(SchemaCards.elements(schema));
            panel().ifPresent(panel -> elements.add(new Card.Element.Rows(ROWS, Optional.empty())));
            return new Card(elements);
        }

        private Optional<ColonyPanel> panel() {
            return Optional.ofNullable(Enrollments.panels(colony).get(scope));
        }

        @Override
        public List<Card.Row> rows(ServerLevel level, String source, Map<String, Value> readings) {
            return source.equals(ROWS) ? panel().map(panel -> panel.rows(ColonyPanel.Page.SETTINGS, colony.view(level)))
                .orElse(List.of()) : List.of();
        }

        // A scope is set, never run, so no work is told to be anywhere.
        @Override
        public WorldPos site() {
            return WorldPos.of(net.minecraft.world.level.Level.OVERWORLD, net.minecraft.core.BlockPos.ZERO);
        }

        @Override
        public Map<String, Routine.Use> routines() {
            return Map.of();
        }

        @Override
        public List<Trigger> triggers() {
            return List.of();
        }

        @Override
        public Map<String, Reading> readings(ServerLevel level) {
            return Map.of();
        }

        // Only a scope with rows of its own reads them, wherever the colony is loaded.
        @Override
        public Optional<ServerLevel> loaded(MinecraftServer server) {
            return panel().isPresent() ? Optional.ofNullable(server.overworld()) : Optional.empty();
        }

        @Override
        public Map<String, Value> parameters() {
            return card().withFallbacks(ColonyFront.of(colony).settings(scope).parameters());
        }

        @Override
        public Map<String, Boolean> switches() {
            return Map.of();
        }

        @Override
        public List<String> running() {
            return List.of();
        }

        @Override
        public Optional<Recent> recent() {
            return Optional.empty();
        }

        @Override
        public Optional<String> disabled(String routine, Map<String, Value> enabledWhen) {
            return Optional.empty();
        }

        @Override
        public List<DefinedFacilities.Problem> run(ServerPlayer by, String routine, Map<String, Value> arguments) {
            return panel().flatMap(panel -> panel.act(by, routine, arguments))
                .orElseGet(() -> List.of(new DefinedFacilities.Problem("routine", "manual", routine)));
        }

        @Override
        public List<DefinedFacilities.Problem> setParameter(String parameter, Value value) {
            var element = card().parameter(parameter);
            if (element.isEmpty() || !element.get().accepts(value)) {
                return List.of(new DefinedFacilities.Problem("parameters." + parameter, "parameter", parameter));
            }
            var converted = ColonySettings.ofParameters(Map.of(parameter, value)).valueOf(parameter);
            if (converted.isEmpty()) {
                return List.of(new DefinedFacilities.Problem("parameters." + parameter, "parameter", parameter));
            }
            ColonyFront.of(colony).setSetting(scope, parameter, converted.get());
            return List.of();
        }

        @Override
        public List<DefinedFacilities.Problem> toggle(String trigger, boolean enabled) {
            return List.of(new DefinedFacilities.Problem("triggers." + trigger, "background", trigger));
        }
    }
}
