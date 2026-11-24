import { readFileSync } from "node:fs";
import { defineTask, javaTask, screen, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { tryCommand } from "../shared/harness/bw-helpers";
import type { Vec } from "../shared/colony/colony-founding";
import { openTomsTerminal, orderAtTomsTerminal } from "../shared/ui/toms-orders";
import type { Stage } from "./assistant-kit";
import { sleepMs } from "./promo-kit";

// A brick fan smelter on a meadow: a Create creative motor turns an encased fan through a clutch with a lever on top,
// the fan blows east through a lava basin onto a depot, and a brick backstop ends the hot stream. South-east of it the
// colony keeps its stock in a Tom's Simple Storage network: two chests behind an inventory connector and a storage
// terminal facing the meadow, the raw iron and the bread inside. A barrel at the end of the yard is the smelter's
// outbox: Tom's chests are reached through the network, so finished goods wait there for the order to fetch them.
// The lever stands on, so the clutch is let go and the fan still: the assistant finds out by trying it that pulling
// the lever back starts the fan.
//
// The fan stands at `o`; the stream runs east along z = 0.

export type Smelter = {
  motor: Vec; clutch: Vec; lever: Vec; fan: Vec; lava: Vec; depot: Vec;
  terminal: Vec; connector: Vec; chests: Vec[]; beds: Vec[]; outbox: Vec;
};

export function smelterLayout(o: Vec): Smelter {
  const at = (x: number, y: number, z: number): Vec => ({ x: o.x + x, y: o.y + y, z: o.z + z });
  return {
    motor: at(-2, 0, 0), clutch: at(-1, 0, 0), lever: at(-1, 1, 0), fan: at(0, 0, 0), lava: at(1, 0, 0), depot: at(2, 0, 0),
    terminal: at(7, 0, 10), connector: at(7, 0, 11), chests: [at(6, 0, 11), at(8, 0, 11)],
    beds: [at(-8, 0, 6), at(-6, 0, 6)], outbox: at(4, 0, 2),
  };
}

// The store holds more raw iron than the order asks for: a trial smelt during the conversation may take some.
export const RAW_IRON = 20;
// The ingots the player orders: more than one load of the depot, so the stream cooks several batches.
export const ORDER = 16;

export function smelterCommands(o: Vec): string[] {
  const r = (x: number, y: number, z: number) => `${o.x + x} ${o.y + y} ${o.z + z}`;
  const out: string[] = [];
  const fill = (a: number[], b: number[], block: string) => out.push(`fill ${r(a[0], a[1], a[2])} ${r(b[0], b[1], b[2])} ${block}`);
  const set = (a: number[], block: string) => out.push(`setblock ${r(a[0], a[1], a[2])} ${block}`);
  // A clean meadow, a paved yard round the machine and a path to the store.
  fill([-14, 0, -12], [14, 12, 16], "minecraft:air");
  fill([-14, -1, -12], [14, -1, 16], "minecraft:grass_block");
  fill([-14, -4, -12], [14, -2, 16], "minecraft:dirt");
  fill([-4, -1, -3], [4, -1, 3], "minecraft:polished_andesite");
  fill([4, -1, 8], [10, -1, 13], "minecraft:polished_andesite");
  fill([3, -1, 3], [4, -1, 8], "minecraft:dirt_path");
  fill([-9, -1, 3], [-2, -1, 4], "minecraft:dirt_path");
  // The drive: a creative motor, the clutch and its lever, then the fan facing east.
  set([-2, 0, 0], "create:creative_motor[facing=east]");
  set([-1, 0, 0], "create:clutch[axis=x]");
  set([-1, 1, 0], "minecraft:lever[face=floor,facing=north,powered=true]");
  set([0, 0, 0], "create:encased_fan[facing=east]");
  // The lava basin the stream blows through, the depot, and the backstop that ends the stream.
  fill([1, -1, -1], [1, -1, 1], "minecraft:bricks");
  set([1, 0, -1], "minecraft:bricks");
  set([1, 0, 1], "minecraft:bricks");
  set([1, 0, 0], "minecraft:lava");
  set([2, 0, 0], "create:depot");
  fill([3, 0, 0], [3, 2, 0], "minecraft:bricks");
  set([3, 3, 0], "minecraft:brick_wall");
  set([1, 1, -1], "minecraft:brick_slab[type=bottom]");
  set([1, 1, 1], "minecraft:brick_slab[type=bottom]");
  // A barrel at the end of the yard takes what the smelter puts out until it is carried to the store.
  set([4, 0, 2], "minecraft:barrel[facing=up]");
  // Tom's storage: a connector between two chests, the terminal on its meadow side.
  set([6, 0, 11], "minecraft:chest[facing=north]");
  set([8, 0, 11], "minecraft:chest[facing=north]");
  set([7, 0, 11], "toms_storage:inventory_connector");
  set([7, 0, 10], "toms_storage:storage_terminal[facing=south]");
  for (const x of [5, 9]) set([x, 0, 10], "minecraft:lantern[hanging=false]");
  // The camp: two beds west of the yard.
  for (const x of [-8, -6]) {
    set([x, 0, 6], "minecraft:white_bed[part=foot,facing=north]");
    set([x, 0, 5], "minecraft:white_bed[part=head,facing=north]");
  }
  for (const [x, z] of [[-11, -8], [10, -9], [13, 5], [-12, 10], [-3, 12]]) set([x, 0, z], "minecraft:azalea");
  for (const [x, z] of [[-9, -5], [11, 13], [11, -4]]) set([x, 0, z], "minecraft:poppy");
  return out;
}

export async function buildSmelter(server: MinecraftServer, o: Vec) {
  const layout = smelterLayout(o);
  for (const command of smelterCommands(o)) await tryCommand(server, command);
  // The motor takes its speed once its block entity has ticked.
  await running(server, 1000);
  const machine = await server.run(SMELTER_MACHINE, { ...o, raw: RAW_IRON });
  // The motor's sign is checked once with the clutch held: the stream must blow east, through the lava.
  await tryCommand(server, `setblock ${layout.lever.x} ${layout.lever.y} ${layout.lever.z} minecraft:lever[face=floor,facing=north,powered=false]`);
  await running(server, 2000);
  const first = await server.run(SMELTER_STATE, o);
  // Create reads speeds as 0 while ticks are frozen: the stream's processing at the depot tells which way it blows.
  if (first.processing !== "create:blasting") {
    await server.run(SMELTER_MACHINE, { ...o, raw: RAW_IRON, speed: -64 });
    await running(server, 2000);
  }
  const checked = await server.run(SMELTER_STATE, o);
  if (checked.processing !== "create:blasting") {
    throw new Error(`the fan must blow east through the lava: ${JSON.stringify({ machine, first, checked })}`);
  }
  await tryCommand(server, `setblock ${layout.lever.x} ${layout.lever.y} ${layout.lever.z} minecraft:lever[face=floor,facing=north,powered=true]`);
  // The storage network settles before its terminal is taken in.
  await running(server, 2500);
  return { machine, checked, stopped: await server.run(SMELTER_STATE, o) };
}

// Outside a clip the world stands still; the machine and the storage network settle with time running.
async function running(server: MinecraftServer, ms: number) {
  await tick.freeze(server, false);
  await sleepMs(ms);
  await tick.freeze(server, true);
}

const ZH: Record<string, string> = JSON.parse(readFileSync(
  "mods/vanilla/src/main/resources/assets/folkways_vanilla/lang/zh_cn.json", "utf8"));
/** The terminal's order button as the promo client (zh_cn) labels it. */
export const ORDER_LABEL = ZH["folkways.toms.orders.open"];

/** Places the ingot order from the craftable grid, holding each step long enough to read in the take. */
export async function placeSmeltingOrder(stage: Stage, order: { terminal: Vec; count: number; itemLabel: string;
  beforeConfirm: () => Promise<void> }) {
  const { server, client } = stage;
  await standAtTerminal(stage, order.terminal);
  await openTomsTerminal(client, server, order.terminal, ORDER_LABEL);
  await orderAtTomsTerminal(client, server, {
    amount: order.count,
    label: ORDER_LABEL,
    catalog: { toggle: ZH["folkways.toms.orders.craftables"], item: order.itemLabel },
    pause: sleepMs,
    beforeConfirm: order.beforeConfirm,
  });
}

/** The player, out of the picture, steps up to the terminal: a container screen opens only within reach. */
export async function standAtTerminal(stage: Stage, t: Vec) {
  await world.command(stage.server, `gamemode creative ${stage.player}`);
  // Empty-handed: the colony book from the box would open the colony's card for the terminal instead of the terminal.
  await world.command(stage.server, `item replace entity ${stage.player} weapon.mainhand with minecraft:air`);
  await world.command(stage.server, `tp ${stage.player} ${t.x + 0.5} ${t.y} ${t.z - 1.8} facing ${t.x + 0.5} ${t.y + 0.5} ${t.z + 0.5}`);
}

// The motor's speed, and the chests filled: raw iron for the order and bread for the residents.
const SMELTER_MACHINE = defineTask<Vec & { raw: number; speed?: number }, any>({
  name: "folkways.promo.assistant.smelter-machine", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.world.Container", "net.minecraft.world.item.ItemStack",
      "net.minecraft.world.item.Items", "com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var o = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var motor = (CreativeMotorBlockEntity) level.getBlockEntity(o.offset(-2, 0, 0));
motor.generatedSpeed.setValue(a.has("speed") ? (int) a.integer("speed") : 64);
var ore = (Container) level.getBlockEntity(o.offset(6, 0, 11));
ore.clearContent();
ore.setItem(0, new ItemStack(Items.RAW_IRON, (int) a.integer("raw")));
var pantry = (Container) level.getBlockEntity(o.offset(8, 0, 11));
pantry.clearContent();
pantry.setItem(0, new ItemStack(Items.BREAD, 32));
return Map.of("speed", motor.generatedSpeed.getValue(), "generated", motor.getGeneratedSpeed(), "time", level.getGameTime());`,
  }),
});

/**
 * The machine at a glance: the fan's speed and stream, the depot, the store and who carries what. Items on the
 * depot and in the residents' packs are named `id×count`.
 */
export const SMELTER_STATE = defineTask<Vec, {
  speed: number; direction: string; processing: string; lever: boolean; depot: string[];
  raw: number; ingots: number; ground: number;
  residents: Array<{ uuid: string; x: number; y: number; z: number; pack: string[] }>;
}>({
  name: "folkways.promo.assistant.smelter-state", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.world.Container",
      "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items", "net.minecraft.world.phys.AABB",
      "net.minecraft.world.entity.item.ItemEntity", "net.minecraft.world.level.block.LeverBlock",
      "net.neoforged.neoforge.capabilities.Capabilities",
      "com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity",
      "io.github.izakyl.folkways.core.facility.api.control.Readings",
      "io.github.izakyl.folkways.core.facility.api.control.Value", "io.github.izakyl.folkways.core.world.api.WorldPos",
      "io.github.izakyl.folkways.vanilla.person.ResidentEntity", "net.minecraft.resources.ResourceLocation"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var o = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var fan = (EncasedFanBlockEntity) level.getBlockEntity(o);
var flow = fan.getAirFlowDirection();
var depotPos = o.offset(2, 0, 0);
var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, depotPos, null);
var depot = new ArrayList<String>();
for (int slot = 0; handler != null && slot < handler.getSlots(); slot++) {
    var stack = handler.getStackInSlot(slot);
    if (!stack.isEmpty()) depot.add(BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount());
}
var processing = Readings.of(ResourceLocation.parse("folkways_core:create/air_processing"), WorldPos.of(level, depotPos))
    .flatMap(reading -> reading.read(level)).map(value -> value instanceof Value.Text text ? text.value() : String.valueOf(value))
    .orElse("?");
int raw = 0, ingots = 0;
for (var chest : List.of(o.offset(6, 0, 11), o.offset(8, 0, 11))) {
    var container = (Container) level.getBlockEntity(chest);
    raw += container.countItem(Items.RAW_IRON);
    ingots += container.countItem(Items.IRON_INGOT);
}
var area = new AABB(o).inflate(20);
int ground = level.getEntitiesOfClass(ItemEntity.class, area).size();
var residents = new ArrayList<Object>();
for (var resident : level.getEntitiesOfClass(ResidentEntity.class, area)) {
    var pack = new ArrayList<String>();
    for (ItemStack stack : resident.pack().contents()) {
        if (!stack.isEmpty()) pack.add(BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount());
    }
    residents.add(Map.of("uuid", resident.getStringUUID(), "x", resident.getX(), "y", resident.getY(), "z", resident.getZ(),
        "pack", pack));
}
var drive = new ArrayList<String>();
for (int dx = -2; dx <= 0; dx++) {
    var cell = o.offset(dx, 0, 0);
    var be = level.getBlockEntity(cell);
    drive.add(level.getBlockState(cell) + (be instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity k
    ? " speed=" + k.getSpeed() + " generated=" + k.getGeneratedSpeed() + " stressed=" + k.isOverStressed()
        + " source=" + k.hasSource() + " network=" + k.network : " no kinetic"));
}
var out = new LinkedHashMap<String, Object>();
out.put("drive", drive);
out.put("time", level.getGameTime());
out.put("frozen", ctx.server().tickRateManager().isFrozen());
out.putAll(Map.of("speed", (double) fan.getSpeed(), "direction", flow == null ? "none" : flow.getSerializedName(),
    "processing", processing, "lever", level.getBlockState(o.offset(-1, 1, 0)).getValue(LeverBlock.POWERED),
    "depot", depot, "raw", raw, "ingots", ingots, "ground", ground, "residents", residents));
return out;`,
  }),
});
