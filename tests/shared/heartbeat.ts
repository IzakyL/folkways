export type ColonyBeat = {
  colony?: string;
  dimension?: string;
  bodies: number;
  messages: number;
  nodes: number;
  tours: number;
  phases: Record<string, number>;
  done: number;
  completed?: Record<string, number>;
  released: number;
  why: Record<string, number>;
  unassigned: number;
  unassignedNodes?: Record<string, string>;
  shortfall: number;
  turned: number;
  refused: Record<string, number>;
  broke: Record<string, number>;
  standing: Record<string, number>;
  skipped: number;
  solving: boolean;
  solving_seconds: number;
};

export type NavBeat = {
  colonies: number;
  searches: number;
  nodes: number;
  peakSearchesPerTick: number;
  peakNodesPerTick: number;
  stepSlices: number;
  wallSlices: number;
  graph: { points: number; edges: number; shut: number; parts: number } | null;
  disproved: number;
  asksPending: number;
  quests: { found: number; denied: number; gaveUp: number } | null;
  denied: Record<string, number>;
  renumbered: number;
  straightened: number;
  rambles: { ran: number; edges: number } | null;
  publish: { straightenMicros: number; freezeMicros: number; maxMicros: number } | null;
  hops: number;
  attached: { laid: number; closed: number; far: number } | null;
  segments: { struck: number; walked: number } | null;
  budget: BudgetBeat | null;
};

export type BudgetBeat = {
  ceilingMicros: number;
  floorMicros: number;
  ceilingCapMicros: number;
  climbs: number;
  backOffs: number;
  spentAvgMicros: number;
  spentMaxMicros: number;
  workAvgMicros: number;
  workMaxMicros: number;
  late: number;
  over: number;
  ticks: number;
};

const num = (text: string, field: string): number | undefined => {
  const hit = text.match(new RegExp(`(?:^|[ \\t])${field}=(-?\\d+)(?![\\w.])`));
  return hit ? Number(hit[1]) : undefined;
};

function counted(body: string | undefined): Record<string, number> {
  const out: Record<string, number> = {};
  if (!body) return out;
  for (const entry of body.split(",")) {
    const kv = entry.trim().match(/^(.+)=(-?\d+)$/);
    if (kv) out[kv[1].trim()] = Number(kv[2]);
  }
  return out;
}

const sumOf = (counts: Record<string, number>): number =>
  Object.values(counts).reduce((total, n) => total + n, 0);

export function isNavLine(line: string): boolean {
  return line.includes("labor heartbeat colonies=");
}

function parseBudget(line: string): BudgetBeat | null {
  const head = line.match(
    / budget=(\d+)us\(floor(\d+)us\/ceil(\d+)us\) aimd=\+(\d+)\/-(\d+)/);
  if (!head) return null;
  const spent = line.match(/ spent=(\d+)usavg\/(\d+)usmax/);
  const work = line.match(/ work=(\d+)usavg\/(\d+)usmax/);
  const over = line.match(/ over=(\d+)\/(\d+)/);
  return {
    ceilingMicros: Number(head[1]),
    floorMicros: Number(head[2]),
    ceilingCapMicros: Number(head[3]),
    climbs: Number(head[4]),
    backOffs: Number(head[5]),
    spentAvgMicros: Number(spent?.[1] ?? 0),
    spentMaxMicros: Number(spent?.[2] ?? 0),
    workAvgMicros: Number(work?.[1] ?? 0),
    workMaxMicros: Number(work?.[2] ?? 0),
    late: num(line, "late") ?? 0,
    over: Number(over?.[1] ?? 0),
    ticks: Number(over?.[2] ?? 0),
  };
}

