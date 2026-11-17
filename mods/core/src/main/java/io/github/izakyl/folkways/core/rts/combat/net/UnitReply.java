package io.github.izakyl.folkways.core.rts.combat.net;

import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.VehicleProfile;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record UnitReply(long request, UUID colony, Vehicles.Result result, Optional<UnitSnapshot> snapshot)
    implements CustomPacketPayload {
    public static final Type<UnitReply> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("folkways_core", "unit_reply"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UnitReply> STREAM_CODEC =
        StreamCodec.ofMember(UnitReply::encode, UnitReply::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf b) {
        b.writeVarLong(request);
        b.writeUUID(colony);
        b.writeBoolean(result.accepted());
        b.writeUtf(result.key(), 256);
        b.writeBoolean(snapshot.isPresent());
        if (snapshot.isEmpty()) {
            return;
        }
        UnitSnapshot data = snapshot.orElseThrow();
        b.writeVarInt(data.units().size());
        for (var unit : data.units()) {
            b.writeUUID(unit.id());
            b.writeEnum(unit.kind());
            ComponentSerialization.STREAM_CODEC.encode(b, unit.name());
            unit.position().write(b);
            optionalId(b, unit.owner());
            CombatSelectionPacket.writeIds(b, unit.members());
            b.writeVarInt(unit.seats().size());
            for (var seat : unit.seats()) {
                b.writeVarInt(seat.index());
                b.writeEnum(seat.role());
                b.writeVarInt(seat.weapons().size());
                seat.weapons().forEach(weapon -> b.writeUtf(weapon, 256));
                b.writeVarInt(seat.selected());
                optionalId(b, seat.occupant());
            }
            var caps = unit.capabilities();
            b.writeBoolean(caps.mobile());
            b.writeEnum(caps.airframe());
            b.writeBoolean(caps.armed());
            b.writeVarInt(caps.actions().size());
            caps.actions().forEach(b::writeEnum);
            var status = unit.status();
            b.writeDouble(status.health());
            b.writeDouble(status.maxHealth());
            b.writeVarLong(status.energy());
            b.writeVarLong(status.maxEnergy());
            b.writeVarInt(status.ammunition().size());
            status.ammunition().forEach((weapon, count) -> {
                b.writeUtf(weapon, 256);
                b.writeVarInt(count);
            });
            b.writeBoolean(status.runway().isPresent());
            status.runway().ifPresent(needs -> {
                b.writeDouble(needs.takeoff());
                b.writeDouble(needs.landing());
                b.writeDouble(needs.width());
                b.writeDouble(needs.liftSpeed());
                b.writeDouble(needs.acceleration());
                b.writeDouble(needs.margin());
                b.writeDouble(needs.towSlack());
            });
            b.writeUtf(status.action(), 256);
            b.writeVarInt(status.route().points().size());
            status.route().points().forEach(point -> point.write(b));
            b.writeVarInt(status.route().current());
            b.writeVarInt(status.route().cycleStart());
            target(b, status.target());
            b.writeVarInt(status.firing().size());
            status.firing().forEach(seat -> {
                b.writeVarInt(seat.seat());
                target(b, seat.target());
                b.writeBoolean(seat.firing());
            });
            b.writeVarInt(unit.weapons().size());
            unit.weapons().forEach((weapon, name) -> {
                b.writeUtf(weapon, 256);
                ComponentSerialization.STREAM_CODEC.encode(b, name);
            });
            b.writeBoolean(unit.vehicle().isPresent());
            unit.vehicle().ifPresent(vehicle -> {
                b.writeUUID(vehicle.profile());
                b.writeResourceLocation(vehicle.type());
                b.writeVarInt(vehicle.stock().size());
                vehicle.stock().forEach(b::writeVarInt);
                b.writeVarInt(vehicle.missingSeats().size());
                vehicle.missingSeats().forEach(b::writeVarInt);
            });
            b.writeVarInt(unit.weaponRules().size());
            unit.weaponRules().forEach((weapon, rule) -> {
                b.writeUtf(weapon, 256);
                b.writeEnum(rule.mode());
                b.writeDouble(rule.range());
                b.writeBoolean(rule.indirect());
            });
            b.writeBoolean(unit.releaseWhenIdle());
            b.writeEnum(unit.altitude());
        }
        b.writeVarInt(data.vehicleProfiles().size());
        data.vehicleProfiles().forEach(profile -> b.writeNbt(profile.save(b.registryAccess())));
        b.writeVarInt(data.residents().size());
        for (var resident : data.residents()) {
            b.writeUUID(resident.id());
            ComponentSerialization.STREAM_CODEC.encode(b, resident.name());
            b.writeBoolean(resident.commanded());
            optionalId(b, resident.vehicle());
        }
        b.writeVarInt(data.landingSites().size());
        for (var site : data.landingSites()) {
            b.writeUtf(site.id(), 256);
            b.writeUtf(site.name(), 256);
            b.writeUtf(site.airport().id(), 256);
            b.writeUtf(site.airport().name(), 256);
            optionalId(b, site.airport().colony());
            b.writeEnum(site.kind());
            site.position().write(b);
            b.writeFloat(site.yaw());
            b.writeDouble(site.length());
            b.writeDouble(site.width());
            b.writeVarInt(site.catapults().size());
            for (var catapult : site.catapults()) {
                catapult.start().write(b);
                catapult.controller().write(b);
                b.writeFloat(catapult.yaw());
                b.writeVarInt(catapult.length());
                b.writeVarInt(catapult.power());
                b.writeDouble(catapult.shuttleExitSpeed());
            }
        }
    }

    private static UnitReply decode(RegistryFriendlyByteBuf b) {
        long request = b.readVarLong();
        UUID colony = b.readUUID();
        Vehicles.Result result = new Vehicles.Result(b.readBoolean(), b.readUtf(256));
        if (!b.readBoolean()) {
            return new UnitReply(request, colony, result, Optional.empty());
        }
        List<UnitSnapshot.Unit> units = new ArrayList<>();
        for (int n = count(b, 512); n > 0; n--) {
            UUID id = b.readUUID();
            var kind = b.readEnum(UnitSnapshot.Kind.class);
            Component name = ComponentSerialization.STREAM_CODEC.decode(b);
            WorldPos position = WorldPos.read(b);
            Optional<UUID> owner = optionalId(b);
            List<UUID> members = CombatSelectionPacket.readIds(b, CombatPackets.MAX_MEMBERS);
            List<Vehicles.Seat> seats = new ArrayList<>();
            for (int s = count(b, 256); s > 0; s--) {
                int index = b.readVarInt();
                var role = b.readEnum(Vehicles.Role.class);
                List<String> weapons = new ArrayList<>();
                for (int w = count(b, 256); w > 0; w--) {
                    weapons.add(b.readUtf(256));
                }
                seats.add(new Vehicles.Seat(index, role, weapons, b.readVarInt(), optionalId(b)));
            }
            boolean mobile = b.readBoolean();
            var airframe = b.readEnum(Vehicles.Airframe.class);
            boolean armed = b.readBoolean();
            List<Vehicles.Action> actions = new ArrayList<>();
            for (int a = count(b, Vehicles.Action.values().length); a > 0; a--) {
                actions.add(b.readEnum(Vehicles.Action.class));
            }
            var caps = new Vehicles.Capabilities(mobile, airframe, armed, actions);
            double health = b.readDouble();
            double maxHealth = b.readDouble();
            long energy = b.readVarLong();
            long maxEnergy = b.readVarLong();
            Map<String, Integer> ammo = new LinkedHashMap<>();
            for (int a = count(b, 256); a > 0; a--) {
                ammo.put(b.readUtf(256), b.readVarInt());
            }
            Optional<Vehicles.RunwayNeeds> runway = b.readBoolean()
                ? Optional.of(new Vehicles.RunwayNeeds(b.readDouble(), b.readDouble(), b.readDouble(),
                    b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble())) : Optional.empty();
            String action = b.readUtf(256);
            List<WorldPos> points = new ArrayList<>();
            for (int p = count(b, CombatPackets.MAX_POINTS); p > 0; p--) {
                points.add(WorldPos.read(b));
            }
            var route = new Vehicles.Route(points, b.readVarInt(), b.readVarInt());
            var target = target(b);
            List<Vehicles.Firing> firing = new ArrayList<>();
            for (int f = count(b, 128); f > 0; f--) {
                firing.add(new Vehicles.Firing(b.readVarInt(), target(b), b.readBoolean()));
            }
            var status = new Vehicles.Status(health, maxHealth, energy, maxEnergy, ammo, action, route, target, firing, runway);
            Map<String, Component> weapons = new LinkedHashMap<>();
            for (int w = count(b, 256); w > 0; w--) {
                weapons.put(b.readUtf(256), ComponentSerialization.STREAM_CODEC.decode(b));
            }
            Optional<Vehicles.Readiness> vehicle = Optional.empty();
            if (b.readBoolean()) {
                UUID profile = b.readUUID();
                ResourceLocation type = b.readResourceLocation();
                List<Integer> stock = new ArrayList<>();
                for (int s = count(b, 192); s > 0; s--) {
                    stock.add(b.readVarInt());
                }
                List<Integer> missing = new ArrayList<>();
                for (int s = count(b, 128); s > 0; s--) {
                    missing.add(b.readVarInt());
                }
                vehicle = Optional.of(new Vehicles.Readiness(profile, type, stock, missing));
            }
            Map<String, WeaponRules.Rule> rules = new LinkedHashMap<>();
            for (int r = count(b, 256); r > 0; r--) {
                rules.put(b.readUtf(256), new WeaponRules.Rule(b.readEnum(WeaponRules.Mode.class),
                    b.readDouble(), b.readBoolean()));
            }
            units.add(new UnitSnapshot.Unit(id, kind, name, position, owner, members,
                seats, caps, status, weapons, rules, vehicle, b.readBoolean(),
                b.readEnum(Vehicles.Altitude.class)));
        }
        List<VehicleProfile> profiles = new ArrayList<>();
        for (int p = count(b, 256); p > 0; p--) {
            profiles.add(VehicleProfile.load(b.readNbt(), b.registryAccess()));
        }
        List<UnitSnapshot.Resident> residents = new ArrayList<>();
        for (int n = count(b, CombatPackets.MAX_MEMBERS); n > 0; n--) {
            residents.add(new UnitSnapshot.Resident(b.readUUID(), ComponentSerialization.STREAM_CODEC.decode(b),
                b.readBoolean(), optionalId(b)));
        }
        List<LandingSites.Site> sites = new ArrayList<>();
        for (int n = count(b, 512); n > 0; n--) {
            String id = b.readUtf(256);
            String name = b.readUtf(256);
            var airport = new LandingSites.Airport(b.readUtf(256), b.readUtf(256), optionalId(b));
            var kind = b.readEnum(LandingSites.Kind.class);
            WorldPos position = WorldPos.read(b);
            float yaw = b.readFloat();
            double length = b.readDouble();
            double width = b.readDouble();
            List<LandingSites.Catapult> catapults = new ArrayList<>();
            for (int c = count(b, 64); c > 0; c--) {
                catapults.add(new LandingSites.Catapult(WorldPos.read(b), WorldPos.read(b), b.readFloat(),
                    b.readVarInt(), b.readVarInt(), b.readDouble()));
            }
            sites.add(new LandingSites.Site(id, name, kind, position, yaw, length, width, catapults, airport));
        }
        return new UnitReply(request, colony, result, Optional.of(new UnitSnapshot(colony, units, residents, sites, profiles)));
    }

    private static void target(RegistryFriendlyByteBuf b, Optional<Vehicles.Target> target) {
        b.writeBoolean(target.isPresent());
        target.ifPresent(value -> {
            b.writeUUID(value.id());
            b.writeVarInt(value.entityId());
            ComponentSerialization.STREAM_CODEC.encode(b, value.name());
        });
    }

    private static Optional<Vehicles.Target> target(RegistryFriendlyByteBuf b) {
        return b.readBoolean() ? Optional.of(new Vehicles.Target(b.readUUID(), b.readVarInt(),
            ComponentSerialization.STREAM_CODEC.decode(b))) : Optional.empty();
    }

    private static int count(RegistryFriendlyByteBuf b, int max) {
        int count = b.readVarInt();
        if (count < 0 || count > max || count > b.readableBytes()) {
            throw new IllegalArgumentException("invalid unit snapshot count");
        }
        return count;
    }

    private static void optionalId(RegistryFriendlyByteBuf b, Optional<UUID> id) {
        b.writeBoolean(id.isPresent());
        id.ifPresent(b::writeUUID);
    }

    private static Optional<UUID> optionalId(RegistryFriendlyByteBuf b) {
        return b.readBoolean() ? Optional.of(b.readUUID()) : Optional.empty();
    }
}
