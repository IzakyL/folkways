import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

export const dockObserve = (side: "server" | "client") => defineTask<Record<string, any>, any>({
  name: `folkways.airport.dock-observe.${side}`, side,
  source: javaTask({
    imports: ["net.neoforged.neoforge.common.NeoForge",
      "net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent",
      "net.neoforged.neoforge.event.entity.player.PlayerEvent",
      "net.minecraft.world.entity.Entity", "dev.ryanhcode.sable.Sable"],
    body: `
if (Args.of(ctx).string("action").equals("stop")) {
    var saved = (Map<?, ?>) System.getProperties().remove("folkways.airport.dock-observer");
    NeoForge.EVENT_BUS.unregister(saved.get("observer"));
    return saved.get("rows");
}
rows.clear();
ids = Args.of(ctx).strings("ids");
observer = new Observer();
NeoForge.EVENT_BUS.register(observer);
// Task classes can be recreated between runs. Keep the registered instance and its rows in the JVM.
System.getProperties().put("folkways.airport.dock-observer", Map.of("observer", observer, "rows", rows));
return true;`,
    members: `
private static List<String> ids;
private static final List<Object> rows = new ArrayList<>();
private static Observer observer;

private static void record(String kind, Entity e) {
    if (!ids.contains(e.getUUID().toString())) {
        return;
    }
    var r = new LinkedHashMap<String, Object>();
    r.put("kind", kind); r.put("id", e.getUUID().toString()); r.put("time", e.level().getGameTime());
    r.put("pos", e.position().toString()); r.put("block", e.blockPosition().toString());
    r.put("chunk", e.chunkPosition().toString()); r.put("tick", e.tickCount);
    r.put("reason", String.valueOf(e.getRemovalReason()));
    var frame = Sable.HELPER.getTrackingSubLevel(e);
    r.put("tracking", frame == null ? "none" : frame.getUniqueId().toString());
    rows.add(r);
    if (rows.size() > 60) {
        rows.removeFirst();
    }
}

public static class Observer {
    @net.neoforged.bus.api.SubscribeEvent
    public void leave(EntityLeaveLevelEvent event) {
        record("leave", event.getEntity());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void start(PlayerEvent.StartTracking event) {
        record("start", event.getTarget());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void stop(PlayerEvent.StopTracking event) {
        record("stop", event.getTarget());
    }
}`,
  }),
});
