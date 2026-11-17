import dev.blockwright.api.Context;
import blockwright.minecraft.core.*;
import io.github.izakyl.folkways.airline.Airports;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

// Hands the tower to the colony, names the airport and waits on a worker thread for its survey to finish.
public final class Task {
    public static Object run(Context ctx) throws Exception {
        var a = Args.of(ctx);
        var server = (MinecraftServer) ctx.server();
        var level = server.overworld();
        var pos = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
        server.submit(() -> {
            var colony = Colonies.of(server, UUID.fromString(a.string("colony"))).orElseThrow();
            colony.hold(ResourceLocation.fromNamespaceAndPath("folkways_airline", "airport_tower"),
                new Claimed.Block(WorldPos.of(level, pos)));
            Airports.register(level, pos);
            Airports.rename(level, pos, a.string("name"));
            Airports.request(level, pos);
        }).join();
        Thread.sleep(1000);
        long until = System.currentTimeMillis() + (long) a.integer("timeoutMs", 300000);
        while (server.submit(() -> Airports.scanning(level, pos)).join() && System.currentTimeMillis() < until) {
            Thread.sleep(500);
        }
        return server.submit(() -> {
            var airport = Airports.at(level, pos).orElseThrow();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("scanning", Airports.scanning(level, pos));
            out.put("runways", airport.runways().size());
            out.put("ends", airport.ends().stream().map(e -> e.number() + "@" + e.threshold().toShortString()).toList());
            out.put("helipads", airport.helipads().size());
            out.put("stands", airport.stands().stream().map(s -> s.pos().toShortString() + " " + s.facing()).toList());
            out.put("uncovered", airport.coverage().stream().filter(f -> !f.covered()).map(f -> f.area() + " " + f.reason()).toList());
            out.put("flaws", airport.flaws().stream().map(Object::toString).toList());
            return out;
        }).join();
    }
}
