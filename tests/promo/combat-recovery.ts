import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

const IMPORTS = [
  "java.util.Random",
  "io.github.izakyl.folkways.core.colony.api.Colonies",
  "io.github.izakyl.folkways.front.engine.colony.ZoneDrafts",
  "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
  "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
  "io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent",
  "io.github.izakyl.folkways.core.resident.api.Bodies",
  "net.minecraft.core.BlockPos",
  "net.minecraft.world.level.block.Blocks",
  "net.minecraft.world.item.ItemStack",
  "net.minecraft.world.item.Items",
  "net.minecraft.world.level.material.Fluids",
  "com.mikoalopex.createfirefightingadd.CreateFireFightingAdd",
  "com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem",
  "com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItemHandler",
  "com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity",
  "net.neoforged.neoforge.fluids.FluidStack",
  "net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction",
];

export const DAMAGE_PLANS = defineTask<Record<string, any>, any>({
  name: "folkways.promo.combat-fire-plans",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
var presence = colony.service(DamageControlContent.ID, Object.class).orElseThrow();
var field = presence.getClass().getDeclaredField("responding");
field.setAccessible(true);
var plans = (Map<?, ?>) field.get(presence);
return Map.of("planned", plans.size(), "time", level.getGameTime(), "plans", plans.toString(),
    "baselines", colony.kept(DamageControlContent.ID).getList("baselines", 10).size());
`,
  }),
});

export const DAMAGE_KITS = defineTask<Record<string, any>, any>({
  name: "folkways.promo.recovery-kits",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var o = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var cabinetPos = o.offset(9, 0, 28);
level.setBlockAndUpdate(cabinetPos, CreateFireFightingAdd.FIRE_HYDRANT_CABINET.get().defaultBlockState());
var cabinet = (FireHydrantCabinetBlockEntity) level.getBlockEntity(cabinetPos);
cabinet.getItemStackHandler().setStackInSlot(FireHydrantCabinetBlockEntity.SLOT_HOSE,
    new ItemStack(CreateFireFightingAdd.FIRE_HOSE_ITEM.get()));
cabinet.getFluidHandler(null).fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
var barrel = (net.minecraft.world.Container) level.getBlockEntity(o.offset(-12, 0, 8));
barrel.setItem(0, new ItemStack(CreateFireFightingAdd.CONE_NOZZLE_ITEM.get()));
var bottle = new ItemStack(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get());
new FireExtinguisherItemHandler(CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, bottle)
    .setStoredFluid(new FluidStack(Fluids.WATER, FireExtinguisherItem.CAPACITY));
int index = 0;
for (var raw : a.list("residents")) {
    var body = Bodies.of(level.getEntity(UUID.fromString(raw.toString()))).orElseThrow();
    for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
        body.pack().setItem(slot, ItemStack.EMPTY);
    }
    if (index++ == 0) {
        body.pack().insert(bottle);
    }
    for (int i = 0; i < 4; i++) {
        body.pack().insert(new ItemStack(Items.STONE_BRICKS, 64));
    }
    body.pack().insert(new ItemStack(Items.OAK_PLANKS, 64));
    body.pack().insert(new ItemStack(Items.OAK_PLANKS, 64));
    body.pack().insert(new ItemStack(Items.COOKED_BEEF, 16));
    var trade = body.licences().trade(DamageControlContent.trade()).orElseThrow();
    body.licences().setKeenness(trade, io.github.izakyl.folkways.core.vocation.api.Keenness.FIRST);
}
return Map.of("extinguisherWater", FireExtinguisherItem.CAPACITY, "hydrantWater", cabinet.getFluid().getAmount(),
    "nozzle", "Finite cone nozzle stored in barrel until the hose phase; no forced session or spray",
    "materials", 768);`,
  }),
});

export const DAMAGE_BASELINE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.recovery-baseline",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var o = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var low = a.has("bounds") ? a.list("bounds").subList(0, 3) : List.of(-15, -3, -11);
var high = a.has("bounds") ? a.list("bounds").subList(3, 6) : List.of(15, 8, 10);
var from = o.offset(((Number) low.get(0)).intValue(), ((Number) low.get(1)).intValue(),
    ((Number) low.get(2)).intValue());
var to = o.offset(((Number) high.get(0)).intValue(), ((Number) high.get(1)).intValue(),
    ((Number) high.get(2)).intValue());
var settings = ColonySettings.empty().with("extinguish", new ColonySettings.Value.Flag(true))
    .with("repair", new ColonySettings.Value.Flag(a.flag("repair", false)))
    .with("refill", new ColonySettings.Value.Flag(false));
