import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { writeFileSync } from "node:fs";
import { runColonyTask } from "../e2e/colony-tasks";
import { outAbs } from "../out-paths";
import { aim, moveCamera, FILM, type Camera, type Shot } from "./video";
import { call, calls, emptyScene, pick, take, Tail, type Spot } from "./hotspots";

// The roaming cameras' director. Two cameras take turns: while one is on the air the other goes to wherever most of
// the colony is at work, and is cut to once it stands there. A train is filmed whole: a camera waits at the station it
// heads for, is cut to as the train comes into view and stays on the air until it has gone out of it. With nothing
// worth a close look (the night, mostly) the film goes back to the overview. Every cut is kept, and the film is put
// together from them afterwards (video.ts#finishFilm).

const KEY = "folkways.bench.filmFeed";

// Installs (once per game JVM) the feed the director reads while the run goes on: a row for each work report as it is
// made, and once a game second the clock, each train's station, destination, distance to it, speed and riders, and
// where each of the colony stands that has nothing in hand, flushed to a file. Nothing is read from the world but the
// trains and the colony's bodies, and nothing a client sees changes.
const INSTALL = defineTask<{ path: string; colony_id: string }, Record<string, any>>({
  name: "folkways.bench.film-feed-install",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "java.nio.file.Files",
      "java.nio.file.Path",
      "java.nio.file.StandardOpenOption",
      "net.neoforged.bus.api.EventPriority",
      "net.neoforged.neoforge.common.NeoForge",
      "net.neoforged.neoforge.event.tick.ServerTickEvent",
      "net.neoforged.neoforge.server.ServerLifecycleHooks",
      "io.github.izakyl.folkways.core.api.work.WorkProgress",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
    ],
    body: `
var props = System.getProperties();
if (props.get("${KEY}") != null) return Map.of("ok", true, "installed", false);
var path = Path.of(Args.of(ctx).string("path"));
var colonyId = UUID.fromString(Args.of(ctx).string("colony_id"));
Files.createDirectories(path.getParent());
var out = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, WorkProgress.class, event -> {
  long now = ServerLifecycleHooks.getCurrentServer().overworld().getGameTime();
  var node = event.node();
  var site = node.spec().site().cell();
  var row = now + ",W," + event.step() + "," + node.id() + "," + node.getClass().getSimpleName() + ","
    + site.getX() + "," + site.getY() + "," + site.getZ() + "\\n";
  synchronized (out) {
    try { out.write(row); } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
  }
});
NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post.class, event -> {
  var server = event.getServer();
  if (!server.tickRateManager().runsNormally()) return;
  long now = server.overworld().getGameTime();
  if (now % 20 != 0) return;
  var rows = new StringBuilder().append(now).append(",H\\n");
  for (var train : com.simibubi.create.Create.RAILWAYS.trains.values()) {
    var here = train.getCurrentStation();
    var to = train.navigation.destination;
    // Passengers are carried, not put to work: no work report tells of them, so they are counted in their seats.
    var riders = new int[1];
    for (var carriage : train.carriages) carriage.forEachPresentEntity(car -> {
      for (var rider : car.getPassengers()) if (Bodies.of(rider).isPresent()) riders[0]++;
    });
    rows.append(now).append(",T,").append(train.id).append(',').append(here == null ? "" : here.name).append(',')
      .append(to == null ? "" : to.name).append(',').append(Math.round(train.navigation.distanceToDestination)).append(',')
      .append(Math.abs(train.speed)).append(',').append(riders[0]).append('\\n');
  }
  var colony = ColonyGround.of(server, colonyId);
  if (colony.isPresent()) for (var resident : colony.get().residents()) {
    var entity = server.overworld().getEntity(resident.id());
    if (entity == null || !entity.isAlive()) continue;
    var body = Bodies.of(entity);
    if (body.isEmpty() || body.get().doing().isPresent()) continue;
    rows.append(now).append(",I,").append(entity.getBlockX()).append(',').append(entity.getBlockY()).append(',')
      .append(entity.getBlockZ()).append('\\n');
  }
  synchronized (out) {
    try { out.write(rows.toString()); out.flush(); } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
  }
});
props.put("${KEY}", path.toString());
return Map.of("ok", true, "installed", true, "path", path.toString());
`,
  }),
});

export async function installFilmFeed(server: MinecraftServer, path: string, colony_id: string) {
  const { ok, $task, ...out } = await runColonyTask(server, INSTALL, { path, colony_id });
  return out;
}

/** Finds, for each view, where a camera sees it from; the views nothing sees clear are left out and named. */
export async function scout(server: MinecraftServer, shots: Shot[]) {
  const spots: Spot[] = [], blind: string[] = [];
  for (const shot of shots) {
    try {
      const { eye } = await aim(server, shot);
      const t = shot.target;
      spots.push({ name: shot.name, eye, target: t, underground: shot.underground, station: shot.station,
        box: shot.subject ?? { minX: t.x - 12, maxX: t.x + 12, minZ: t.z - 12, maxZ: t.z + 12 } });
    } catch (error) {
      if (!String(error).includes("No clear view")) throw error;
      blind.push(shot.name);
    }
  }
  return { spots, blind };
}

