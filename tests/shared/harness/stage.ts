// Where a spec builds its scene: on the ground, or on the deck of a Sable ship laid where that ground was. A spec
// keeps its layout in plan coordinates, the cells it would use on the ground, and asks its stage at each boundary:
// at() for the block a command, a container read or a block use addresses, world() for where that block shows, which
// is where the player stands, aims or looks for dropped items. On the ground both are the plan itself. Aboard, the
// ship's blocks live in its plot far off in the same level, and the ship rests where its deck was laid, unrotated
// with its physics paused, so both are plain shifts of the plan.
import { defineTask, javaTask, player, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../colony/colony-tasks";
import { commandPos, tryCommand } from "./bw-helpers";

export type Vec = { x: number; y: number; z: number };
export type Box = { min: Vec; max: Vec };

/** The least box holding every one of `boxes`. */
export function cover(...boxes: Box[]): Box {
  return {
    min: { x: Math.min(...boxes.map((b) => b.min.x)), y: Math.min(...boxes.map((b) => b.min.y)), z: Math.min(...boxes.map((b) => b.min.z)) },
    max: { x: Math.max(...boxes.map((b) => b.max.x)), y: Math.max(...boxes.map((b) => b.max.y)), z: Math.max(...boxes.map((b) => b.max.z)) },
  };
}

/** The box of `cells`, grown by `pad` sideways and `up` above the highest. */
export function around(cells: Vec[], pad = 0, up = 0): Box {
  return {
    min: { x: Math.min(...cells.map((c) => c.x)) - pad, y: Math.min(...cells.map((c) => c.y)), z: Math.min(...cells.map((c) => c.z)) - pad },
    max: { x: Math.max(...cells.map((c) => c.x)) + pad, y: Math.max(...cells.map((c) => c.y)) + up, z: Math.max(...cells.map((c) => c.z)) + pad },
  };
}

const GROUND_CONFIG = "blockwright.toml";
export const SABLE_CONFIG = "blockwright.toml#sable";
const DECK_BLOCK = "minecraft:oak_planks";
const ABOARD_TITLE = " · aboard a Sable ship";
// A player set down exactly on a deck's surface can come out a hair inside the ship and fall through it; set down
// this much above, they drop onto it.
const DECK_LIFT = 0.2;

export interface Stage {
  readonly kind: "ground" | "aboard";
  /** The blockwright config the pair is launched from: aboard needs Sable on both sides. */
  readonly config: string;
  /** The Sable structure the scene stands on, once laid aboard. */
  readonly structure: string | undefined;
  /** The test's title on this stage: as given on the ground, marked aboard. */
  title(base: string): string;
  /** An instance or trace name of this stage's own, so the twins never share one. */
  name(base: string): string;
  /** Readies the floor under `box` (plan cells, min.y the lowest standing cell): aboard, a deck one below. */
  lay(server: MinecraftServer, box: Box): Promise<void>;
  /** The block that commands, container reads and block uses address for the plan cell `cell`. */
  at(cell: Vec): Vec;
  /** Where in the world the plan cell `cell` shows: where to aim or look for entities. */
  world(cell: Vec): Vec;
  /** Where to set a player down to stand at the plan point `at` (its y the floor it stands on). */
  stand(at: Vec): Vec;
  /** Aims at the plan cell `cell` and clicks it, checking the crosshair is on that cell's block. */
  click(mc: { server: MinecraftServer; client: MinecraftClient }, cell: Vec,
    options?: player.ClickBlockOptions): Promise<player.ClickBlockResult>;
}

export const GROUND: Stage = {
  kind: "ground",
  config: GROUND_CONFIG,
  structure: undefined,
  title: (base) => base,
  name: (base) => base,
  lay: async () => {},
  at: (cell) => cell,
  world: (cell) => cell,
  stand: (at) => at,
  click: (mc, cell, options) => player.clickBlock(mc, cell, options),
};

export function aboard(): Stage {
  let offset: Vec | undefined;
  let structure: string | undefined;
  const shift = (): Vec => {
    if (!offset) throw new Error("stage: the ship is not laid yet; call stage.lay before addressing any cell aboard");
    return offset;
  };
  const stage: Stage = {
    kind: "aboard",
    config: SABLE_CONFIG,
    get structure() {
      return structure;
    },
    title: (base) => base + ABOARD_TITLE,
    name: (base) => `${base}-aboard`,
    async lay(server, { min, max }) {
      const deckY = min.y - 1;
      await tryCommand(server, `fill ${min.x} ${deckY} ${min.z} ${max.x} ${max.y} ${max.z} minecraft:air`);
      await tryCommand(server, `fill ${min.x} ${deckY} ${min.z} ${max.x} ${deckY} ${max.z} ${DECK_BLOCK}`);
      const assembled = await tryCommand(server, `sable assemble area ${min.x} ${deckY} ${min.z} ${max.x} ${deckY} ${max.z}`);
      const ship = await shipAt(server, { x: min.x, y: deckY, z: min.z }, `assemble said ${JSON.stringify(assembled)}`);
      offset = ship.offset;
      structure = ship.structure;
      await standOnDeck(server, min, max);
    },
    at(cell) {
      const by = shift();
      return { x: cell.x + by.x, y: cell.y + by.y, z: cell.z + by.z };
    },
    world(cell) {
      shift();
      return cell;
    },
    stand(at) {
      const seen = stage.world(at);
      return { ...seen, y: seen.y + DECK_LIFT };
    },
    async click(mc, cell, options) {
      // player.aim sets the player down where it aims from: on the deck, a little above it.
      const from = options?.from ?? { x: cell.x + 0.5, y: cell.y + 1, z: cell.z + 0.5 };
      const aim = await player.aim(mc.server, stage.world(cell), { ...options, from: stage.stand(from) });
      const click = await player.click(mc.client, { button: options?.button, modifiers: options?.modifiers,
        expect: { pos: stage.at(cell) } });
      return { aim, click };
    },
  };
  return stage;
}

/**
 * The stages a twin spec runs its one body on, each as its own test: `for (const stage of twins())
 * test(stage.title("..."), async ({}, testInfo) => { ... })`. A twin not yet expected to pass aboard says so in its
 * body: `test.fail(stage.kind === "aboard", "why")`.
 */
export function twins(): Stage[] {
  return [GROUND, aboard()];
}

// A player standing on the floor as it is assembled sinks into the deck on its own client before the ship reaches it,
// and once inside the deck nothing holds them up: they fall out of the world. Each is set back on top of the deck.
async function standOnDeck(server: MinecraftServer, min: Vec, max: Vec) {
  const below = min.y - 16;
  const selector = `@a[x=${min.x},y=${below},z=${min.z},dx=${max.x - min.x + 1},dy=${max.y - below},dz=${max.z - min.z + 1}]`;
  await tick.sprint(server, 2);
  await tryCommand(server, `execute as ${selector} at @s run tp @s ~ ${min.y + DECK_LIFT} ~`);
  await tick.sprint(server, 10);
  const players = await world.players(server);
  const sunk = players.filter((one: any) => one.position
    && one.position.x >= min.x && one.position.x <= max.x + 1 && one.position.z >= min.z && one.position.z <= max.z + 1
    && one.position.y < min.y - 0.1);
  if (sunk.length > 0) {
    throw new Error(`stage: ${sunk.map((one: any) => `${one.name} at ${JSON.stringify(one.position)}`).join(", ")} `
      + `sank through the deck laid at y=${min.y - 1} instead of standing on it`);
  }
}

/**
 * The Sable structure whose block shows at the world cell `cell`, with the shift from a world cell to the same cell
 * in its plot. It pauses Sable's physics first, so the ship stays where it is and the shift holds; it throws when
 * no structure shows a block there, or the one that does is rotated.
 */
export async function shipAt(server: MinecraftServer, cell: Vec, context = ""): Promise<{ structure: string; offset: Vec }> {
  const found = await runColonyTask(server, LAY_SHIP, { corner: cell });
  if (!found.found) {
    throw new Error(`stage: no unrotated Sable structure shows a block at ${commandPos(cell)}`
      + `${context ? ` (${context})` : ""}; the task said ${JSON.stringify(found)}`);
  }
  return { structure: String(found.structure), offset: { x: Number(found.dx), y: Number(found.dy), z: Number(found.dz) } };
}

// Finds the structure that now holds the deck's corner, pauses physics so the ship stays exactly where its deck was
// laid, and answers the shift from a world cell to the same cell in the ship's plot.
const LAY_SHIP = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.shared.stage-lay-ship",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "dev.ryanhcode.sable.api.sublevel.SubLevelContainer",
      "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem",
      "net.minecraft.core.BlockPos",
      "net.minecraft.world.level.block.Blocks",
      "net.minecraft.world.phys.Vec3",
    ],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var corner=(Map<?,?>)args.value("corner");