var zone = ZoneDrafts.zone(level, colony, DamageControlContent.ID,
    from, to, settings).orElseThrow();
var baseline = new ArrayList<Object>();
for (var at : BlockPos.betweenClosed(from, to)) {
    var state = level.getBlockState(at);
    if (!state.isAir() && !state.hasBlockEntity()) {
        baseline.add(Map.of("x", at.getX(), "y", at.getY(), "z", at.getZ(), "state", state.toString()));
    }
}
return Map.of("zone", zone.id().toString(), "baseline", baseline);`,
  }),
});

export const DAMAGE_STAGE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.recovery-aftermath",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var o = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var removed = new ArrayList<String>();
var blast = new LinkedHashSet<BlockPos>();
var random = new Random(10755);
var center = new net.minecraft.world.phys.Vec3(o.getX() + 2.35, o.getY() - .5, o.getZ() - 7.5);
// Like vanilla's explosion boundary rays, varying ray energy makes a rough spherical breach.
for (int ix = 0; ix < 16; ix++) {
    for (int iy = 0; iy < 16; iy++) {
        for (int iz = 0; iz < 16; iz++) {
            if (ix != 0 && ix != 15 && iy != 0 && iy != 15 && iz != 0 && iz != 15) {
                continue;
            }
            var direction = new net.minecraft.world.phys.Vec3(ix / 15.0 * 2 - 1,
                iy / 15.0 * 2 - 1, iz / 15.0 * 2 - 1).normalize();
            var point = center;
            for (double energy = 2.75 * (.7 + random.nextDouble() * .6); energy > 0; energy -= .225) {
                var at = BlockPos.containing(point);
                if (at.getY() >= o.getY() - 1 && !level.getBlockState(at).hasBlockEntity()) {
                    blast.add(at.immutable());
                }
                point = point.add(direction.scale(.3));
            }
        }
    }
}
for (var at : blast) {
    if (!level.getBlockState(at).isAir()) {
        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
        removed.add(at.toShortString());
    }
}
var fireCells = new ArrayList<BlockPos>();
for (int x = -12; x <= 13; x++) {
    for (int z = -7; z <= 6; z++) {
        double edge = .10 * Math.sin(x * .71 + z * .37) + .13 * Math.sin(x * .29 - z * .83);
        double core = Math.pow((x - 3.5) / 7.8, 2) + Math.pow((z + 3.6) / 4.8, 2);
        double interior = Math.pow((x - 10) / 3.7, 2) + Math.pow((z + 4) / 3.5, 2);
        double embers = Math.pow((x + 8.8) / 2.5, 2) + Math.pow((z - 3.8) / 1.8, 2);
        if (Math.min(core, interior) > 1 + edge && embers > 1 + edge * 1.6) {
            continue;
        }
        var at = o.offset(x, 0, z);
        // Fires occupy intact ground/wood inside the workshop, never the recessed crater floor.
        if (level.getBlockState(at).isAir() && level.getBlockState(at.below()).isSolid()) {
            level.setBlockAndUpdate(at, Blocks.FIRE.defaultBlockState());
            fireCells.add(at.immutable());
        }
    }
}
int i = 0;
for (var raw : a.list("residents")) {
    var body = Bodies.of(level.getEntity(UUID.fromString(raw.toString()))).orElseThrow();
    boolean first = i++ == 0;
    body.mob().moveTo(o.getX() + (first ? -9.5 : 9.5), o.getY(),
        o.getZ() + (first ? 7.5 : 29.5), 180, 0);
    body.mob().getNavigation().stop();
}
var meter = level.getEntity(UUID.fromString(a.list("residents").getFirst().toString()));
var cabinet = (FireHydrantCabinetBlockEntity) level.getBlockEntity(o.offset(9, 0, 28));
int[] previous = {fireCells.size(), FireExtinguisherItem.CAPACITY, cabinet.getFluid().getAmount()};
var priorFires = new LinkedHashSet<BlockPos>(fireCells);
var hoseActor = level.getEntity(UUID.fromString(a.list("residents").get(1).toString()));
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
    net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, event -> {
        if (meter.isRemoved()) {
            return;
        }
        int burning = (int) fireCells.stream().filter(at -> level.getBlockState(at).is(Blocks.FIRE)).count();
        int water = 0;
        for (var raw : a.list("residents")) {
            for (var stack : Bodies.of(level.getEntity(UUID.fromString(raw.toString()))).orElseThrow()
                .pack().contents()) {
                if (stack.getItem() instanceof FireExtinguisherItem) {
                    water += FireExtinguisherItem.getFluid(stack).getAmount();
                }
            }
        }
        var barrel = (net.minecraft.world.Container) level.getBlockEntity(o.offset(-12, 0, 8));
        for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
            var stack = barrel.getItem(slot);
            if (stack.getItem() instanceof FireExtinguisherItem) {
                water += FireExtinguisherItem.getFluid(stack).getAmount();
            }
        }
        int hydrant = cabinet.getFluid().getAmount();
        var data = meter.getPersistentData();
        if (water < previous[1] && hydrant < previous[2]) {
            data.putInt("sharedFires", data.getInt("sharedFires") + previous[0] - burning);
        } else if (water < previous[1]) {
            data.putInt("extinguisherFires", data.getInt("extinguisherFires") + previous[0] - burning);
        } else if (hydrant < previous[2]) {
            data.putInt("hoseFires", data.getInt("hoseFires") + previous[0] - burning);
            var removedFires = priorFires.stream().filter(at -> !level.getBlockState(at).is(Blocks.FIRE)).toList();
            var eye = hoseActor.getEyePosition();
            var pulse = new net.minecraft.nbt.CompoundTag();
            pulse.putLong("tick", level.getGameTime());
            pulse.putDouble("x", eye.x);
            pulse.putDouble("y", eye.y);
            pulse.putDouble("z", eye.z);
            pulse.putDouble("nearestFireDistance", priorFires.stream()
                .mapToDouble(at -> eye.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(at))).min().orElse(0));
            pulse.putDouble("minExtinguishedDistance", removedFires.stream()
                .mapToDouble(at -> eye.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(at))).min().orElse(0));
            pulse.putDouble("maxExtinguishedDistance", removedFires.stream()
                .mapToDouble(at -> eye.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(at))).max().orElse(0));
            pulse.putDouble("hydrantDistance", eye.distanceTo(
                net.minecraft.world.phys.Vec3.atCenterOf(cabinet.getBlockPos())));
            pulse.putInt("waterUsed", previous[2] - hydrant);
            pulse.putInt("fires", removedFires.size());
            var pulses = data.getList("hosePulses", net.minecraft.nbt.Tag.TAG_COMPOUND);
            pulses.add(pulse);
            data.put("hosePulses", pulses);
        }
        priorFires.removeIf(at -> !level.getBlockState(at).is(Blocks.FIRE));
        previous[0] = burning;
        previous[1] = water;
        previous[2] = hydrant;
    });
return Map.of("removed", removed, "fires", fireCells.stream().map(BlockPos::toShortString).toList(),
    "method", "Seeded vanilla-style boundary rays breach the wall and only the top floor course",
    "fireShape", "Joined irregular lobes on intact crater rim and wooden interior, with scattered foreground embers",
    "phaseSupply", "Transfer the stored nozzle into the finite cabinet for phase two; native AI picks it up");`,
  }),
});

