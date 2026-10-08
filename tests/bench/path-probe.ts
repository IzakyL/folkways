import { defineTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../e2e/colony-tasks";

export async function samplePathQueue(server: MinecraftServer) {
  return runColonyTask(server, PATH_QUEUE, {});
}

// Read-only: reflects over ColonyWays' path graphs and their request queues.
const PATH_QUEUE = defineTask<Record<string, never>, Record<string, any>>({
  name: "folkways.bench.path-queue",
  side: "server",
  timeoutMs: 20_000,
  source: `
import dev.blockwright.api.Context;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.core.BlockPos;
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
  record Key(Object sort, Object realm) {}
  record Targets(Object realm, List<Long> cells) {}
  static class Net {
    Map<Long,Integer> cells, attached;
    int[] part, head, to, pieceOf;
    long[] pieceCells;
    Net(Object net) throws Exception {
      cells = (Map<Long,Integer>)call(net,"byCell");
      attached = (Map<Long,Integer>)call(net,"attached");
      part = (int[])call(net,"part"); head = (int[])call(net,"head"); to = (int[])call(net,"to");
      Object pieces = call(net,"pieces");
      pieceOf = (int[])call(pieces,"of"); pieceCells = (long[])call(pieces,"cell");
    }
    int at(long cell) { return cells.getOrDefault(cell,attached.getOrDefault(cell,-1)); }
    boolean reaches(int start, List<Long> goals) {
      BitSet ends = new BitSet(part.length);
      for (long cell : goals) { int id = at(cell); if (id >= 0) ends.set(id); }
      BitSet seen = new BitSet(part.length);
      int[] queue = new int[part.length]; int tail = 0;
      queue[tail++] = start; seen.set(start);
      for (int cursor = 0; cursor < tail; cursor++) {
        int id = queue[cursor];
        if (ends.get(id)) return true;
        for (int edge = head[id]; edge < head[id+1]; edge++) {
          int next = to[edge];
          if (!seen.get(next)) { seen.set(next); queue[tail++] = next; }
        }
      }
      return false;
    }
    Map<String,Object> summary(Key key) {
      Map<Integer,Integer> sizes = new HashMap<>();
      for (int id : cells.values()) sizes.merge(part[id],1,Integer::sum);
      List<Integer> ordered = new ArrayList<>(sizes.values()); ordered.sort(Comparator.reverseOrder());
      Set<Integer> groups = new HashSet<>();
      for (int id : cells.values()) groups.add(pieceOf[id]);
      int missing = 0;
      List<String> missingCells = new ArrayList<>();
      for (int group : groups) if (at(pieceCells[group]) < 0) {
        missing++;
        if (missingCells.size() < 8) missingCells.add(BlockPos.of(pieceCells[group]).toShortString());
      }
      return Map.of("sort",key.sort().toString(),"realm",key.realm().toString(),
        "slots",part.length,"live",cells.size(),"dead",part.length-cells.size(),
        "components",sizes.size(),"largest",ordered.stream().limit(8).toList(),
        "attached",attached.size(),"edges",to.length,
        "normalization",Map.of("groups",groups.size(),"unknown_representatives",missing,"examples",String.join(" | ",missingCells)));
    }
  }
  static void addTargets(Set<Targets> targets, Collection<WorldPos> cells) {
    Map<Object,List<Long>> realms = new HashMap<>();
    for (WorldPos cell : cells) realms.computeIfAbsent(cell.realm(), k -> new ArrayList<>()).add(cell.cell().asLong());
    for (var e : realms.entrySet()) { e.getValue().sort(null); targets.add(new Targets(e.getKey(),e.getValue())); }
  }
  public static Object run(Context ctx) throws Exception {
    Field graphs = ColonyWays.class.getDeclaredField("GRAPHS"); graphs.setAccessible(true);
    List<Object> result = new ArrayList<>();
    for (Object roaming : ((Map<?,?>)graphs.get(null)).values()) {
      Collection<?> pending = (Collection<?>)field(roaming,"pending");
      Object graph = field(roaming,"published");
      Map<?,?> sorts = (Map<?,?>)field(graph,"sorts");
      Map<Key,Net> nets = new LinkedHashMap<>();
      for (var entry : ((Map<?,?>)field(graph,"nets")).entrySet()) {
        nets.put(new Key(call(entry.getKey(),"sort"),call(entry.getKey(),"realm")),new Net(entry.getValue()));
      }
      Set<Targets> platforms = new HashSet<>();
      for (Object crossing : (List<?>)field(graph,"crossings")) {
        Hop hop = (Hop)call(crossing,"hop");
        addTargets(platforms,hop.boarding().cells()); addTargets(platforms,hop.landing().cells());
      }
      Map<Key,Set<Long>> feet = new HashMap<>();
      for (var entry : ((Map<?,?>)field(graph,"feet")).entrySet()) {
        for (WorldPos foot : (List<WorldPos>)entry.getValue()) {
          feet.computeIfAbsent(new Key(entry.getKey(),foot.realm()),k -> new HashSet<>()).add(foot.cell().asLong());
        }
      }
      Map<String,Integer> reasons = new TreeMap<>(), details = new TreeMap<>(), census = new TreeMap<>();
      List<Object> examples = new ArrayList<>();
      int stride = Math.max(1, (pending.size() + 255) / 256), index = 0, sampled = 0;
      for (Object ask : pending) {
        Object kind = call(ask,"kind"), realm = call(ask,"realm"), sort = sorts.get(kind);
        long from = (long)call(ask,"from");
        List<Long> goals = (List<Long>)call(ask,"goals");
        boolean platform = platforms.contains(new Targets(realm,goals));
        census.merge(kind.toString() + (platform ? "/platform_targets" : "/other_targets"),1,Integer::sum);
        if (index++ % stride != 0) continue;
        sampled++;
        Key key = new Key(sort,realm); Net net = nets.get(key);
        String reason = "no_graph", directed = "not_checked";
        if (net != null) {
          int start = net.at(from);
          if (start < 0) reason = "unknown_origin";
          else {
            reason = "unknown_goals";
            for (long goal : goals) {
              int end = net.at(goal);
              if (end < 0) continue;
              reason = "disconnected";
              if (net.part[start] == net.part[end]) { reason = "same_component"; break; }
            }
            if (reason.equals("same_component")) directed = net.reaches(start,goals) ? "reachable" : "unreachable";
          }
        }
        reasons.merge(reason,1,Integer::sum);
        String category = kind + "/" + (platform ? "platform" : "other") + "/" + reason + "/" + directed;
        details.merge(category,1,Integer::sum);
        boolean occupied = feet.getOrDefault(key,Set.of()).contains(from);
        details.merge("origin/" + (occupied ? "current_feet" : "not_current_feet"),1,Integer::sum);
        if (examples.size() < 8) examples.add(Map.of("kind",kind.toString(),"reason",reason,
          "directed",directed,"platform_targets",platform,"current_feet",occupied,
          "from",BlockPos.of(from).toShortString(),"goals",goals.size(),
          "first_goal",goals.isEmpty() ? "" : BlockPos.of(goals.getFirst()).toShortString()));
      }
      List<Object> summaries = new ArrayList<>();
      for (var entry : nets.entrySet()) summaries.add(entry.getValue().summary(entry.getKey()));
      Object quest = field(roaming,"quest");
      Map<String,Object> active = new LinkedHashMap<>();
      if (quest != null) {
        Object ask = field(quest,"ask");
        active.put("kind",call(ask,"kind").toString()); active.put("steps",field(quest,"steps"));
        active.put("from",BlockPos.of((long)call(ask,"from")).toShortString());
        active.put("goals",((List<?>)call(ask,"goals")).size());
      }
      result.add(Map.of("pending",pending.size(),"connecting",((Collection<?>)field(roaming,"connecting")).size(),
        "sampled",sampled,"reasons",reasons,"details",details,
        "census",census,"nets",summaries,"active_quest",active,"examples",examples,
        "flow",Map.of("received",field(roaming,"receivedRequests"),
          "enqueued",field(roaming,"enqueuedRequests"),"answered_without_search",field(roaming,"answeredRequests"),
          "started",field(roaming,"startedRequests"),"discarded",field(roaming,"discardedRequests"),
          "reviewed",field(roaming,"reviewedRequests"),"swept",field(roaming,"sweptRequests"))));
    }
    return Map.of("ok",true,"graphs",result);
  }
}`,
});

export async function samplePathFlow(server: MinecraftServer) {
  return runColonyTask(server, PATH_FLOW, {});
}

const PATH_FLOW = defineTask<Record<string, never>, Record<string, any>>({
  name: "folkways.bench.path-flow",
  side: "server",
  timeoutMs: 20_000,
  source: `
import dev.blockwright.api.Context;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.server.level.ServerLevel;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
public final class Task {
  static Object field(Object owner, String name) throws Exception {
    Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
  }
  public static Object run(Context ctx) throws Exception {
    Field graphs = ColonyWays.class.getDeclaredField("GRAPHS"); graphs.setAccessible(true);
    List<Object> result = new ArrayList<>();
    for (Object roaming : ((Map<?,?>)graphs.get(null)).values()) {
      result.add(Map.of("tick",((ServerLevel)field(roaming,"level")).getGameTime(),
        "pending",((Collection<?>)field(roaming,"pending")).size(),
        "connecting",((Collection<?>)field(roaming,"connecting")).size(),
        "received",field(roaming,"receivedRequests"),"enqueued",field(roaming,"enqueuedRequests"),
        "answered_without_search",field(roaming,"answeredRequests"),
        "started",field(roaming,"startedRequests"),"discarded",field(roaming,"discardedRequests"),
          "reviewed",field(roaming,"reviewedRequests"),"swept",field(roaming,"sweptRequests")));
    }
    return Map.of("ok",true,"graphs",result);
  }
}`,
});
