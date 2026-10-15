import { player, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { setBookGesture } from "../colony/colony-founding";
import { commandPos, frames } from "../harness/bw-helpers";
import type { Stage, Vec } from "../harness/stage";

// The native empty rack occupies only y=9..14/16 and the rear 2/16 of its block.
// Aiming at a block-face centre passes through air; aim at the actual visible bar instead.
export async function markSouthRack(server: MinecraftServer, client: MinecraftClient, name: string, rack: Vec, stage: Stage) {
  await setBookGesture(server, client, name, "point");
  const from = stage.stand({ x: rack.x + 0.5, y: rack.y - 1, z: rack.z + 1.75 });
  const target = stage.world({ x: rack.x + 0.5, y: rack.y + 0.72, z: rack.z + 0.0625 });
  const dx = target.x - from.x, dz = target.z - from.z, dy = target.y - (from.y + 1.62);
  const yaw = Math.atan2(-dx, dz) * 180 / Math.PI;
  const pitch = -Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI;
  await world.command(server, "tp " + name + " " + from.x + " " + from.y + " " + from.z + " " + yaw + " " + pitch);
  await tick.sprint(server, 5); await frames(client, 4);
  const click = await player.click(client, { button: "left", expect: { pos: stage.at(rack) } });
  await tick.sprint(server, 5);
  if (!await world.probe(server, "if data block " + commandPos(stage.at(rack)) + " \"neoforge:attachments\".\"folkways:colony_member\"")) {
    throw new Error("the real book click hit the rack but did not register it: " + JSON.stringify(click));
  }
  return click;
}
