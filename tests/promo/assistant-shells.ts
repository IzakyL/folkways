import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { tryCommand } from "../shared/harness/bw-helpers";
import type { Vec } from "../shared/colony/colony-founding";

// Shared by the cannon walker take: stocks a powder store and watches the shells it fires.

/** Fills the powder store with shot and charges. */
export async function stockBattery(server: MinecraftServer, store: Vec, rounds = 6) {
  await tryCommand(server, `data merge block ${store.x} ${store.y} ${store.z} {Items:[`
    + `{Slot:0b,id:"createbigcannons:solid_shot",count:${rounds}},{Slot:1b,id:"createbigcannons:powder_charge",count:${rounds * 2}}]}`);
}

/**
 * Counts the shells the world sees fired from now on and where the last one ended; installed once per take. A shell is
 * in the air for about a second, too short to catch by polling for it.
 */
export const SHOT_WATCH = defineTask<{ action: "install" | "read" }, any>({
  name: "folkways.promo.assistant.shot-watch", side: "server",
  source: javaTask({
    imports: ["net.neoforged.neoforge.common.NeoForge", "net.neoforged.neoforge.event.entity.EntityJoinLevelEvent",
      "net.neoforged.neoforge.event.tick.ServerTickEvent",
      "rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile"],
    body: `
var key = "folkways.promo.shots";
if (Args.of(ctx).string("action").equals("install")) {
    var shots = new java.util.concurrent.ConcurrentHashMap<String, Object>();
    shots.put("count", 0);
    System.getProperties().put(key, shots);
    NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent event) -> {
        if (event.getEntity() instanceof AbstractBigCannonProjectile shell && !event.getLevel().isClientSide()
                && System.getProperties().get(key) == shots) {
            shots.put("count", (Integer) shots.get("count") + 1);
            shots.put("flying", shell);
        }
    });
    NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
        if (shots.get("flying") instanceof AbstractBigCannonProjectile shell) {
            shots.put("last", java.util.List.of(shell.getX(), shell.getY(), shell.getZ()));
            if (!shell.isAlive() || shell.getDeltaMovement().lengthSqr() < 1.0e-4) shots.remove("flying");
        }
    });
    return Map.of("installed", true);
}
var shots = (Map<?, ?>) System.getProperties().get(key);
return shots == null ? Map.of("count", 0) : Map.of("count", shots.get("count"), "flying", shots.containsKey("flying"),
    "last", shots.containsKey("last") ? shots.get("last") : List.of());`,
  }),
});
