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
  await world.command(server, "tp " + name + " " + from.x + " " + from.y + " " + from.z);
  await tick.sprint(server, 5); await frames(client, 4);
  // The ship's deck lift settles before the click. Aim from the actual eye, not the teleport's initial height.
  const settled = await player.state(client);
  if (!settled?.position) throw new Error("cannot aim at the rack without the player's actual position");
  const dx = target.x - settled.position.x, dz = target.z - settled.position.z;
  const dy = target.y - (settled.position.y + settled.eyeHeight);
  const yaw = Math.atan2(-dx, dz) * 180 / Math.PI;
  const pitch = -Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI;
  await player.place(client, { yaw, pitch }); await frames(client, 3);
  const click = await player.click(client, { button: "left", expect: { pos: stage.at(rack) } });
  await tick.sprint(server, 5);
  if (!await world.probe(server, "if data block " + commandPos(stage.at(rack)) + " \"neoforge:attachments\".\"folkways:colony_member\"")) {
    throw new Error("the real book click hit the rack but did not register it: " + JSON.stringify(click));
  }
  return click;
}