export const DAMAGE_PHASE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.recovery-phase",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var o = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
String phase = a.string("phase");
int index = 0;
for (var raw : a.list("residents")) {
    var body = Bodies.of(level.getEntity(UUID.fromString(raw.toString()))).orElseThrow();
    boolean respond = phase.equals("repair") || (phase.equals("extinguisher") ? index == 0 : index == 1);
    index++;
    for (var vocation : io.github.izakyl.folkways.core.vocation.api.Vocations.all()) {
        body.licences().trade(vocation).ifPresent(trade -> body.licences().allow(trade,
            respond && (vocation.id().equals(DamageControlContent.ID)
                || phase.equals("repair") && vocation.id().getPath().equals("building"))));
    }
}
if (phase.equals("hose")) {
    var barrel = (net.minecraft.world.Container) level.getBlockEntity(o.offset(-12, 0, 8));
    var cabinet = (FireHydrantCabinetBlockEntity) level.getBlockEntity(o.offset(9, 0, 28));
    cabinet.getItemStackHandler().setStackInSlot(FireHydrantCabinetBlockEntity.SLOT_NOZZLE,
        barrel.removeItem(0, 1));
    FireHydrantCabinetBlockEntity.tick(level, cabinet.getBlockPos(), cabinet.getBlockState(), cabinet);
}
if (phase.equals("repair") || phase.equals("extinguisher")) {
    var front = ColonyFront.of(colony);
    var zone = front.zonesIn(level).stream().filter(z -> z.id().toString().equals(a.string("zone")))
        .findFirst().orElseThrow();
    front.setDrawn(zone.id(), zone.settings()
        .with("extinguish", new ColonySettings.Value.Flag(true))
        .with("repair", new ColonySettings.Value.Flag(phase.equals("repair")))
        .with("refill", new ColonySettings.Value.Flag(true)));
}
return Map.of("phase", phase, "tick", level.getGameTime());`,
  }),
});

export const DAMAGE_STATE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.recovery-evidence",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var o = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
int missing = 0, fires = 0, water = 0, materials = 0;
var missingPositions = new ArrayList<String>();
var firePositions = new ArrayList<String>();
for (var raw : a.list("baseline")) {
    var row = (Map<?, ?>) raw;
    var at = new BlockPos(((Number) row.get("x")).intValue(),
        ((Number) row.get("y")).intValue(), ((Number) row.get("z")).intValue());
    if (!level.getBlockState(at).toString().equals(row.get("state"))) {
        missing++;
        missingPositions.add(at.toShortString());
    }
}
for (var at : BlockPos.betweenClosed(o.offset(-15, -3, -11), o.offset(15, 8, 10))) {
    if (level.getBlockState(at).is(Blocks.FIRE)) {
        fires++;
        firePositions.add(at.toShortString());
    }
}
var crew = new ArrayList<Object>();
for (var raw : a.list("residents")) {
    var body = Bodies.of(level.getEntity(UUID.fromString(raw.toString()))).orElseThrow();
    for (var stack : body.pack().contents()) {
        if (stack.is(Items.STONE_BRICKS) || stack.is(Items.OAK_PLANKS)) {
            materials += stack.getCount();
        }
        if (stack.getItem() instanceof FireExtinguisherItem) {
            water += FireExtinguisherItem.getFluid(stack).getAmount();
        }
    }
    crew.add(Map.of("uuid", body.id().toString(), "pos", body.mob().position().toString(),
        "doing", body.doing().map(d -> d.what().toString()).orElse("idle"),
        "held", body.mob().getMainHandItem().toString()));
}
var barrel = (net.minecraft.world.Container) level.getBlockEntity(o.offset(-12, 0, 8));
for (int slot = 0; barrel != null && slot < barrel.getContainerSize(); slot++) {
    var stack = barrel.getItem(slot);
    if (stack.getItem() instanceof FireExtinguisherItem) {
        water += FireExtinguisherItem.getFluid(stack).getAmount();
    }
}
var cabinet = (FireHydrantCabinetBlockEntity) level.getBlockEntity(o.offset(9, 0, 28));
var data = level.getEntity(UUID.fromString(a.list("residents").getFirst().toString())).getPersistentData();
var out = new LinkedHashMap<String, Object>();
out.put("fires", fires);out.put("missing", missing);out.put("missingPositions", missingPositions);
out.put("firePositions", firePositions);
out.put("water", water);out.put("materials", materials);
out.put("hydrantWater", cabinet == null ? 0 : cabinet.getFluid().getAmount());
out.put("extinguisherFires", data.getInt("extinguisherFires"));out.put("hoseFires", data.getInt("hoseFires"));
out.put("sharedFires", data.getInt("sharedFires"));
var pulses = new ArrayList<Object>();
for (var tag : data.getList("hosePulses", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
    var pulse = (net.minecraft.nbt.CompoundTag) tag;
    pulses.add(Map.of("tick", pulse.getLong("tick"), "eye", List.of(pulse.getDouble("x"),
        pulse.getDouble("y"), pulse.getDouble("z")), "nearestFireDistance", pulse.getDouble("nearestFireDistance"),
        "minExtinguishedDistance", pulse.getDouble("minExtinguishedDistance"),
        "maxExtinguishedDistance", pulse.getDouble("maxExtinguishedDistance"),
        "hydrantDistance", pulse.getDouble("hydrantDistance"), "waterUsed", pulse.getInt("waterUsed"),
        "fires", pulse.getInt("fires")));
}
out.put("hosePulses", pulses);
out.put("crew", crew);out.put("time", level.getGameTime());
return out;`,
  }),
});

