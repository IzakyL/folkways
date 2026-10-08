import { world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask, MARK_MEMBERS } from "../e2e/colony-tasks";
import { GOLEM_CAST, GOLEM_WORK, WIRE_NETWORK, NETWORK_STATE } from "../shared/integration-tasks";
import { setMaintainDemandsFast } from "../promo/fast-setup";
import type { Vec } from "./fixture";
import { commandPos } from "../shared/bw-helpers";

export async function setupIntegrations(server: MinecraftServer, player: string, colony_id: string, origin: Vec, golemSpawns: Array<{ kind: string; position: Vec }>) {
  const at = (x: number, z: number, dy = 0) => ({ x: origin.x + x, y: origin.y + dy, z: origin.z + z });
  const source = at(-8, -2), packager = at(-8, -1), link = at(-7, -1), send = at(-8, -1, 1);
  const receive = at(5, 0), ticker = at(2, -3), target = at(9, 2), bench = at(7, 2);
  const chains = [at(-8, -1, 5), at(5, -1, 5)];
  for (const [p, block] of [
    [source, "minecraft:chest[facing=north]"], [packager, "create:packager[facing=south]"],
    [link, "create:stock_link[face=wall,facing=east]"], [send, "create:package_frogport"],
    [receive, "create:package_frogport"], [ticker, "create:stock_ticker[facing=south]"],
    [at(2, -2), "create:orange_seat"], [target, "minecraft:chest[facing=south]"],
    [bench, "minecraft:crafting_table"], ...chains.map(p => [p, "create:chain_conveyor"]),
    [at(-8, -1, 4), "create:creative_motor[facing=up]"],
  ] as Array<[Vec, string]>) await world.command(server, `setblock ${commandPos(p)} ${block}`);
  for (let slot = 0; slot < 8; slot++) await world.command(server,
    `item replace block ${commandPos(source)} container.${slot} with minecraft:spruce_log 64`);
  const members = await runColonyTask(server, MARK_MEMBERS, {
    player, colony_id, cells: [target, bench].flatMap(p => [p.x, p.y, p.z]),
  });
  const args = { player, colony_id, source, packager, link, send, receive, ticker, target, chains,
    input_item: "minecraft:spruce_log", output_item: "minecraft:spruce_planks", assign_crew: false };
  const network = await runColonyTask(server, WIRE_NETWORK, args);
  const golems = await runColonyTask(server, GOLEM_CAST, {
    player, colony_id, origin: at(-80, -66), freeze_humans: false, seed_mending: true,
    kinds: golemSpawns.map(g => g.kind), positions: golemSpawns.map(g => g.position),
  });
  const before = await runColonyTask(server, NETWORK_STATE, args);
  const demand = await setMaintainDemandsFast(server, player, [{ at: target, item: "minecraft:spruce_planks", count: 256 }]);
  return { args, network, members, golems, before, demand, containers: [source, target],
    note: "External spruce supply through Create; three golem variants are included in the total population and share the full colony workload." };
}

export async function sampleIntegrations(server: MinecraftServer, setup: any) {
  return {
    dispatch: await runColonyTask(server, NETWORK_STATE, setup.args),
    golem: await runColonyTask(server, GOLEM_WORK, { uuids: setup.golems.uuids, target: setup.args.target }),
  };
}
