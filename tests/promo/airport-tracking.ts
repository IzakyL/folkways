import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

/** Read-only diagnostics for chunk delivery and the normal server entity pairing. */
export const AIRPORT_TRACKING = defineTask<{ entity: string; player: string }, any>({
  name: "folkways.airport.entity-tracking", side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "net.minecraft.server.level.ChunkMap", "java.util.UUID"],
    body: `
var a = Args.of(ctx);
var server = (MinecraftServer) ctx.server();
var player = server.getPlayerList().getPlayerByName(a.string("player"));
var level = player.serverLevel();
var entity = level.getEntity(UUID.fromString(a.string("entity")));
if (entity == null) {
    throw new IllegalStateException("No server entity " + a.string("entity"));
}
var map = level.getChunkSource().chunkMap;
var entitiesField = ChunkMap.class.getDeclaredField("entityMap");
entitiesField.setAccessible(true);
var tracked = ((java.util.Map<?, ?>) entitiesField.get(map)).get(entity.getId());
if (tracked == null) {
    throw new IllegalStateException("No server tracker " + entity.getUUID());
}
var seenField = tracked.getClass().getDeclaredField("seenBy");
seenField.setAccessible(true);
var seen = (java.util.Set<?>) seenField.get(tracked);
var chunk = entity.chunkPosition();
boolean inView = player.getChunkTrackingView().contains(chunk.x, chunk.z);
boolean pending = player.connection.chunkSender.isPending(chunk.toLong());
return Map.of("ready", inView && !pending, "pending", pending, "inView", inView,
    "paired", seen.contains(player.connection),
    "entityChunk", chunk.toString(), "playerChunk", player.chunkPosition().toString(),
    "frozen", server.tickRateManager().isFrozen(), "tick", server.getTickCount());`,
  }),
});