/**
 * One stretch of the film: which camera is on the air, on what, from when to when (the clock Date.now reads). A cut
 * to a train says which, and once it is over how many got off it and on it at the station.
 */
export type Cut = { camera: string; spot?: string; score?: number; train?: string; alighted?: number; boarded?: number;
  from_ms: number; to_ms?: number };

const STEP_MS = 500;
// A spot is worth leaving the overview for with about two at work on it.
const LEAST = 2;
// How long before its cut a camera sets off: a move within the town loads nothing new, one across country does.
const LEAD_MS = 6_000, FAR_LEAD_MS = 16_000, FAR = 160;
// A camera has stood this long before it is cut to, and stays this long after it is cut from: the cuts are timed on
// this clock, not the recording's own, and neither end may show the move.
const GUARD_MS = 1_500;
// A camera that will not move this many times running is left where it is.
const MOST_FAILURES = 3;
// A train is in the station's picture from about this many blocks short of it, and out of it this many ticks after it
// pulls away.
const IN_VIEW = 110, OUT_OF_VIEW_TICKS = 200;
// A camera sent to meet a train needs this many blocks of the train's run to stand there first: a block is about a
// tick of it, so these are LEAD_MS and FAR_LEAD_MS with the guard on top.
const AHEAD = 160, FAR_AHEAD = 360;
// A shot cut short for a train still lasts this long, a train held outside its station is not waited on for more
// than this many holds, and after a train the film shows this many holds of other things.
const SHORTEST_MS = 8_000, MOST_TRAIN_HOLDS = 3, TRAIN_REST_HOLDS = 4;
// A train with no one aboard but its driver lets no one off: it is filmed only when no train has been for this many holds.
const EMPTY_TRAIN_HOLDS = 12;

/**
 * Directs the cameras until stopped. `stands` is the spot each camera starts on. Moving a camera is a teleport of its
 * spectator, so those few commands are asked of the server while it runs; picking the spot reads only the feed file.
 */
