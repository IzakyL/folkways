import dev.blockwright.api.Context;
import blockwright.minecraft.core.*;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

// Probe: spawn one vehicle with a resident at the driver's seat, then optionally order it.
public final class Task {
    public static Object run(Context ctx) throws Exception {
        var a = Args.of(ctx);
        var level = ((MinecraftServer) ctx.server()).overworld();
        String op = a.string("op");
        var out = new LinkedHashMap<String, Object>();
        if (op.equals("sites")) {
            for (var s : LandingSites.in(level)) out.put(s.id(), s.kind() + " " + s.name() + " " + s.position() + " yaw=" + s.yaw() + " " + s.length() + "x" + s.width() + " cat=" + s.catapults().size());
            return out;
        }
        if (op.equals("spawn")) {
            var colony = Colonies.of(level.getServer(), UUID.fromString(a.string("colony"))).orElseThrow();
            var vehicle = (VehicleEntity) BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(a.string("type"))).create(level);
            vehicle.moveTo(a.number("x"), a.number("y"), a.number("z"), (float) a.number("yaw"), 0);
            vehicle.setEnergy(vehicle.getMaxEnergy());
            level.addFreshEntity(vehicle);
            var body = PersonBody.RESIDENT.get().create(level);
            body.moveTo(vehicle.position());
            level.addFreshEntity(body);
            colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            out.put("vehicle", vehicle.getUUID().toString());
            out.put("board", Vehicles.of(vehicle).orElseThrow().board(vehicle, io.github.izakyl.folkways.core.resident.api.Bodies.of(body).orElseThrow(), 0).toString());
            return out;
        }
        var vehicle = (VehicleEntity) level.getEntity(UUID.fromString(a.string("vehicle")));
        if (op.equals("order")) {
            List<io.github.izakyl.folkways.core.world.api.WorldPos> points = new ArrayList<>();
            if (a.has("px")) points.add(WorldSpaces.at(level, BlockPos.containing(a.number("px"), a.number("py"), a.number("pz"))));
            var result = Vehicles.of(vehicle).orElseThrow().order(vehicle, new Vehicles.Order(Vehicles.Action.valueOf(a.string("action")),
                points, -1, Optional.empty(), a.has("site") ? Optional.of(a.string("site")) : Optional.empty()));
            out.put("result", result.toString());
        }
        out.put("pos", vehicle.position().toString());
        out.put("ground", vehicle.onGround());
        out.put("status", Vehicles.of(vehicle).orElseThrow().status(vehicle).toString());
        out.put("energy", vehicle.getEnergy());
        out.put("wreck", vehicle.isWreck());
        return out;
    }
}
