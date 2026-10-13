import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../../shared/colony/colony-tasks";

export async function sampleRailReach(server: MinecraftServer) {
  return runColonyTask(server, RAIL_REACH, {});
}

// Read-only: for the published way graph, where each train hop's platforms sit among the walking pieces, and for
// far asks still waiting, which side of the ride the asker and the goal stand on and what a quiet ask answers.
const RAIL_REACH = defineTask<Record<string, never>, Record<string, any>>({
  name: "folkways.bench.rail-reach",
  side: "server",
  timeoutMs: 30_000,
  source: `
import dev.blockwright.api.Context;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.participation.*;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.travel.Urgency;
public final class Task {
  static Object field(Object owner, String name) throws Exception {
    Class<?> c = owner.getClass();
    while (true) { try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
      catch (NoSuchFieldException e) { c = c.getSuperclass(); if (c == null) throw e; } }
  }
  static Object call(Object owner, String name, Object... args) throws Exception {
    for (Method m : owner.getClass().getDeclaredMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == args.length) { m.setAccessible(true); return m.invoke(owner, args); }
    }
    throw new NoSuchMethodException(name);
  }
  static String at(long cell) { return BlockPos.of(cell).toShortString(); }
  public static Object run(Context ctx) throws Exception {
    Field graphs = ColonyWays.class.getDeclaredField("GRAPHS"); graphs.setAccessible(true);
    List<Object> out = new ArrayList<>();
    ResourceLocation resident = ResourceLocation.fromNamespaceAndPath("folkways", "resident");
    for (Object roaming : ((Map<?,?>)graphs.get(null)).values()) {
      long now = ((ServerLevel)field(roaming,"level")).getGameTime();
      Object graph = field(roaming,"published");
      Map<?,?> sorts = (Map<?,?>)field(graph,"sorts");
      Object sort = sorts.get(resident);
      if (sort == null) { out.add(Map.of("error","no resident sort","kinds",String.valueOf(sorts.keySet()))); continue; }
      Object web = call(graph,"weave",sort);
      int[] webPart = (int[])field(web,"part");
      int size = (int)call(web,"size");
      Map<Integer,Integer> webSizes = new HashMap<>();
      for (int v = 0; v < size; v++) webSizes.merge(webPart[v],1,Integer::sum);
      // Walking-only pieces: each tract's own part, offset into the web's numbering.
      Object[] nets = (Object[])field(web,"nets");
      int[] offset = (int[])field(web,"offset");
      int[] walkPart = new int[size];
      for (int t = 0; t < nets.length; t++) {
        int[] p = (int[])call(nets[t],"part");
        for (int l = 0; l < p.length; l++) walkPart[offset[t]+l] = offset[t]+p[l];
      }
      Map<Integer,Integer> walkSizes = new HashMap<>();
      for (int v = 0; v < size; v++) walkSizes.merge(walkPart[v],1,Integer::sum);
      int[] hopHead = (int[])field(web,"hopHead");
      int laidHops = hopHead[size];
      List<Object> hops = new ArrayList<>();
      for (Object crossing : (List<?>)field(graph,"crossings")) {
        WorldPos boarding = (WorldPos)call(crossing,"boarding");
        WorldPos landing = (WorldPos)call(crossing,"landing");
        Hop hop = (Hop)call(crossing,"hop");
        ResourceLocation passage = (ResourceLocation)call(crossing,"passage");
        int b = (int)call(web,"at",boarding), l = (int)call(web,"at",landing);
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("fare",String.valueOf(hop.fare()));
        row.put("boarding_hub",boarding.cell().toShortString());
        row.put("landing_hub",landing.cell().toShortString());
        row.put("boarding_vertex",b); row.put("landing_vertex",l);
        row.put("boarding_snapped",b < 0 ? "" : ((WorldPos)call(web,"cellAt",b)).cell().toShortString());
        row.put("landing_snapped",l < 0 ? "" : ((WorldPos)call(web,"cellAt",l)).cell().toShortString());
        row.put("boarding_walk_piece",b < 0 ? -1 : walkPart[b]); row.put("boarding_walk_size",b < 0 ? 0 : walkSizes.get(walkPart[b]));
        row.put("landing_walk_piece",l < 0 ? -1 : walkPart[l]); row.put("landing_walk_size",l < 0 ? 0 : walkSizes.get(walkPart[l]));
        row.put("participation",String.valueOf(Participations.between(resident, Stake.passage(passage))));
        OptionalLong arrives = hop.fare().arriveBy(now);
        row.put("arrives_in",arrives.isPresent() ? arrives.getAsLong() - now : -1);
        row.put("footings",hop.boarding().cells().size() + "/" + hop.landing().cells().size());
        hops.add(row);
      }
      // Far asks still waiting: which walking piece holds each end, whether the web joins them, what a quiet ask says.
      Class<?> askerType = null;
      for (Class<?> c : graph.getClass().getDeclaredClasses()) if (c.getSimpleName().equals("Asker")) askerType = c;
      Constructor<?> asker = askerType.getDeclaredConstructors()[0]; asker.setAccessible(true);
      Object quiet = asker.newInstance(resident, Urgency.CHECKING, false);
      Method direct = null;
      for (Method m : graph.getClass().getDeclaredMethods()) if (m.getName().equals("direct")) direct = m;
      direct.setAccessible(true);
      List<Object> asks = new ArrayList<>();
      Map<String,Integer> verdicts = new TreeMap<>();
      int far = 0;
      for (String queue : List.of("pending","connecting")) {
        for (Object ask : (Collection<?>)field(roaming,queue)) {
          if (!resident.equals(call(ask,"kind"))) continue;
          long from = (long)call(ask,"from");
          List<Long> goals = (List<Long>)call(ask,"goals");
          if (goals.isEmpty() || BlockPos.of(from).distManhattan(BlockPos.of(goals.get(0))) < 300) continue;
          far++;
          Object realm = call(ask,"realm");
          WorldPos fromPos = new WorldPos((io.github.izakyl.folkways.core.api.terms.Realm)realm, BlockPos.of(from));
          Set<WorldPos> goalPos = new LinkedHashSet<>();
          for (long g : goals) goalPos.add(new WorldPos((io.github.izakyl.folkways.core.api.terms.Realm)realm, BlockPos.of(g)));
          int s = (int)call(web,"at",fromPos);
          int e = -1; for (WorldPos g : goalPos) { e = (int)call(web,"at",g); if (e >= 0) break; }
          Object said = direct.invoke(graph, quiet, sort, fromPos, goalPos, Map.of());
          String verdict = said.getClass().getSimpleName()
            + "/start" + (s < 0 ? "Off" : "On") + "/goal" + (e < 0 ? "Off" : "On")
            + "/web" + (s >= 0 && e >= 0 && webPart[s] == webPart[e] ? "Joined" : "Apart");
          verdicts.merge(queue + ":" + verdict,1,Integer::sum);
          if (asks.size() < 24) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("queue",queue); row.put("from",at(from)); row.put("goal",at(goals.get(0))); row.put("goals",goals.size());
            row.put("verdict",verdict); row.put("said",String.valueOf(said));
            row.put("from_walk_piece",s < 0 ? -1 : walkPart[s]); row.put("from_walk_size",s < 0 ? 0 : walkSizes.get(walkPart[s]));
            row.put("goal_walk_piece",e < 0 ? -1 : walkPart[e]); row.put("goal_walk_size",e < 0 ? 0 : walkSizes.get(walkPart[e]));
            row.put("goal_snapped",e < 0 ? "" : ((WorldPos)call(web,"cellAt",e)).cell().toShortString());
            asks.add(row);
          }
        }
      }
      List<Integer> biggest = new ArrayList<>(walkSizes.values()); biggest.sort(Comparator.reverseOrder());
      Map<String,Object> res = new LinkedHashMap<>();
      res.put("tick",now); res.put("vertices",size); res.put("laid_hops",laidHops);
      res.put("walk_pieces",walkSizes.size()); res.put("walk_biggest",biggest.subList(0,Math.min(10,biggest.size())));
      res.put("web_pieces",webSizes.size());
      res.put("hops",hops); res.put("far_asks",far); res.put("verdicts",verdicts); res.put("examples",asks);
      out.add(res);
    }
    return Map.of("ok",true,"graphs",out);
  }
}`,
});

