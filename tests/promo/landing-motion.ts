import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

export const landingMotion = (side: "server" | "client") => defineTask<Record<string, any>, any>({
  name: `folkways.landing.motion.${side}`, side, limits: { maxNodes: 2_000_000 },
  source: javaTask({
    imports: ["com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity",
      "io.github.izakyl.folkways.core.world.api.WorldSpaces", "net.minecraft.world.phys.Vec3",
      "net.neoforged.neoforge.common.NeoForge", "net.neoforged.neoforge.event.tick.EntityTickEvent"],
    body: `
if (Args.of(ctx).string("action").equals("stop")) {
    NeoForge.EVENT_BUS.unregister(observer);
    return new ArrayList<>(rows);
}
rows.clear();
var id = UUID.fromString(Args.of(ctx).string("id"));
var args = Args.of(ctx);
var frameId = args.has("frame") ? UUID.fromString(args.string("frame")) : null;
var origin = args.has("origin") ? args.object("origin") : null;
observer = event -> {
    if (!(event.getEntity() instanceof VehicleEntity v) || !v.getUUID().equals(id)) {
        return;
    }
    var r = new LinkedHashMap<String, Object>();
    r.put("tick", v.tickCount); r.put("time", v.level().getGameTime());
    r.put("x", v.getX()); r.put("y", v.getY()); r.put("z", v.getZ());
    r.put("pitch", v.getXRot()); r.put("roll", v.getRoll());
    r.put("vx", v.getDeltaMovement().x); r.put("vy", v.getDeltaMovement().y);
    r.put("vz", v.getDeltaMovement().z); r.put("ground", v.onGround());
    r.put("aabb", v.verticalCollisionBelow); r.put("obb", v.checkObbOnGroundCached());
    r.put("bottom", v.getBoundingBox().minY);
    r.put("speed", v.getDeltaMovement().length());
    if (frameId != null) {
        var frame = WorldSpaces.frame(v.level(), frameId).orElseThrow();
        var local = frame.toLocal(v.position()).add(Vec3.atLowerCornerOf(frame.storageOrigin()))
            .subtract(origin.number("x"), origin.number("y") + 1, origin.number("z"));
        r.put("deck", Map.of("x", local.x, "y", local.y, "z", local.z));
    }
    if (!v.level().isClientSide()) {
        try {
            var type = Class.forName("io.github.izakyl.folkways.firearms.warfare.VehicleController");
            var active = type.getDeclaredField("ACTIVE"); active.setAccessible(true);
            var c = ((Map<?, ?>) active.get(null)).get(v);
            for (String name : List.of("approachAligned", "touchdown", "groundContactTicks", "action")) {
                var f = type.getDeclaredField(name); f.setAccessible(true);
                r.put(name, c == null ? "absent" : f.get(c));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
    rows.add(r);
};
NeoForge.EVENT_BUS.addListener(EntityTickEvent.Post.class, observer);
return List.of();`,
    members: `
private static final List<Object> rows = new ArrayList<>();
private static java.util.function.Consumer<EntityTickEvent.Post> observer;`,
  }),
});
