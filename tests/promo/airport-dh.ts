import { defineTask, javaTask, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { sleepMs } from "./promo-kit";

// This probe deliberately targets the pinned DH 3.3.3 internals: a changed API fails the take instead of
// silently replacing the generation barrier with a guessed delay. An empty network queue alone is insufficient;
// the quadtree may still be discovering missing positions or waiting to upload the returned terrain.
const DH_STATUS = defineTask<Record<string, never>, any>({
  name: "folkways.promo.airport.dh-status", side: "client",
  source: javaTask({ imports: ["com.mojang.blaze3d.systems.RenderSystem"], body: `
var shared = Class.forName("com.seibel.distanthorizons.core.api.internal.SharedApi");
var world = shared.getMethod("getAbstractDhWorld").invoke(null);
if (world == null) { return Map.of("ready", false); }
var levels = (Iterable<?>) world.getClass().getMethod("getAllLoadedLevels").invoke(world);
var rows = new ArrayList<Object>();
boolean ready = true;
for (var level : levels) {
    var provider = level.getClass().getMethod("getFullDataProvider").invoke(level);
    var queueRef = (java.util.concurrent.atomic.AtomicReference<?>) provider.getClass()
        .getField("worldGenQueueRef").get(provider);
    var queue = queueRef.get();
    if (queue == null) { ready = false; continue; }
    long waiting = ((Number) queue.getClass().getMethod("getWaitingTaskCount").invoke(queue)).longValue();
    long working = ((Number) queue.getClass().getMethod("getInProgressTaskCount").invoke(queue)).longValue();
    long remaining = ((Number) queue.getClass().getMethod("getRetrievalEstimatedRemainingChunkCount")
        .invoke(queue)).longValue();
    var module = level.getClass().getField("clientside").get(level);
    var stateRef = (java.util.concurrent.atomic.AtomicReference<?>) module.getClass()
        .getField("ClientRenderStateRef").get(module);
    var state = stateRef.get();
    if (state == null) { ready = false; continue; }
    var tree = state.getClass().getField("quadtree").get(state);
    var lockField = tree.getClass().getDeclaredField("treeTickLock");
    lockField.setAccessible(true);
    var lock = (java.util.concurrent.locks.ReentrantLock) lockField.get(tree);
    int missing;
    int queued;
    lock.lock();
    try {
        var missingField = tree.getClass().getDeclaredField("missingGenerationPosSet");
        missingField.setAccessible(true);
        missing = ((Set<?>) missingField.get(tree)).size();
        var queuedField = tree.getClass().getDeclaredField("queuedGenerationPosSet");
        queuedField.setAccessible(true);
        queued = ((Set<?>) queuedField.get(tree)).size();
    } finally {
        lock.unlock();
    }
    var sections = new ArrayList<>();
    tree.getClass().getMethod("populateListWithEnabledRenderSections", ArrayList.class).invoke(tree, sections);
    int pending = 0;
    for (var section : sections) {
        if (!(Boolean) section.getClass().getMethod("gpuUploadComplete").invoke(section)) { pending++; }
    }
    int diameter = tree.getClass().getField("blockRenderDistanceDiameter").getInt(tree);
    ready &= diameter >= 2560 && waiting == 0 && working == 0 && remaining == 0 && missing == 0
        && queued == 0 && !sections.isEmpty() && pending == 0;
    rows.add(Map.of("diameter", diameter, "waiting", waiting, "working", working, "remaining", remaining,
        "missing", missing, "queued", queued, "sections", sections.size(), "pendingUploads", pending));
}
return Map.of("ready", ready && !rows.isEmpty(), "levels", rows,
    "fogStart", RenderSystem.getShaderFogStart(), "fogEnd", RenderSystem.getShaderFogEnd());` }),
});

export async function waitForAirportLods(client: MinecraftClient) {
  const start = Date.now();
  let settledSince = 0;
  let status: any;
  let lastReport = 0;
  while (Date.now() - start < 300_000) {
    status = await client.run(DH_STATUS, {});
    if (Date.now() - lastReport >= 30_000) {
      console.log(`Airport DH: ${JSON.stringify(status)}`);
      lastReport = Date.now();
    }
    if (status.ready) {
      settledSince ||= Date.now();
      // Several discovery ticks must agree, including network retrieval and GPU uploads.
      if (Date.now() - settledSince >= 10_000) {
        return { ...status, waitedMs: Date.now() - start };
      }
    } else {
      settledSince = 0;
    }
    await sleepMs(2_000);
  }
  throw new Error(`DH terrain did not settle before filming: ${JSON.stringify(status)}`);
}