int cx=((Number)corner.get("x")).intValue(),cy=((Number)corner.get("y")).intValue(),cz=((Number)corner.get("z")).intValue();
SubLevelPhysicsSystem.require(level).setPaused(true);
var seen=new ArrayList<Object>();
for(var ship:SubLevelContainer.getContainer(level).getAllSubLevels()){
  var pose=ship.logicalPose();
  var local=pose.transformPositionInverse(new Vec3(cx+0.5,cy+0.5,cz+0.5));
  var cell=BlockPos.containing(local.x(),local.y(),local.z());
  var box=ship.getPlot().getBoundingBox();
  boolean inside=cell.getX()>=box.minX()&&cell.getX()<=box.maxX()&&cell.getY()>=box.minY()&&cell.getY()<=box.maxY()
    &&cell.getZ()>=box.minZ()&&cell.getZ()<=box.maxZ();
  seen.add(Map.of("structure",ship.getUniqueId().toString(),"cell",cell.toShortString(),"pose",pose.toString()));
  if(!inside||level.getBlockState(cell).isAir())continue;
  var q=pose.orientation();
  if(Math.abs(Math.abs(q.w())-1.0)>1.0e-6)
    return Map.of("found",false,"why","the ship came out rotated: "+pose,"seen",seen);
  return Map.of("found",true,"structure",ship.getUniqueId().toString(),
    "dx",cell.getX()-cx,"dy",cell.getY()-cy,"dz",cell.getZ()-cz,"pose",pose.toString());
}
return Map.of("found",false,"seen",seen);
`,
  }),
});
