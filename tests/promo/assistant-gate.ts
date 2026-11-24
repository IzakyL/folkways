import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { tryCommand } from "../shared/harness/bw-helpers";
import type { Vec } from "../shared/colony/colony-founding";

// A drawbridge over a moat with nothing built round it, so its whole machine stands in the open: a wooden bridge
// standing up at the road's end, a Create mechanical bearing at its foot holding it, and gearboxes and a shaft
// carrying the turn of a copper valve handle beside the road down to the bearing. The colony lives on the road's
// side of the moat. Nothing says which way the handle lowers the bridge: one use turns it a quarter turn, a plain use
// one way and a sneaking use the other, and only one way lays the deck across the moat. That is what the assistant
// calibrates.
//
// The bearing stands at `o`; the road runs east-west through z+1..z+2, the moat east of the bridge at x+2..x+5, the
// colony west of it.

export type Gate = { valve: Vec; bearing: Vec; deck: Vec[]; inside: Vec; outside: Vec };

export function gateLayout(o: Vec): Gate {
  const at = (x: number, y: number, z: number): Vec => ({ x: o.x + x, y: o.y + y, z: o.z + z });
  return {
    valve: at(-3, 1, -1), bearing: at(1, 0, 0),
    // Raised, the deck stands upright at the road's end; lowered, it lies across the moat.
    deck: [1, 2].flatMap(z => [0, 1, 2, 3, 4].map(y => at(1, y, z))),
    inside: at(-6, 1, 1), outside: at(8, 1, 1),
  };
}

export function gateCommands(o: Vec): string[] {
  const r = (x: number, y: number, z: number) => `${o.x + x} ${o.y + y} ${o.z + z}`;
  const out: string[] = [];
  const fill = (a: number[], b: number[], block: string) => out.push(`fill ${r(a[0], a[1], a[2])} ${r(b[0], b[1], b[2])} ${block}`);
  const set = (a: number[], block: string) => out.push(`setblock ${r(a[0], a[1], a[2])} ${block}`);
  // A clean meadow, a moat cut across it and the road raised a step above it.
  fill([-14, 0, -14], [14, 14, 16], "minecraft:air");
  fill([-14, -1, -14], [14, -1, 16], "minecraft:grass_block");
  fill([-14, -4, -14], [14, -2, 16], "minecraft:dirt");
  fill([2, -3, -14], [5, -3, 16], "minecraft:gravel");
  fill([2, -2, -14], [5, -2, 16], "minecraft:water");
  fill([2, -1, -14], [5, -1, 16], "minecraft:air");
  fill([1, -2, -14], [1, -1, 16], "minecraft:mossy_cobblestone");
  fill([6, -2, -14], [6, -1, 16], "minecraft:cobblestone");
  fill([6, -1, -14], [6, -1, 16], "minecraft:grass_block");
  fill([-14, 0, 1], [1, 0, 2], "minecraft:polished_andesite");
  fill([6, 0, 1], [14, 0, 2], "minecraft:polished_andesite");
  for (const z of [0, 3]) {
    fill([-14, 0, z], [-3, 0, z], "minecraft:andesite_slab[type=bottom]");
    fill([6, 0, z], [14, 0, z], "minecraft:andesite_slab[type=bottom]");
  }
  // Lantern posts at the road's end on both banks, and a few shrubs.
  for (const [x, z] of [[-1, 4], [7, -1], [7, 4]]) {
    set([x, 0, z], "minecraft:spruce_fence");
    set([x, 1, z], "minecraft:lantern[hanging=false]");
  }
  for (const [x, z] of [[9, -6], [11, 7], [12, -3], [-6, -9], [-9, 11], [-12, -4]]) set([x, 0, z], "minecraft:azalea");
  for (const [x, z] of [[8, -9], [10, 10], [-7, 12], [-11, -10]]) set([x, 0, z], "minecraft:poppy");
  // The machine, in the open. The handle turns a gearbox; another below it sends the turn east along a shaft, and a
  // third turns it south into the back of the bearing at the foot of the bridge.
  set([-3, 1, -1], "create:copper_valve_handle[facing=west]");
  set([-2, 1, -1], "create:gearbox[axis=z]");
  set([-2, 0, -1], "create:gearbox[axis=z]");
  fill([-1, 0, -1], [0, 0, -1], "create:shaft[axis=x]");
  set([1, 0, -1], "create:gearbox[axis=y]");
  set([1, 0, 0], "create:mechanical_bearing[facing=south]");
  // The bridge, raised: spruce boards, the far end a dark oak beam.
  fill([1, 0, 1], [1, 3, 2], "minecraft:spruce_planks");
  fill([1, 4, 1], [1, 4, 2], "minecraft:stripped_dark_oak_log[axis=z]");
  return out;
}

export async function buildGate(server: MinecraftServer, o: Vec) {
  for (const command of gateCommands(o)) await tryCommand(server, command);
  return server.run(GATE_MACHINE, o);
}

// The bridge's boards are glued together, as a player glues them before the bearing first turns; the valve's use is
// set to a quarter turn, as a player sets it with a wrench, so that one use swings the bridge all the way.
export const GATE_MACHINE = defineTask<Vec, any>({
  name: "folkways.promo.assistant.gate-machine", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.world.phys.AABB",
      "com.simibubi.create.content.contraptions.glue.SuperGlueEntity",
      "com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var o = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var deck = new AABB(o.getX() + 1, o.getY(), o.getZ() + 1, o.getX() + 2, o.getY() + 5, o.getZ() + 3);
level.getEntitiesOfClass(SuperGlueEntity.class, deck.inflate(8)).forEach(old -> old.discard());
level.addFreshEntity(new SuperGlueEntity(level, deck));
var valve = (ValveHandleBlockEntity) level.getBlockEntity(o.offset(-3, 1, -1));
valve.angleInput.setValue(90);
return Map.of("angle", valve.angleInput.getValue());`,
  }),
});
