package io.github.izakyl.folkways.vanilla.combat;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Combatant;
import io.github.izakyl.folkways.core.combat.api.FireControl;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

final class FacilityScans {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "hostile_within");
    private static final int MAX_RANGE = 128;

    private FacilityScans() {
    }

    static void install() {
        Readings.register(ID, new Readings.ConfiguredProbe() {
            @Override
            public Optional<Value> read(ServerLevel level, WorldPos at, Map<String, Value> config) {
                return targets(level, at, range(config).orElseThrow()).map(mobs -> Value.of(mobs.size()));
            }

            @Override
            public boolean valid(Map<String, Value> config) {
                return range(config).isPresent();
            }

            @Override
            public OptionalDouble range(Map<String, Value> config) {
                return FacilityScans.range(config);
            }
        });
        FireControl.registerSource(ID, FacilityScans::contacts);
    }

    private static OptionalDouble range(Map<String, Value> config) {
        if (config.size() == 1 && config.get("range") instanceof Value.Real real
                && real.value() > 0 && real.value() <= MAX_RANGE) {
            return OptionalDouble.of(real.value());
        }
        return OptionalDouble.empty();
    }

    static Optional<List<Mob>> targets(ServerLevel level, WorldPos at, double range) {
        return WorldSpaces.world(level, at).filter(pos -> level.hasChunkAt(BlockPos.containing(pos)))
            .map(origin -> level.getEntitiesOfClass(Mob.class, AABB.ofSize(origin, 2 * range, 2 * range, 2 * range),
                mob -> mob.distanceToSqr(origin) <= range * range && Combatant.get().hostile(mob)));
    }

    static Collection<FireControl.Contact> contacts(Colony colony, MinecraftServer server) {
        List<FireControl.Contact> contacts = new ArrayList<>();
        DefinedFacilities.of(colony).ifPresent(service -> {
            long now = server.overworld().getGameTime();
            for (FacilityDefinition facility : service.facilities()) {
                if (facility.probes().values().stream().noneMatch(probe -> probe.probe().equals(ID))) {
                    continue;
                }
                ServerLevel level = facility.site().level(server).filter(facility.extent()::loaded).orElse(null);
                if (level == null) {
                    continue;
                }
                for (FacilityDefinition.Probed probe : facility.probes().values()) {
                    if (probe.probe().equals(ID)) {
                        WorldPos at = facility.anchor().at(facility.anchor().cell().offset(probe.offset()));
                        range(probe.config()).ifPresent(range -> targets(level, at, range).ifPresent(mobs ->
                            mobs.forEach(mob -> contacts.add(FireControl.contact(mob, now)))));
                    }
                }
            }
        });
        return contacts;
    }
}
