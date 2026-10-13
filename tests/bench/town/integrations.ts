import type { MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../../shared/colony/colony-tasks";
import { GOLEM_CAST, GOLEM_WORK } from "../../shared/colony/integration-tasks";
import type { Vec } from "./fixture";

/** Casts the colony's golems where the town plan stands them; `watched` is a chest their work report reads the stock of. */
export async function setupIntegrations(server: MinecraftServer, player: string, colony_id: string, origin: Vec, watched: Vec,
  golemSpawns: Array<{ kind: string; position: Vec }>) {
  const golems = await runColonyTask(server, GOLEM_CAST, {
    player, colony_id, origin, freeze_humans: false, seed_mending: true,
    kinds: golemSpawns.map(g => g.kind), positions: golemSpawns.map(g => g.position),
  });
  return { golems, watched,
    note: "Three golem variants are included in the total population and share the full colony workload." };
}

export async function sampleIntegrations(server: MinecraftServer, setup: any) {
  return { golem: await runColonyTask(server, GOLEM_WORK, { uuids: setup.golems.uuids, target: setup.watched }) };
}