export const DAMAGE_HUD = defineTask<void, any>({
  name: "folkways.promo.recovery-client-evidence",
  side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.api.ui.OverheadTools", "net.minecraft.client.Minecraft"],
    body: `
var mc = Minecraft.getInstance();
var active = OverheadTools.active().orElse(null);
boolean damage = active != null && active.getClass().getSimpleName().equals("DamageControlTool");
var aimed = Class.forName("io.github.izakyl.folkways.front.client.ToolOverlay").getDeclaredMethod("aimed");
aimed.setAccessible(true);
var hovered = (Optional<?>) aimed.invoke(null);
int spray = 0;
var particles = mc.particleEngine.getClass().getDeclaredField("particles");
particles.setAccessible(true);
for (var queue : ((Map<?, ?>) particles.get(mc.particleEngine)).values()) {
    for (var particle : (Collection<?>) queue) {
        if (particle.getClass().getName().endsWith(".SprayParticle")) {
            spray++;
        }
    }
}
var connections = Class.forName("io.github.izakyl.folkways.firefighting.ResidentSprayVisuals")
    .getDeclaredField("ACTIVE");
connections.setAccessible(true);
return Map.of("active", damage && mc.screen != null && mc.screen.getClass().getSimpleName().equals("OverheadScreen"),
    "area", damage && active.marks().stream().anyMatch(m -> m.color() == 0x7CFFAD),
    "card", hovered.filter(OverheadTools.Mark.class::isInstance)
        .map(OverheadTools.Mark.class::cast).filter(mark -> mark.color() == 0x7CFFAD).isPresent(),
    "hidden", mc.options.hideGui,
    "sprayParticles", spray, "hoses", ((Map<?, ?>) connections.get(null)).size());`,
  }),
});