export function direct(server: MinecraftServer, cameras: Camera[], spots: Spot[], stands: Array<Spot | undefined>, feedPath: string) {
  const hold = FILM.shot * FILM.speed * 1000;
  const tail = new Tail(feedPath);
  const scene = emptyScene();
  const shown = new Map<string, number>();
  const notes: string[] = [];
  const rigs = cameras.map((camera, i) => ({ camera, spot: stands[i], freeAt: 0, failures: 0 }));
  const cuts: Cut[] = [{ camera: "overview", from_ms: Date.now() }];
  let onAir = -1, last = -1, since = cuts[0].from_ms;
  let next: { rig: number; spot: Spot; score?: number; train?: string; readyAt?: number; failed?: boolean; moving: Promise<void> } | undefined;
  // The train on the air and the station it calls at, with how many rode it in and the fewest that sat in it while it
  // stood there, and when a train may next be filmed.
  let airing: { train: string; station: string; rodeIn: number; fewest: number } | undefined, trainsFrom = 0;
  const riding = (train: string) => scene.trains.get(train)?.riders ?? 0;
  // The train's cut is told how many it let off and took on, now that it is over.
  const seenOff = (now: number) => {
    if (!airing) return;
    const fewest = Math.min(airing.fewest, airing.rodeIn), cut = cuts[cuts.length - 1];
    cut.alighted = airing.rodeIn - fewest;
    cut.boarded = Math.max(0, riding(airing.train) - fewest);
    trainsFrom = now + TRAIN_REST_HOLDS * hold;
    airing = undefined;
  };

  const cutTo = (rig: number, spot?: Spot, score?: number, train?: string) => {
    const now = Date.now();
    seenOff(now);
    cuts[cuts.length - 1].to_ms = now;
    cuts.push({ camera: rig < 0 ? "overview" : rigs[rig].camera.name, spot: spot?.name, score, train, from_ms: now });
    if (onAir >= 0) {
      rigs[onAir].freeAt = now + GUARD_MS;
      if (rigs[onAir].spot) shown.set(rigs[onAir].spot!.name, now);
    }
    if (train && spot?.station) airing = { train, station: spot.station, rodeIn: riding(train), fewest: Infinity };
    onAir = rig;
    since = now;
    if (rig >= 0) last = rig;
    // Kept as they are made: a run that dies still leaves the recordings and what to cut from them.
    try { writeFileSync(outAbs("bench", "film", "cuts.json"), JSON.stringify(cuts)); } catch { /* the film is cut from memory */ }
  };

  const send = (turn: number, spot: Spot, look: { x: number; y: number; z: number }, far: boolean, about: { score: number } | { train: string }) => {
    const rig = rigs[turn];
    const pose = cameraLookingAt(spot.eye, look);
    const going: NonNullable<typeof next> = { rig: turn, spot, moving: Promise.resolve(),
      ...("train" in about ? about : { score: Math.round(about.score * 10) / 10 }) };
    going.moving = moveCamera(rig.camera, server, { eye: spot.eye, yaw: pose.yaw, pitch: pose.pitch, underground: spot.underground },
      { wasUnderground: rig.spot?.underground, far })
      .then(() => { rig.spot = spot; rig.failures = 0; going.readyAt = Date.now(); },
        (error: unknown) => {
          going.failed = true;
          rig.spot = undefined;
          if (++rig.failures >= MOST_FAILURES) notes.push(`camera ${rig.camera.name} stopped roaming: ${String(error).slice(0, 300)}`);
        });
    next = going;
  };

  const step = () => {
    take(scene, tail.rows());
    const now = Date.now(), ran = now - since;
    // A train on the air is seen out of the picture before the film moves on.
    const gone = airing ? call(scene, airing.train, airing.station) : undefined;
    if (airing && gone?.state === "standing") airing.fewest = Math.min(airing.fewest, riding(airing.train));
    const held = gone !== undefined && ran < MOST_TRAIN_HOLDS * hold && !(gone.state === "gone" && gone.ticks >= OUT_OF_VIEW_TICKS);
    if (next?.failed) next = undefined;
    if (next) {
      if (next.readyAt === undefined || now < next.readyAt + GUARD_MS || held) return;
      if (next.train) {
        const coming = call(scene, next.train, next.spot.station!);
        // The camera stood there too late to see the train in: this one is let go.
        if (coming.state !== "coming") next = undefined;
        else if (coming.distance <= IN_VIEW && ran >= SHORTEST_MS) {
          cutTo(next.rig, next.spot, next.score, next.train);
          next = undefined;
        }
      } else if (ran >= hold) {
        cutTo(next.rig, next.spot, next.score);
        next = undefined;
      }
      return;
    }
    const free = rigs.map((_, i) => i).filter((i) => i !== onAir && rigs[i].freeAt <= now && rigs[i].failures < MOST_FAILURES);
    const turn = free.find((i) => i !== last) ?? free[0];
    const taken = new Set(onAir >= 0 && rigs[onAir].spot ? [rigs[onAir].spot!.name] : []);
    const apart = (spot: Spot, rig: number) =>
      Math.hypot(spot.eye.x - (rigs[rig].spot?.eye.x ?? Infinity), spot.eye.z - (rigs[rig].spot?.eye.z ?? Infinity)) > FAR;
    if (turn !== undefined && !airing && now >= trainsFrom) {
      // Of the trains a camera can still get ahead of, and that come into view before the shot on the air has run
      // half as long again as a shot does, the one bringing the most riders, and of those the one for the station
      // shown longest ago.
      const empty = now >= trainsFrom + (EMPTY_TRAIN_HOLDS - TRAIN_REST_HOLDS) * hold;
      const due = calls(spots.filter((s) => !taken.has(s.name)), scene)
        .filter((c) => c.distance >= IN_VIEW + (apart(c.spot, turn) ? FAR_AHEAD : AHEAD) && ran + (c.distance - IN_VIEW) * 50 <= hold * 1.5
          && (c.riders > 1 || empty))
        .sort((a, b) => b.riders - a.riders || (shown.get(a.spot.name) ?? 0) - (shown.get(b.spot.name) ?? 0))[0];
      if (due) {
        send(turn, due.spot, due.spot.target, apart(due.spot, turn), { train: due.train });
        return;
      }
    }
    const best = turn === undefined ? undefined : pick(spots, scene, { taken, shown, wall: now, least: LEAST });
    if (!best) {
      if (onAir >= 0 && ran >= hold && !held) cutTo(-1);
      return;
    }
    const far = apart(best.spot, turn!);
    if (ran < hold - (far ? FAR_LEAD_MS : LEAD_MS)) return;
    // The camera looks half way between the spot's own middle and the middle of what is busy on it.
    const c = best.centre ?? best.spot.target, t = best.spot.target;
    send(turn!, best.spot, { x: (c.x + t.x) / 2, y: (c.y + t.y) / 2, z: (c.z + t.z) / 2 }, far, best);
  };

  let stopped = false, errors = 0;
  const loop = (async () => {
    while (!stopped) {
      try { step(); } catch (error) {
        if (++errors === 1) notes.push(`the director stumbled: ${String(error).slice(0, 300)}`);
        if (errors > 20) { notes.push("the director gave up; the rest is the overview"); if (onAir >= 0) cutTo(-1); break; }
      }
      await new Promise((resolve) => setTimeout(resolve, STEP_MS));
    }
  })();

  return {
    cuts,
    /** Ends the last cut and answers them all. Never throws. */
    async stop() {
      stopped = true;
      await loop;
      await next?.moving;
      tail.close();
      seenOff(Date.now());
      cuts[cuts.length - 1].to_ms ??= Date.now();
      return { cuts, notes, hold_ms: hold, spots: spots.length };
    },
  };
}

export type Directing = ReturnType<typeof direct>;
