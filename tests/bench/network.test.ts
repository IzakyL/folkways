import assert from "node:assert/strict";
import test from "node:test";
import { Lie, PIECE, Walk, clashes, piecesOf, planNetwork, selfCrossings, trackOf, type Network, type Pt, type Relief } from "./network.ts";

// Gentle temperate land on a 16-block grid around the origin, with a mountain rising west of x = -420; `at` can sink a
// lake into it or flatten the mountain.
function relief(at: (x: number, z: number) => { h: number; kind: number } | null = () => null): Relief {
  const step = 16, half = 1600, n = (half * 2) / step + 1;
  const height: number[] = [], kind: number[] = [];
  for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) {
    const x = -half + i * step, z = -half + j * step;
    const rise = Math.max(0, Math.min(90, (-420 - x) / 3));
    const here = at(x, z) ?? { h: 70 + Math.round(2 * Math.sin(x / 90) * Math.cos(z / 110) + rise), kind: 3 };
    height.push(here.h);
    kind.push(here.kind);
  }
  return { minX: -half, minZ: -half, step, width: n, depth: n, sea: 63, height, kind };
}

const CITY = { x: 0, z: 0, level: 71, halfW: 106, halfD: 106 };
const steps = (a: Pt, b: Pt) => Math.max(Math.abs(b.x - a.x), Math.abs(b.z - a.z));
const heading = (a: Pt, b: Pt) => [Math.sign(b.x - a.x), Math.sign(b.z - a.z)];

let open: Network | undefined;
const plain = () => (open ??= planNetwork(relief(), CITY));

test("the line loops from the city's west edge out to a mine at the mountain's foot", () => {
  const net = plain();
  assert.deepEqual(net.refused, []);
  assert.deepEqual(net.lines.map((l) => l.stops.map((s) => s.name)), [["City", "Mine"]]);
  assert.deepEqual(clashes(net), []);
  const [line] = net.lines, [city, mine] = line.stops, [works] = line.works;
  // The rail lies in the city's ground, and the city's halt stands flush against its west edge at street level.
  assert.equal(city.position.y, CITY.level);
  const halt = line.halts.find((h) => h.stop === "City")!.halt;
  assert.equal(halt.box.maxX, CITY.x - CITY.halfW - 1);
  assert.equal(halt.y, CITY.level + 1);
  assert.equal(halt.door, "east");
  // All of the track runs west of the city.
  for (const p of line.points) assert.ok(p.x < CITY.x - CITY.halfW, `track at ${p.x},${p.z} runs into the city`);
  assert.ok(works.x < -420, `the mine stands on the mountain (${works.x})`);
  assert.ok(Math.hypot(mine.position.x - works.x, mine.position.z - works.z) < 80, "the mine's station stands by it");
});

test("each line is track the railway pattern can lay: axis or 45° straights, real corners, room for every curve", () => {
  for (const l of plain().lines) {
    const t = trackOf(l);
    for (let i = 0; i + 1 < t.length; i++) {
      const a = t[i], b = t[i + 1];
      const dx = Math.abs(b.x - a.x), dz = Math.abs(b.z - a.z);
      assert.ok(dx === 0 || dz === 0 || dx === dz, `${l.name}: ${a.x},${a.z} -> ${b.x},${b.z} runs aslant`);
      assert.ok(steps(a, b) >= 21, `${l.name}: ${a.x},${a.z} -> ${b.x},${b.z} leaves no room for its curves`);
      const c = t[(i + 2) % (t.length - 1)];
      const [h1, h2] = [heading(a, b), heading(b, c)];
      const dot = h1[0] * h2[0] + h1[1] * h2[1];
      assert.ok(!(h1[0] === h2[0] && h1[1] === h2[1]), `${l.name}: ${b.x},${b.z} is no corner`);
      assert.ok(dot > 0, `${l.name}: ${b.x},${b.z} bends more than 45°`);
    }
  }
});

test("every stop stands beside a level straight of its own line, on the track's level", () => {
  for (const l of plain().lines) for (const s of l.stops) {
    const side = s.side ?? 1, axis = s.axis ?? "z";
    const track = axis === "z" ? { x: s.position.x - side, z: s.position.z } : { x: s.position.x, z: s.position.z - side };
    const t = trackOf(l);
    const leg = t.findIndex((a, i) => {
      const b = t[i + 1];
      if (!b || a.y !== b.y) return false;
      const n = steps(a, b), k = steps(a, { ...track, y: 0 });
      return k <= n && a.x + Math.sign(b.x - a.x) * k === track.x && a.z + Math.sign(b.z - a.z) * k === track.z;
    });
    assert.ok(leg >= 0, `${s.name} stands beside a level straight of ${l.name}`);
    assert.equal(s.position.y, t[leg].y + 1, `${s.name} stands on the track's level`);
  }
});

test("a line is drawn in pieces that meet rail to rail on level straights and stay within the pattern's budget", () => {
  for (const l of plain().lines) {
    const pieces = piecesOf(l);
    let total = 0;
    for (const [i, p] of pieces.entries()) {
      const length = p.slice(1).reduce((n, q, k) => n + steps(p[k], q), 0);
      total += length;
      // A railway piece of ~150 blocks drew in ≤2.4M steps; the pattern budget is 20M, so a piece well past PIECE still draws.
      assert.ok(length <= PIECE + 100, `${l.name} piece ${i} runs ${length}`);
      const next = pieces[(i + 1) % pieces.length][0], end = p[p.length - 1];
      assert.ok(steps(end, next) <= 1 && end.y === next.y, `${l.name} piece ${i} meets the next`);
    }
    assert.ok(Math.abs(total - l.length) <= 2, `${l.name}'s pieces cover its ${l.length} blocks (${total})`);
  }
});

test("the line never runs over its own track", () => {
  for (const l of plain().lines) assert.deepEqual(selfCrossings(l), [], `${l.name} crosses itself`);
});

test("land with no mountain to the west gets no line, and says why", () => {
  const net = planNetwork(relief((x, z) => x < -200 ? { h: 70, kind: 3 } : null), CITY);
  assert.deepEqual(net.lines, []);
  assert.ok(net.refused.some((r) => r.what === "Mine line" && /mountain/.test(r.why)), JSON.stringify(net.refused));
});

test("a walk goes round a river to its ford, and riding across it then saves minutes", () => {
  // A river north to south 200 blocks east of the town, fordable only 1200 blocks south.
  const land = relief((x, z) => x < -300 ? { h: 70, kind: 3 } : x >= 192 && x <= 240 && z < 1200 ? { h: 60, kind: 1 } : null);
  const walk = new Walk(new Lie(land), { x: 0, z: 0 }, 1600);
  const across = walk.commute({ x: 0, z: 0 }, 400, 0)!;
  assert.ok(across.walk > 2 * 1200, `the walk across goes round by the ford (${across.walk})`);
  assert.ok(across.saved_s > 300, `riding saves minutes (${across.saved_s}s)`);
  const near = walk.commute({ x: 0, z: 0 }, -400, 0)!;
  assert.ok(near.walk < 440 && near.saved_s < 30, `open land walks straight, and riding saves little (${near.walk}, ${near.saved_s}s)`);
});