/** Create's track graphs as they stand: each graph's stations and size, each train's graph, and what each planned
 * station's cell, the block under it and its track hold. */
export async function sampleRailGraphs(server: MinecraftServer, stations: Array<{ name: string; position: { x: number; y: number; z: number } }>,
  gaps: number[][] = []) {
  return runColonyTask(server, RAIL_GRAPHS, { stations, gaps });
}

const RAIL_GRAPHS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.bench.rail-graphs",
  side: "server",
  timeoutMs: 30_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "com.simibubi.create.Create",
      "com.simibubi.create.content.trains.graph.EdgePointType", "com.simibubi.create.content.trains.station.GlobalStation"],
    body: `
var level = ctx.server().overworld();
var rail = Create.RAILWAYS.sided(level);
var args = Args.of(ctx);
List<Object> graphs = new ArrayList<>();
for (var g : rail.trackNetworks.values()) {
  List<String> names = new ArrayList<>();
  for (GlobalStation s : g.getPoints(EdgePointType.STATION))
    names.add(s.name + "@" + (s.blockEntityPos == null ? "?" : s.blockEntityPos.toShortString()));
  List<String> ends = new ArrayList<>();
  for (var loc : g.getNodes()) {
    var node = g.locateNode(loc);
    if (node != null && g.getConnectionsFrom(node).size() <= 1) ends.add(loc.getLocation().toString());
  }
  graphs.add(Map.of("id", g.id.toString(), "nodes", g.getNodes().size(), "stations", names, "ends", ends));
}
List<Object> trains = new ArrayList<>();
for (var t : rail.trains.values()) trains.add(Map.of("id", t.id.toString(), "graph", t.graph == null ? "none" : t.graph.id.toString(),
  "station", t.getCurrentStation() == null ? "-" : t.getCurrentStation().name));
List<Object> cells = new ArrayList<>();
for (var raw : args.list("stations")) {
  var st = (Map<?,?>) raw; var p = (Map<?,?>) st.get("position");
  var at = new BlockPos(((Number)p.get("x")).intValue(), ((Number)p.get("y")).intValue(), ((Number)p.get("z")).intValue());
  Map<String,Object> row = new LinkedHashMap<>();
  row.put("name", st.get("name"));
  for (int dy = -1; dy <= 1; dy++) row.put("y" + dy, level.getBlockState(at.offset(0, dy, 0)).toString());
  cells.add(row);
}
List<Object> gaps = new ArrayList<>();
for (var raw : args.list("gaps")) {
  var g = (List<?>) raw;
  int x0 = ((Number)g.get(0)).intValue(), z0 = ((Number)g.get(1)).intValue(), x1 = ((Number)g.get(2)).intValue(), z1 = ((Number)g.get(3)).intValue();
  int n = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
  List<String> along = new ArrayList<>();
  for (int k = 0; k <= n; k++) {
    int x = x0 + Math.round((x1 - x0) * k / (float) Math.max(1, n)), z = z0 + Math.round((z1 - z0) * k / (float) Math.max(1, n));
    StringBuilder col = new StringBuilder(x + "," + z + ":");
    for (int y = 71; y <= 79; y++) {
      var st = level.getBlockState(new BlockPos(x, y, z));
      String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
      col.append(" ").append(id.equals("track") ? "TRACK" + st.getValue(com.simibubi.create.content.trains.track.TrackBlock.SHAPE) : id);
      var be = level.getBlockEntity(new BlockPos(x, y, z));
      if (be instanceof com.simibubi.create.content.trains.track.TrackBlockEntity tbe && !tbe.getConnections().isEmpty()) col.append("(curves=").append(tbe.getConnections().size()).append(")");
    }
    along.add(col.toString());
  }
  gaps.add(along);
}
return Map.of("graphs", graphs, "trains", trains, "cells", cells, "gaps", gaps);
`,
  }),
});
