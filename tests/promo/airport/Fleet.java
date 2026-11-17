import dev.blockwright.api.Context;
import blockwright.minecraft.core.*;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

// The airport's vehicles. op=spawn parks each one with a full battery and every armed seat's ammunition in its hold;
// op=crew seats the given residents in a vehicle's driver and armed seats, each first stood beside it, as the
// official boarding call does; op=read reports where they are and what they are doing.
public final class Task {
    public static Object run(Context ctx) throws Exception {
        var a = Args.of(ctx);
        var server = (MinecraftServer) ctx.server();
        var level = server.overworld();
        var out = new LinkedHashMap<String, Object>();
        switch (a.string("op")) {
            case "spawn" -> {
                for (Object raw : a.list("vehicles")) {
                    var v = Args.of(raw, "vehicle");
                    var vehicle = (VehicleEntity) BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(v.string("type")))
                        .create(level);
                    vehicle.moveTo(v.number("x"), v.number("y"), v.number("z"), (float) v.number("yaw"), 0);
                    vehicle.setYHeadRot((float) v.number("yaw"));
                    vehicle.setEnergy(vehicle.getMaxEnergy());
                    vehicle.addTag("folkways_promo");
                    level.addFreshEntity(vehicle);
                    int slot = 0;
                    List<String> loaded = new ArrayList<>();
                    for (int seat = 0; seat < vehicle.getMaxPassengers(); seat++) {
                        for (String weapon : vehicle.getSeat(seat).weapons()) {
                            try {
                                var data = vehicle.getGunData(weapon);
                                if (data == null) continue;
                                var stack = data.selectedAmmoConsumer().stack();
                                if (stack == null || stack.isEmpty()) continue;
                                // Shells do not stack: a few slots of them, so a cannon fires for the whole film.
                                int copies = stack.getMaxStackSize() < 16 ? 6 : 1;
                                for (int n = 0; n < copies && slot < vehicle.getContainerSize(); n++) {
                                    vehicle.setItem(slot++, stack.copyWithCount(stack.getMaxStackSize()));
                                }
                                loaded.add(seat + "/" + weapon);
                            } catch (Throwable ignored) {
                                loaded.add(seat + "/" + weapon + "!");
                            }
                        }
                    }
                    out.put(v.string("key"), Map.of("uuid", vehicle.getUUID().toString(), "loaded", loaded));
                }
            }
            case "crew" -> {
                var vehicle = level.getEntity(UUID.fromString(a.string("vehicle")));
                var provider = Vehicles.of(vehicle).orElseThrow();
                var seats = provider.seats(vehicle).stream()
                    .filter(seat -> seat.index() == 0 || !seat.weapons().isEmpty())
                    .filter(seat -> seat.occupant().isEmpty()).toList();
                var people = a.strings("residents");
                // Each seat takes the next resident who accepts it: one busy elsewhere answers "unavailable" and the
                // seat goes to the one after.
                List<String> results = new ArrayList<>();
                List<String> used = new ArrayList<>();
                int next = 0;
                for (var seat : seats) {
                    while (next < people.size()) {
                        String id = people.get(next++);
                        Entity body = level.getEntity(UUID.fromString(id));
                        if (body == null) continue;
                        body.moveTo(vehicle.getX() + 1.5, vehicle.getY(), vehicle.getZ() + 1.5);
                        var result = provider.board(vehicle, Bodies.of(body).orElseThrow(), seat.index());
                        results.add(seat.index() + ":" + (result.accepted() ? "true" : String.valueOf(result)));
                        if (result.accepted()) {
                            used.add(id);
                            break;
                        }
                    }
                }
                out.put("boarded", results);
                out.put("used", used);
                out.put("needed", seats.size());
            }
            case "read" -> {
                for (String id : a.strings("vehicles")) {
                    var e = level.getEntity(UUID.fromString(id));
                    if (e == null) {
                        out.put(id, Map.of("gone", true));
                        continue;
                    }
                    var status = Vehicles.of(e).orElseThrow().status(e);
                    var vehicle = (VehicleEntity) e;
                    out.put(id, Map.of("x", e.getX(), "y", e.getY(), "z", e.getZ(), "ground", e.onGround(),
                        "action", status.action(), "wreck", vehicle.isWreck(), "aboard", e.getPassengers().size(),
                        "firing", status.firing().stream().filter(f -> f.firing()).count(),
                        "speed", e.getDeltaMovement().length(),
                        "seats", Vehicles.of(e).orElseThrow().seats(e).stream()
                            .map(seat -> seat.index() + "=" + seat.occupant().map(person -> {
                                var who = level.getEntity(person);
                                return person.toString().substring(0, 8) + (who == null ? "?" : who.getVehicle() == e ? "@aboard"
                                    : String.format("@%.0f,%.0f", who.getX(), who.getZ()));
                            }).orElse("-")).toList()));
                }
            }
            case "catapult" -> {
                // Twelve rails down the runway's centreline from `start`, the controller just behind them at full
                // launch power with its lever, and a shuttle waiting on the first rail: SW's own carrier catapult,
                // read by Firearms as an accessory of the runway end it points down.
                var start = new net.minecraft.core.BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
                var forward = net.minecraft.core.Direction.byName(a.string("facing"));
                var controller = start.relative(forward.getOpposite());
                level.setBlockAndUpdate(controller, com.atsuishio.superbwarfare.init.ModBlocks.CATAPULT_CONTROLLER.get()
                    .defaultBlockState().setValue(com.atsuishio.superbwarfare.block.CatapultControllerBlock.FACING, forward)
                    .setValue(com.atsuishio.superbwarfare.block.CatapultControllerBlock.LAUNCH_POWER, 15));
                for (int n = 0; n < 12; n++) {
                    level.setBlockAndUpdate(start.relative(forward, n), com.atsuishio.superbwarfare.init.ModBlocks
                        .AIRCRAFT_CATAPULT.get().defaultBlockState()
                        .setValue(com.atsuishio.superbwarfare.block.AircraftCatapultBlock.FACING, forward));
                }
                level.setBlockAndUpdate(controller.below(), net.minecraft.world.level.block.Blocks.LEVER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.CEILING));
                var shuttle = new com.atsuishio.superbwarfare.entity.misc.CatapultShuttleEntity(level);
                shuttle.moveTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(start));
                shuttle.setYRot(forward.toYRot());
                shuttle.addTag("folkways_promo");
                level.addFreshEntity(shuttle);
                out.put("shuttle", shuttle.getUUID().toString());
            }
            default -> throw new IllegalArgumentException(a.string("op"));
        }
        return out;
    }
}