export function parseNav(line: string): NavBeat | null {
  if (!isNavLine(line)) return null;
  const slices = line.match(/ slices=(\d+)steps\/(\d+)wall/);
  const graph = line.match(/ graph=(\d+)pts\/(\d+)edges\/(\d+)shut\/(\d+)parts/);
  const quests = line.match(/ quests=(\d+)found\/(\d+)denied\/(\d+)gaveup/);
  const rambles = line.match(/ rambles=(\d+)\/(\d+)edges/);
  const publish = line.match(/ publish=(\d+)usstraighten\/(\d+)usfreeze\/(\d+)usmax/);
  const attached = line.match(/ attached=(\d+)laid\/(\d+)closed\/(\d+)far/);
  const segments = line.match(/ segments=(\d+)struck\/(\d+)walked/);
  const denied: Record<string, number> = {};
  for (const part of (line.match(/ denied=([\w/]+)/)?.[1] ?? "").split("/")) {
    const kv = part.match(/^(\d+)([A-Za-z]+)$/);
    if (kv) denied[kv[2]] = Number(kv[1]);
  }
  return {
    colonies: num(line, "colonies") ?? 0,
    searches: num(line, "searches") ?? 0,
    nodes: num(line, "nodes") ?? 0,
    peakSearchesPerTick: Number(line.match(/ peakSearches\/tick=(\d+)/)?.[1] ?? 0),
    peakNodesPerTick: Number(line.match(/ peakNodes\/tick=(\d+)/)?.[1] ?? 0),
    stepSlices: Number(slices?.[1] ?? 0),
    wallSlices: Number(slices?.[2] ?? 0),
    graph: graph
      ? { points: Number(graph[1]), edges: Number(graph[2]), shut: Number(graph[3]), parts: Number(graph[4]) }
      : null,
    disproved: Number(line.match(/ disproved=(\d+)pts/)?.[1] ?? 0),
    asksPending: num(line, "asksPending") ?? 0,
    quests: quests
      ? { found: Number(quests[1]), denied: Number(quests[2]), gaveUp: Number(quests[3]) }
      : null,
    denied,
    renumbered: num(line, "renumbered") ?? 0,
    straightened: Number(line.match(/ straightened=(\d+)pts/)?.[1] ?? 0),
    rambles: rambles
      ? { ran: Number(rambles[1]), edges: Number(rambles[2]) }
      : null,
    publish: publish
      ? {
        straightenMicros: Number(publish[1]),
        freezeMicros: Number(publish[2]),
        maxMicros: Number(publish[3]),
      }
      : null,
    hops: num(line, "hops") ?? 0,
    attached: attached
      ? { laid: Number(attached[1]), closed: Number(attached[2]), far: Number(attached[3]) }
      : null,
    segments: segments ? { struck: Number(segments[1]), walked: Number(segments[2]) } : null,
    budget: parseBudget(line),
  };
}

const COLONY_HEAD =
  / bodies=(\d+) messages=(\d+) nodes=(\d+) tours=(\d+) phases=\{([^}]*)\} done=(\d+) released=(\d+)/;

export function parseColony(line: string): ColonyBeat | null {
  if (isNavLine(line)) {
    return null;
  }
  const head = line.match(COLONY_HEAD);
  if (!head) {
    return null;
  }
  const who = line.match(/([0-9a-f-]{36})@([a-z0-9_.-]+:[a-z0-9_./-]+)/);
  const why = counted(line.match(/ unassigned=\{([^}]*)\}/)?.[1]);
  const waiting = line.match(/ unassignedNodes=\{([^}]*)\}/);
  return {
    colony: who?.[1],
    dimension: who?.[2],
    bodies: Number(head[1]),
    messages: Number(head[2]),
    nodes: Number(head[3]),
    tours: Number(head[4]),
    phases: counted(head[5]),
    done: Number(head[6]),
    released: Number(head[7]),
    why,
    unassigned: sumOf(why),
    unassignedNodes: waiting
      ? Object.fromEntries([...waiting[1].matchAll(/([0-9a-f-]{36})=([A-Z_]+)/g)].map((match) => [match[1], match[2]]))
      : undefined,
    shortfall: num(line, "shortfall") ?? 0,
    turned: num(line, "turned") ?? 0,
    refused: counted(line.match(/ refused=\{([^}]*)\}/)?.[1]),
    completed: counted(line.match(/ completed=\{([^}]*)\}/)?.[1]),
    broke: counted(line.match(/ broke=\{([^}]*)\}/)?.[1]),
    standing: counted(line.match(/ standing=\{([^}]*)\}/)?.[1]),
    skipped: num(line, "skipped") ?? 0,
    solving: / solving(=\d+s)?$/.test(line.trimEnd()),
    solving_seconds: Number(line.trimEnd().match(/ solving=(\d+)s$/)?.[1] ?? 0),
  };
}
