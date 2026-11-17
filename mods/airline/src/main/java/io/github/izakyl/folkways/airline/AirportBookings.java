package io.github.izakyl.folkways.airline;

import io.github.izakyl.folkways.core.combat.api.AirportTraffic;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

final class AirportBookings implements AirportTraffic.Provider {
    @Override
    public AirportTraffic.Claim request(ServerLevel level, AirportTraffic.Priority priority,
                                        List<AirportTraffic.Resource> resources) {
        return AirTraffic.of(level).request(priority, resources);
    }

    @Override
    public Optional<List<AirportTraffic.Resource>> assignment(ServerLevel level, LandingSites.Site site,
                                                              AirportTraffic.Aircraft aircraft) {
        var surfaces = resources(level, site);
        if (aircraft.departing() || site.kind() == LandingSites.Kind.HELIPAD) {
            return Optional.of(surfaces);
        }
        var airport = Airports.all(level).stream().filter(value ->
            WorldSpaces.at(level, value.tower()).toString().equals(site.airport().id())).findFirst();
        if (airport.isEmpty() || airport.orElseThrow().stands().isEmpty()) {
            return Optional.of(surfaces);
        }
        BlockPos landing = site.position().cell().below();
        BlockPos facility = airport.orElseThrow().runways().stream().filter(runway ->
            runway.firstThreshold().equals(landing) || runway.secondThreshold().equals(landing))
            .map(Runway::firstThreshold).findFirst().orElse(landing);
        var reachable = airport.orElseThrow().ground().reachableFrom(Set.of(facility), aircraft.width());
        for (var stand : airport.orElseThrow().stands()) {
            if (stand.maxLength() < aircraft.length() || stand.maxWingspan() < aircraft.width()
                    || !reachable.contains(stand.pos())) {
                continue;
            }
            var footprint = stand.footprint();
            var first = WorldSpaces.at(level, new BlockPos(footprint.minX(), footprint.y(), footprint.minZ()));
            var last = WorldSpaces.at(level, new BlockPos(footprint.maxX(), footprint.y(), footprint.maxZ()));
            var parking = new AirportTraffic.Resource(AirportTraffic.Kind.STAND, first, last);
            var bounds = new AABB(WorldSpaces.world(level, first).orElseThrow(),
                WorldSpaces.world(level, last).orElseThrow()).inflate(1, 4, 1);
            if (!level.getEntities((Entity) null, bounds, entity -> !entity.getUUID().equals(aircraft.id())
                    && entity.getBbWidth() >= 1).isEmpty()) {
                continue;
            }
            var combined = new ArrayList<>(surfaces);
            combined.add(parking);
            if (AirTraffic.of(level).free(combined)) {
                return Optional.of(List.copyOf(combined));
            }
        }
        return Optional.of(surfaces);
    }

    @Override
    public List<AirportTraffic.Resource> resources(ServerLevel level, LandingSites.Site site) {
        double angle = Math.toRadians(site.yaw() + 180);
        double dx = Math.sin(angle);
        double dz = -Math.cos(angle);
        BlockPos center = site.position().cell().below();
        double half = site.width() / 2;
        double endX = center.getX() + dx * (site.length() - 1);
        double endZ = center.getZ() + dz * (site.length() - 1);
        BlockPos first = BlockPos.containing(Math.min(center.getX(), endX) - Math.abs(dz) * half,
            center.getY(), Math.min(center.getZ(), endZ) - Math.abs(dx) * half);
        BlockPos last = BlockPos.containing(Math.max(center.getX(), endX) + Math.abs(dz) * half,
            center.getY(), Math.max(center.getZ(), endZ) + Math.abs(dx) * half);
        if (site.kind() == LandingSites.Kind.HELIPAD) {
            int radius = (int) Math.floor(site.width() / 2);
            first = center.offset(-radius, 0, -radius);
            last = center.offset(radius, 0, radius);
        }
        return List.of(new AirportTraffic.Resource(site.kind() == LandingSites.Kind.HELIPAD
            ? AirportTraffic.Kind.HELIPAD : AirportTraffic.Kind.RUNWAY,
            new WorldPos(site.position().realm(), first),
            new WorldPos(site.position().realm(), last)));
    }
}