export const DAMAGE_DIAGNOSTIC = defineTask<Record<string, any>, any>({
  name: "folkways.promo.recovery-diagnostic",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var colony = Colonies.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
var presence = colony.service(DamageControlContent.ID, Object.class).orElseThrow();
var values = new LinkedHashMap<String, Object>();
for (String name : List.of("responding", "refusals", "unreachable", "retryAfter", "repairs")) {
    var field = presence.getClass().getDeclaredField(name);
    field.setAccessible(true);
    values.put(name, String.valueOf(field.get(presence)));
}
var responses = presence.getClass().getDeclaredField("responding");
responses.setAccessible(true);
var targets = new ArrayList<Object>();
for (var entry : ((Map<?, ?>) responses.get(presence)).entrySet()) {
    var node = (io.github.izakyl.folkways.core.work.api.Node) entry.getValue();
    var body = Bodies.of(ctx.server().overworld().getEntity((UUID) entry.getKey())).orElseThrow();
    var status = new LinkedHashMap<String, Object>();
    status.put("worker", entry.getKey().toString());
    status.put("position", body.mob().position().toString());
    status.put("site", node.spec().site().toString());
    status.put("stances", node.spec().stances().toString());
    status.put("pack", body.pack().contents().stream().map(Object::toString).toList());
    status.put("navigation", String.valueOf(body.mob().getNavigation().getTargetPos()));
    status.put("navigationDone", body.mob().getNavigation().isDone());
    for (String name : List.of("interaction", "result", "destinations")) {
        var field = node.getClass().getDeclaredField(name);
        field.setAccessible(true);
        status.put(name, String.valueOf(field.get(node)));
    }
    targets.add(status);
}
values.put("targets", targets);
return values;`,
  }),
});

export const DAMAGE_PAUSE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.recovery-staging-pause",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var front = ColonyFront.of(colony);
var zone = front.zonesIn(level).stream().filter(z -> z.id().toString().equals(a.string("zone")))
    .findFirst().orElseThrow();
front.setDrawn(zone.id(), zone.settings()
    .with("extinguish", new ColonySettings.Value.Flag(false))
    .with("repair", new ColonySettings.Value.Flag(false))
    .with("refill", new ColonySettings.Value.Flag(false)));
return true;`,
  }),
});

export const DAMAGE_PANEL_INPUT = defineTask<void, any>({
  name: "folkways.promo.damage-panel-input",
  side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.engine.item.HeldBook"],
    body: `
var mc = net.minecraft.client.Minecraft.getInstance();
var aimed = Class.forName("io.github.izakyl.folkways.front.client.ToolOverlay").getDeclaredMethod("aimed");
aimed.setAccessible(true);
var mark = (Optional<io.github.izakyl.folkways.front.api.ui.OverheadTools.Mark>) aimed.invoke(null);
return Map.of("creative", mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.CREATIVE,
    "bookColony", HeldBook.bound(mc.player).map(book -> book.colonyId().toString()).orElse(""),
    "aimedZone", mark.map(one -> one.area().toString()).orElse(""));`,
  }),
});
