import { launchPair, type E2EPair } from "../shared/pair";
import { expect, test } from "@playwright/test";
import { camera, defineTask, screen, type TaskTemplate } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { readdirSync, readFileSync } from "node:fs";
import { VisualRun } from "./visual-kit";
import { tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { frames, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

type Vec = { x: number; y: number; z: number };

type View = { name: string; position: Vec; target: Vec; fov?: number; subject: string };

type Gallery = {
  pattern: string;
  task: TaskTemplate<Record<string, any>, any>;
  args: () => Record<string, any>;
  stand: string;
  forceload: string;
  before?: string[];
  check?: (value: any) => void;
  views: (value: any) => View[];
};

const PATTERNS = "src/main/resources/data/folkways/folkways/pattern";
const CAMERA = "tests/visual/arch-bridge/CameraSetup.java";
const SETTLE_MS = 5_000;

// Pins the render distance and pushes the fog out, so every shot sees the whole gallery.
const CAMERA_SETUP = defineTask<void, unknown>({
  name: "folkways.visual.camera-setup",
  side: "client",
  timeoutMs: 20_000,
  source: readFileSync(CAMERA, "utf8"),
});

/** A gallery's Java file (class Task) as a server task that lays the gallery out and answers its report. */
const galleryTask = (pattern: string, java: string) => defineTask<Record<string, any>, any>({
  name: `folkways.visual.gallery.${pattern.replace(/_/g, "-")}`,
  side: "server",
  timeoutMs: 180_000,
  source: readFileSync(java, "utf8"),
});

const star = (name: string) => readFileSync(`${PATTERNS}/${name}.star`, "utf8");
const json = (file: string) => JSON.parse(readFileSync(file, "utf8"));
const v = (x: number, y: number, z: number): Vec => ({ x, y, z });
const view = (name: string, position: Vec, target: Vec, subject: string, fov?: number): View =>
  ({ name, position, target, subject, fov });

function mineViews(value: any): View[] {
  const surface: number = value.surface;
  const [rx0, , rz0, rx1, , rz1] = value.rock as number[];
  const inside = (p: Vec): Vec => ({
    x: Math.min(rx1 - 2, Math.max(rx0 + 2, p.x)),
    y: Math.min(surface - 5, p.y),
    z: Math.min(rz1 - 2, Math.max(rz0 + 2, p.z)),
  });
  const views: View[] = [
    view("overview", v(0, surface + 34, 74), v(0, surface, 0), "Two mine heads side by side: the default spruce headframe on the iron layer (left), an oak headframe on the copper layer (right)", 60),
  ];
  for (const mine of value.mines) {
    const [mx0, my0, mz0, mx1, , mz1] = mine.mouth as number[];
    const mouth = v((mx0 + mx1) / 2, surface + 2, (mz0 + mz1) / 2);
    const [dx0, dy0, dz0, dx1, , dz1] = mine.dug as number[];
    const middle = v((dx0 + dx1) / 2, dy0 + 1, (dz0 + dz1) / 2);
    const half = Math.max(dx1 - dx0, dz1 - dz0) / 2;
    const toward = { x: Math.sign(-middle.x) || 1, z: Math.sign(middle.z - mouth.z) || 1 };
    views.push(
      view(`${mine.name}-headframe`, v(mouth.x + 11, surface + 8, mouth.z + 13), mouth,
        `${mine.name} mine head: headframe, fence, eaves and hanging lanterns, everything above ground`, 55),
      view(`${mine.name}-xray-plan`, inside(v(middle.x + 0.5, surface - 5, middle.z + 0.5)), v(middle.x + 0.5, dy0, middle.z + 1),
        `${mine.name} x-ray top-down (camera inside the rock): plan of the spiral stair shaft, the bottom main drift and the side drifts`, 90),
      view(`${mine.name}-xray-oblique`,
        inside(v(middle.x + toward.x * (half + 10), dy0 + half + 12, middle.z + toward.z * (half + 10))), middle,
        `${mine.name} x-ray oblique: stairs winding down from the shaft top, posts and beams on each stretch, lanterns and chased-out veins`, 70),
      view(`${mine.name}-xray-shaft`, inside(v(mouth.x - 14, my0 - 6, mouth.z - 14)), v(mouth.x, my0 - 16, mouth.z),
        `${mine.name} x-ray of the shaft: a 24-block spiral stair loop, corner landings and supports`, 70),
    );
  }
  return views;
}

const GALLERIES: Gallery[] = [
  {
    pattern: "arcade",
    task: galleryTask("arcade", "tests/visual/arcade/ArcadeGallery.java"),
    args: () => ({ arcade: star("arcade") }),
    stand: "0 40 0",
    forceload: "-80 -80 80 80",
    check: (value) => {
      const arcades: any[] = value.arcades;
      const refused = arcades.filter((one) => one.refused);
      expect(refused, `arcade: some arcades were not drawn ${JSON.stringify(refused)}`).toEqual([]);
      for (const one of arcades) {
        expect(one.reports.framed, `arcade ${one.points}: the dressing phase saw ${one.reports.framed} of`
          + ` ${one.reports.bays} bays`).toBe(one.reports.bays);
      }
      expect(arcades[0].reports.lamps ?? 0, "arcade with lights and railing set no lamp").toBeGreaterThan(0);
    },
    views: () => [
      view("overview", v(0, 70, 80), v(-5, 8, -5), "Arcade overview: four arcades over hills", 60),
      view("straight-side", v(-40, 16, -34), v(-40, 8, -48), "Straight arcade side: bays stepping down the slope", 70),
      view("straight-walk", v(-36, 9, -48), v(-20, 6, -48), "Walking down the arcade: ramps between bays, lanterns on the railing", 80),
      view("bend", v(-34, 30, 10), v(-12, 12, -12), "Bend climbing a hill, a pavilion at the turn", 70),
      view("bend-pavilion", v(-4, 16, -20), v(-12, 12, -12), "Pavilion at the bend: hipped roof over four posts", 70),
      view("zigzag", v(30, 30, -8), v(36, 10, -30), "Zigzag with a cornerwise stretch, shed roofs, open sides", 75),
      view("wide", v(30, 22, 50), v(40, 10, 34), "Wide arcade up a long slope", 75),
      view("wide-inside", v(22, 15, 24), v(40, 10, 33), "Inside the wide arcade", 80),
    ],
  },
  {
    pattern: "arch_bridge",
    task: galleryTask("arch_bridge", "tests/visual/arch-bridge/BridgeGallery.java"),
    args: () => ({ source: star("arch_bridge") }),
    stand: "0 25 8",
    forceload: "-64 -64 64 64",
    before: [
      "fill -48 -3 -38 48 -1 54 minecraft:stone",
      "fill -48 0 -38 48 0 54 minecraft:water",
      ...[1, 4, 7, 10, 13, 16].map((y) => `fill -48 ${y} -38 48 ${y + 2} 54 minecraft:air`),
    ],
    views: () => {
      const { position, target } = json("tests/visual/arch-bridge/camera.json");
      return [
        view("gallery", position, target, "Arch bridge overview: samples of each span, material and abutment laid out over water", 55),
        view("sandstone-detail", v(26, 13, 18), v(20, 6, -4), "Sandstone arch bridge close-up: where arch, deck and railing meet", 50),
        view("straight-side", v(18, 11, -49), v(18, 5, -22), "Straight bridge side: the arch profile", 55),
        view("straight-entry", v(-8, 11, -13), v(17, 5, -22), "Straight bridge approach: stepping from the bank onto the deck", 55),
      ];
    },
  },
  {
    pattern: "city_wall",
    task: galleryTask("city_wall", "tests/visual/city-wall/WallGallery.java"),
    args: () => ({ source: star("city_wall") }),
    stand: "0 40 0",
    forceload: "-80 -80 80 80",
    views: () => (json("tests/visual/city-wall/cameras.json") as any[]).map((c) =>
      view(c.name, c.position, c.target, `City wall · ${c.name}`, c.fov ?? 60)),
  },
  {
    pattern: "dock",
    task: galleryTask("dock", "tests/visual/dock/DockGallery.java"),
    args: () => ({ source: star("dock"), ...json("tests/visual/dock/scenes.json") }),
    stand: "0 25 40",
    forceload: "-64 -32 64 48",
    views: () => {
      const { scenes } = json("tests/visual/dock/scenes.json");
      return [
        view("overview", v(0, 36, 62), v(0, 0, 8), "Dock overview: five shore types and the dock heads side by side", 60),
        ...(scenes as any[]).flatMap((s) => [
          view(`s${s.x}-quarter`, v(s.x + 13, 9, s.z + 12), v(s.x + s.dx / 2, 1, s.z - 6), `Dock x=${s.x} three-quarter view`, 55),
          view(`s${s.x}-side`, v(s.x + 11, 4, s.z - 4), v(s.x, 1, s.z - 7), `Dock x=${s.x} side: piles and beams`, 55),
          view(`s${s.x}-shore`, v(s.x - 7, 7, -12), v(s.x + s.dx, 1, s.z), `Dock x=${s.x} looking back at the shore from the water`, 55),
        ]),
      ];
    },
  },
  {
    pattern: "railway",
    task: galleryTask("railway", "tests/visual/railway/RailwayGallery.java"),
    args: () => ({ source: star("railway") }),
    stand: "0 40 20",
    forceload: "-64 -32 64 250",
    check: (value) => {
      const refused = Object.entries(value).filter(([, one]: [string, any]) => one?.refused);
      expect(refused, `railway: some routes were not drawn ${JSON.stringify(refused)}`).toEqual([]);
    },
    views: () => [
      view("mixed-overview", v(-10, 42, 46), v(-6, 4, 0), "Mixed route overview: tunnel, bridge, cutting and embankment", 60),
      view("tunnel-portal", v(-2, 12, 9), v(-12, 7, 0), "Tunnel portal", 60),
      view("bridge", v(10, 6, 20), v(10, 0, 0), "Railway bridge", 60),
      view("cut-and-fill", v(26, 14, 16), v(38, 3, 0), "Cutting and embankment", 60),
      view("track-level", v(44, 6, 3), v(30, 4, 0), "Track level view", 60),
      view("route-overview", v(-30, 50, 110), v(-5, 8, 62), "Climbing, curving route overview", 60),
      view("route-climb", v(-34, 16, 52), v(-24, 7, 40), "Route climb", 60),
      view("route-bend", v(-16, 20, 58), v(0, 11, 46), "Route bend", 60),
      view("route-curve-level", v(30, 13, 62), v(18, 11, 72), "Curve at track level", 60),
      view("diagonal", v(50, 30, 106), v(24, 4, 136), "Diagonal route", 60),
      view("viaduct", v(6, 8, 250), v(0, -2, 220), "Viaduct", 60),
      view("viaduct-low", v(18, -6, 234), v(0, -2, 220), "Viaduct from below", 60),
    ],
  },
  {
    pattern: "road",
    task: galleryTask("road", "tests/visual/road/RoadGallery.java"),
    args: () => ({ road: star("road"), grading: star("grading") }),
    stand: "0 40 0",
    forceload: "-80 -80 80 80",
    views: () => [
      view("overview", v(10, 70, 75), v(-5, 8, -5), "Road overview: four roads over hills", 60),
      view("hill-cut", v(26, 26, -34), v(2, 12, -10), "Cutting through a hill", 60),
      view("bend", v(-38, 28, -14), v(-20, 10, -30), "Bend", 60),
      view("causeway", v(48, 22, 36), v(30, 8, 30), "Causeway over water", 60),
      view("mud-road", v(-44, 26, 14), v(-22, 10, 34), "Mud brick road", 60),
      view("mud-bend", v(-30, 18, 26), v(-18, 6, 38), "Mud brick road bend", 60),
      view("walk", v(-48, 17, -40), v(-10, 10, -30), "Road surface level view", 60),
    ],
  },
  {
    pattern: "terraced_field",
    task: galleryTask("terraced_field", "tests/visual/terraced-field/FieldGallery.java"),
    args: () => ({ source: star("terraced_field") }),
    stand: "10 40 20",
    forceload: "-80 -48 112 80",
    check: (value) => {
      const dry = value.fields.flatMap((field: any) => field.dry ?? []);
      expect(dry, "terraced field: some farmland cannot reach water").toEqual([]);
    },
    views: () => [
      view("overview", v(16, 58, -64), v(16, 4, 6), "Terraced field overview", 60),
      view("hill-gate", v(16, 9, -7), v(16, 5, 1), "Hillside terrace entrance", 60),
      view("flat-field", v(-30, 20, 30), v(-42, 3, 3), "Flat field", 60),
      view("flat-gate", v(-42, 9, 26), v(-41, 4, 12), "Flat field entrance", 60),
      view("hill-front", v(16, 16, -22), v(16, 10, 16), "Terraces from the front", 60),
      view("hill-side", v(52, 30, 26), v(16, 12, 18), "Terraces from the side", 60),
      view("hill-steps", v(22, 14, 4), v(16, 8, 14), "Terrace steps", 60),
      view("slope-x", v(68, 26, 30), v(68, 8, -4), "Slope along x", 60),
      view("slope-x-low", v(40, 10, -4), v(70, 8, -4), "Slope along x, low angle", 60),
    ],
  },
  {
    pattern: "mine",
    task: galleryTask("mine", "tests/visual/mine/MineGallery.java"),
    args: () => ({ source: star("mine") }),
    stand: "0 110 0",
    forceload: "-80 -44 80 44",
    check: (value) => {
      const unfinished = value.mines.filter((mine: any) => mine.end !== "ended").map((mine: any) => `${mine.name}: ${mine.end}`);
      expect(unfinished, "the mine did not finish growing").toEqual([]);
    },
    views: mineViews,
  },
];

test.describe.configure({ mode: "parallel" });

test("every pattern has a gallery", () => {
  const patterns = readdirSync(PATTERNS)
    .filter((file) => file.endsWith(".star"))
    .map((file) => file.slice(0, -".star".length))
    .filter((name) => /^def (draw|grow)\(site\)/m.test(star(name)));
  expect(GALLERIES.map((gallery) => gallery.pattern).sort(), "every pattern needs a gallery")
    .toEqual(patterns.sort());
});

for (const gallery of GALLERIES) {
  test(`pattern gallery: ${gallery.pattern}`, async ({}, testInfo) => {
    test.setTimeout(BUDGET.heavy);
    const scope = `pattern-${gallery.pattern.replace(/_/g, "-")}`;
    const run = new VisualRun(scope);
    const runId = newRunId();
    let pair: E2EPair | undefined;
    try {
      pair = await launchPair({
        config: "blockwright.toml#promo",
        server: {
          trace: tracePath("visual", `${scope}-server`),
          instance: seededOffThreadServer(`visual-${scope}-server-${runId}`),
          startFrozen: false,
        },
        client: { trace: tracePath("visual", `${scope}-client`), instance: `visual-${scope}-client-${runId}` },
      });
      const { server, client } = pair;
      for (const command of [
        "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
        "gamerule randomTickSpeed 0", "time set 5000", "weather clear", "gamemode spectator @a",
        `tp @a ${gallery.stand}`, `forceload add ${gallery.forceload}`, ...(gallery.before ?? []),
      ]) {
        await tryCommand(server, command);
      }
      await screen.dismiss(client);
      await client.run(CAMERA_SETUP, undefined);

      let value: any;
      try {
        value = await server.run(gallery.task, gallery.args());
      } catch (error) {
        run.note("report", { status: "error", error: String(error) });
        throw new Error(`${gallery.pattern}: gallery script failed ${String(error).slice(0, 2000)}`, { cause: error });
      }
      run.note("report", value);
      if (value && typeof value === "object" && "ok" in value) {
        expect(value.ok, `${gallery.pattern}: gallery report not ok ${JSON.stringify(value).slice(0, 2000)}`).toBe(true);
      }
      gallery.check?.(value);

      const views = gallery.views(value);
      await new Promise((resolve) => setTimeout(resolve, SETTLE_MS));
      for (const one of views) {
        const rotation = cameraLookingAt(one.position, one.target);
        await camera.set(client, { position: one.position, ...rotation });
        await frames(client, 30);
        await run.shot(client, one.name, {
          subject: one.subject,
          worldState: { pattern: gallery.pattern, target: one.target },
          camera: { position: one.position, ...rotation, fov: one.fov ?? 60 },
        });
      }
      run.expectTaken(views.map((one) => one.name));
    } catch (error) {
      pair?.failing(error);
      throw error;
    } finally {
      await pair?.teardown(testInfo);
    }
  });
}
