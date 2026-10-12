import { defineTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../e2e/colony-tasks";

export async function sampleLoosePieces(server: MinecraftServer) {
  return runColonyTask(server, LOOSE_PIECES, {});
}

// Read-only: every piece of each walking net but its biggest - where it stands, whether it holds pinned points or a
// platform's footings, how far the rest of the net is, and what blocks stand round it.
const LOOSE_PIECES = defineTask<Record<string, never>, Record<string, any>>({
  name: "folkways.bench.loose-pieces",
  side: "server",
  timeoutMs: 60_000,
  source: `
import dev.blockwright.api.Context;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
public final class Task {
  static Object field(Object owner, String name) throws Exception {
    Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
  }
  static Object call(Object owner, String name) throws Exception {
    Method m = owner.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(owner);
  }
  static String name(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(); }
  public static Object run(Context ctx) throws Exception {
    Field graphs = ColonyWays.class.getDeclaredField("GRAPHS"); graphs.setAccessible(true);
    List<Object> result = new ArrayList<>();
    for (Object roaming : ((Map<?,?>)graphs.get(null)).values()) {
      ServerLevel level = (ServerLevel)field(roaming,"level");
      Object graph = field(roaming,"published");
      Map<?,?> live = (Map<?,?>)field(roaming,"nets");
      Set<Long> footings = new HashSet<>();
      for (Object crossing : (List<?>)field(graph,"crossings")) {
        Hop hop = (Hop)call(crossing,"hop");
        for (WorldPos cell : hop.boarding().cells()) footings.add(cell.cell().asLong());
        for (WorldPos cell : hop.landing().cells()) footings.add(cell.cell().asLong());
      }
      List<Object> nets = new ArrayList<>();
      for (var entry : ((Map<?,?>)field(graph,"nets")).entrySet()) {
        Object key = entry.getKey(), net = entry.getValue();
        String realm = call(key,"realm").toString();
        boolean open = realm.startsWith("Dimension");
        Map<Long,Integer> byCell = (Map<Long,Integer>)call(net,"byCell");
        Map<Long,Integer> attached = (Map<Long,Integer>)call(net,"attached");
        int[] part = (int[])call(net,"part"), head = (int[])call(net,"head");
        Object points = live.get(key);
        BitSet pinned = points == null ? new BitSet() : (BitSet)field(points,"pinned");
        Map<Long,Integer> liveByCell = points == null ? Map.of() : (Map<Long,Integer>)field(points,"byCell");
        Map<Integer,List<Long>> pieces = new HashMap<>();
        for (var at : byCell.entrySet()) pieces.computeIfAbsent(part[at.getValue()],k -> new ArrayList<>()).add(at.getKey());
        Map<Integer,Integer> held = new HashMap<>();
        for (int id : attached.values()) held.merge(part[id],1,Integer::sum);
        int biggest = -1;
        for (var p : pieces.entrySet()) if (biggest < 0 || p.getValue().size() > pieces.get(biggest).size()) biggest = p.getKey();
        List<Object> loose = new ArrayList<>();
        for (var p : pieces.entrySet()) {
          if (p.getKey() == biggest) continue;
          List<Long> cells = p.getValue();
          cells.sort(Comparator.comparingInt(c -> byCell.get(c)));
          int pins = 0, feet = 0, links = 0;
          for (long cell : cells) {
            Integer id = liveByCell.get(cell);
            if (id != null && pinned.get(id)) pins++;
            if (footings.contains(cell)) feet++;
            int frozen = byCell.get(cell);
            links += head[frozen+1] - head[frozen];
          }
          long first = cells.get(0);
          BlockPos at = BlockPos.of(first);
          // The nearest point outside the piece, from its first point.
          double nearest = Double.MAX_VALUE; long other = 0L; boolean otherBig = false;
          for (var e : byCell.entrySet()) {
            if (part[e.getValue()] == p.getKey()) continue;
            double apart = at.distSqr(BlockPos.of(e.getKey()));
            if (apart < nearest) { nearest = apart; other = e.getKey(); otherBig = part[e.getValue()] == biggest; }
          }
          Map<String,Object> row = new LinkedHashMap<>();
          row.put("size",cells.size()); row.put("pinned",pins); row.put("footings",feet); row.put("links",links);
          row.put("attached",held.getOrDefault(p.getKey(),0));
          row.put("first",at.toShortString());
          row.put("cells",cells.stream().limit(6).map(c -> BlockPos.of(c).toShortString()).toList());
          row.put("nearest",nearest == Double.MAX_VALUE ? -1 : Math.round(Math.sqrt(nearest)*10)/10.0);
          row.put("nearest_at",nearest == Double.MAX_VALUE ? "" : BlockPos.of(other).toShortString());
          row.put("nearest_in_biggest",otherBig);
          if (open) {
            row.put("under",name(level.getBlockState(at.below()))); row.put("here",name(level.getBlockState(at)));
            row.put("head",name(level.getBlockState(at.above())));
            Map<String,Integer> round = new TreeMap<>();
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 1; dy++) {
              BlockState state = level.getBlockState(at.offset(dx,dy,dz));
              if (!state.isAir()) round.merge(name(state),1,Integer::sum);
            }
            row.put("round",round);
          }
          loose.add(row);
        }
        nets.add(Map.of("era",points == null ? -1 : field(points,"era"),"most_points",points == null ? -1 : field(points,"mostPoints"),
          "sort",call(key,"sort").toString(),"realm",realm,"live",byCell.size(),
          "pieces",pieces.size(),"biggest",biggest < 0 ? 0 : pieces.get(biggest).size(),"loose",loose));
      }
      result.add(Map.of("tick",level.getGameTime(),"footings",footings.size(),"nets",nets));
    }
    return Map.of("ok",true,"graphs",result);
  }
}`,
});
