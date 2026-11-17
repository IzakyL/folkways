import path from "node:path";
import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, media, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, waitForElement } from "../ldlib2";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { BUDGET } from "../shared/harness/budgets";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import { PREPARE_RESIDENTS, PROJECT, type Vec } from "../shared/combat/rts-fixtures";
import { foundBookColony, layFloor, openRtsTool } from "../shared/combat/overhead";

// The profile loads the capture fixture so NeoForge checks the active EGL context, keeping Superb Warfare
// feature validation intact even with the early window disabled.
const CONFIG = process.env.FOLKWAYS_WARFARE_RTS_CONFIG ?? "blockwright.toml#warfare-rts";
const WINDOW = "folkways.window.folkways_rts_unit";
const DETAILS = "folkways.window.folkways_rts_unit_details";
const SECOND_HULL = process.env.FOLKWAYS_SECOND_HULL ?? "superbwarfare:bmp_2";

type Units = {
  units: { id: string; kind: string; members: number; seats: number; reserved: number; aboard: number;
    altitude: string }[];
  focus: string; selected: string[]; vehicles: string[];
};

const UNIT = "folkways.rts.unit.";

test("RTS command card: select a vehicle, board it, pick its profile; squads, airframes and mixed selections", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  let pair: E2EPair | undefined;
  const shots = testInfo.outputPath("shots");
  try {
    const run = newRunId();
    pair = await launchPair({
      config: CONFIG,
      server: { instance: `rts-vehicle-server-${run}` },
      client: { instance: `rts-vehicle-client-${run}` },
      snapshot: { entityTypes: ["folkways_vanilla:resident", "superbwarfare:lav_150", "superbwarfare:bmp_2",
        "superbwarfare:ah_6", "superbwarfare:a_10a"] },
    });
    const { server, client } = pair;
    const shoot = async (name: string) => {
      // Park the pointer off the window so a button's tooltip does not cover the shot.
      await input.move(client, { x: 4, y: 120 });
      await frames(client, 3);
      await media.screenshot(client, { path: path.join(shots, `${name}.png`) });
    };
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    for (const command of ["difficulty peaceful", "gamerule doMobSpawning false", "gamerule doDaylightCycle false",
      "time set noon"]) {
      await mustCommand(server, command);
    }
    await layFloor(server, { x: origin.x - 24, z: origin.z - 19 }, { x: origin.x + 24, z: origin.z + 28 }, origin.y,
      "minecraft:smooth_stone", 6);
    const { colony } = await foundBookColony(server, client, player.name, origin, 6);
    const residents = await server.run(PREPARE_RESIDENTS, { colony, ...origin, count: 6 });
    expect(residents).toHaveLength(6);
    const lav = await server.run(SPAWN, { type: "superbwarfare:lav_150", x: origin.x - 7, y: origin.y, z: origin.z + 15 });
    // BMP-2 exercises the weapon-name fallback for its unnamed MG seats as well as mixed selection.
    const bmp = await server.run(SPAWN, { type: SECOND_HULL, x: origin.x + 8, y: origin.y, z: origin.z + 15 });
    // One of each airframe: the card offers each its own movement orders.
    const heli = await server.run(SPAWN, { type: "superbwarfare:ah_6", x: origin.x + 17, y: origin.y, z: origin.z + 2 });
    const jet = await server.run(SPAWN, { type: "superbwarfare:a_10a", x: origin.x - 14, y: origin.y, z: origin.z - 9 });
    await mustCommand(server, `tp ${player.name} ${origin.x + 0.5} ${origin.y} ${origin.z + 2.5} 0 0`);
    await tick.sprint(server, 10);
    await frames(client, 10);
    await openRtsTool(client);
    await waitUnits(client, server, "unit roster lists every vehicle",
      value => [lav, bmp, heli, jet].every(id => value.units.some(unit => unit.id === id)));
    await shoot("01-overview");

    // A squad forms once a selection is ordered; its command card sits at the bottom right.
    await dragAround(client, origin, residents.slice(0, 2));
    await rightClickGround(client, { x: origin.x, y: origin.y, z: origin.z + 4 });
    await waitUnits(client, server, "squad focused", value => value.units.some(unit => unit.kind === "SQUAD"
      && unit.id === value.focus));
    await waitForElement(client, { id: WINDOW });
    await frames(client, 5);
    await shoot("02-squad-window");

    // Clicking a vehicle in the world selects it alone and shows its command card.
    await clickEntity(client, origin, lav);
    await waitUnits(client, server, "LAV focused", value => value.focus === lav && value.selected.length === 0);
    await waitForElement(client, { id: WINDOW });
    await waitForElement(client, { id: "folkways.rts.unit.board" });
    await frames(client, 5);
    await shoot("03-vehicle-window-empty");

    // Board fills the empty seats with the nearest idle residents; the window lists them as on the way, then seated.
    await clickElement(client, { id: "folkways.rts.unit.board" });
    const reserved = await waitUnits(client, server, "seats reserved", value =>
      (value.units.find(unit => unit.id === lav)?.reserved ?? 0) > 0);
    expect(reserved.focus).toBe(lav);
    await frames(client, 5);
    await shoot("04-vehicle-window-boarding");
    const seated = await waitUnits(client, server, "crew seated", value =>
      (value.units.find(entry => entry.id === lav)?.aboard ?? 0) > 0, 1600);
    const crew = seated.units.find(unit => unit.id === lav)!;
    expect(crew.aboard).toBeGreaterThan(0);
    await waitForElement(client, { id: "folkways.rts.unit.release_idle" });
    await frames(client, 5);
    await shoot("05-command-card-seated");
    // A vehicle's seats, stock and weapon rules come from its profile: the card only picks which profile, and has no
    // details window.
    await waitForElement(client, { id: "folkways.rts.unit.details" }, { state: "absent" });
    await clickElement(client, { id: "folkways.rts.unit.profile.change" });
    await frames(client, 5);
    await media.screenshot(client, { path: path.join(shots, "05b-profile-picker.png") });
    // Esc closes the picker first; the vehicle stays selected and its card stays.
    await input.key(client, { keyCode: 256 });
    await frames(client, 3);
    await waitForElement(client, { id: "folkways.choice" }, { state: "absent" });
    await waitUnits(client, server, "LAV still focused after Esc", value => value.focus === lav);
    await waitForElement(client, { id: WINDOW });
    // Move arms a one-shot command: the card marks it until a world click completes it.
    await clickElement(client, { id: "folkways.rts.unit.move" });
    await frames(client, 3);
    await shoot("05d-move-armed");
    const ground = await client.run(PROJECT, { x: origin.x, y: origin.y, z: origin.z + 8, entity: "" });
    await input.click(client, { x: ground.x, y: ground.y }, { button: "left" });

    // A vehicle with residents is a mixed selection: the card stays with the shared commands, details close.
    await dragAround(client, origin, residents.slice(0, 2));
    await clickEntity(client, origin, bmp, ["shift"]);
    const mixed = await waitUnits(client, server, "mixed selection", value => value.vehicles.includes(bmp)
      && value.selected.length > 0);
    expect(mixed.focus).toBe("");
    await waitForElement(client, { id: WINDOW });
    await waitForElement(client, { id: DETAILS }, { state: "absent" });
    await frames(client, 5);
    await shoot("06-mixed-selection");

    // Ground: drive orders, no flight controls; crew in a framed group of its own.
    await selectVehicles(client, [lav]);
    await expectCard(client, ["move", "attack", "stop", "crew", "board", "disembark", "release_idle"],
      ["op.land", "op.loiter", "op.take_off", "altitude"]);
    await shoot("07-card-ground");

    // Helicopter: Stop hovers, Land returns to a pad, and there is no Take off: a Move departs on its own.
    await selectVehicles(client, [heli]);
    await expectCard(client, ["move", "attack", "stop", "op.land", "altitude", "altitude.cruise", "crew", "board"],
      ["op.loiter", "op.take_off"]);
    await shoot("08-card-helicopter");
    // The altitude band is kept per vehicle on the server and comes back in the roster.
    expect((await client.run(READ_UNITS)).units.find(unit => unit.id === heli)?.altitude).toBe("CRUISE");
    await clickElement(client, { id: UNIT + "altitude.high" });
    await waitUnits(client, server, "helicopter flies high", value =>
      value.units.find(unit => unit.id === heli)?.altitude === "HIGH");

    // Fixed wing: it cannot stop over a point, so it loiters there; Land as well.
    await selectVehicles(client, [jet]);
    await expectCard(client, ["op.loiter", "op.land", "altitude", "crew", "board"],
      ["move", "attack", "stop", "op.take_off"]);
    await shoot("09-card-fixed-wing");

    // A tank with a helicopter keeps only what both can do: no Land, no altitude.
    await selectVehicles(client, [lav, heli]);
    await expectCard(client, ["move", "attack", "stop", "crew", "board", "disembark"],
      ["op.land", "op.loiter", "altitude"]);
    await shoot("10-card-mixed-airframes");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function selectVehicles(client: MinecraftClient, ids: string[]) {
  await client.run(SELECT_VEHICLES, { ids });
  await frames(client, 5);
  await waitForElement(client, { id: WINDOW });
}

async function expectCard(client: MinecraftClient, present: string[], absent: string[]) {
  for (const name of present) {
    await waitForElement(client, { id: UNIT + name });
  }
  for (const name of absent) {
    await waitForElement(client, { id: UNIT + name }, { state: "absent" });
  }
}

async function waitUnits(client: MinecraftClient, server: MinecraftServer, what: string,
  done: (value: Units) => boolean, maxTicks = 200): Promise<Units> {
  let last: Units | undefined;
  for (let ticks = 0; ticks <= maxTicks; ticks += 10) {
    last = await client.run(READ_UNITS);
    if (done(last)) {
      return last;
    }
    await tick.sprint(server, 10);
    await frames(client, 3);
  }
  throw new Error(`${what} did not happen: ${JSON.stringify(last)}`);
}

async function clickEntity(client: MinecraftClient, origin: Vec, entity: string, modifiers: ("shift")[] = []) {
  const at = await client.run(PROJECT, { ...origin, entity });
  await input.click(client, { x: at.x, y: at.y }, { button: "left", modifiers });
}

async function rightClickGround(client: MinecraftClient, point: Vec) {
  const at = await client.run(PROJECT, { ...point, entity: "" });
  await input.click(client, { x: at.x, y: at.y }, { button: "right" });
}

async function dragAround(client: MinecraftClient, origin: Vec, entities: string[]) {
  const boxes = await Promise.all(entities.map(entity => client.run(PROJECT, { ...origin, entity })));
  const from = { x: Math.min(...boxes.map(box => box.left)) - 6, y: Math.min(...boxes.map(box => box.top)) - 6 };
  const to = { x: Math.max(...boxes.map(box => box.right)) + 6, y: Math.max(...boxes.map(box => box.bottom)) + 6 };
  await input.drag(client, { from, to, steps: 8, button: "left" });
}

const SPAWN = defineTask<{ type: string } & Vec, string>({
  name: "folkways.rts-vehicle-window.spawn-vehicle", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer"],
    body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();
var entity=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(a.string("type"))).create(level);
entity.moveTo(a.integer("x")+0.5,a.integer("y"),a.integer("z")+0.5,180,0);
level.addFreshEntity(entity);return entity.getUUID().toString();`,
  }),
});

const READ_UNITS = defineTask<void, Units>({
  name: "folkways.rts-vehicle-window.read-units", side: "client",
  source: javaTask({
    imports: ["java.util.Map", "java.util.Set", "java.util.List", "java.util.ArrayList",
      "io.github.izakyl.folkways.core.front.api.ui.OverheadTools",
      "io.github.izakyl.folkways.core.rts.combat.net.ClientUnits"],
    body: `
var units=new ArrayList<Map<String,Object>>();
for(var unit:ClientUnits.units()){
  long reserved=unit.seats().stream().filter(seat->seat.occupant().isPresent()).count();
  long aboard=unit.seats().stream().filter(seat->seat.occupant().map(id->{
    var mc=net.minecraft.client.Minecraft.getInstance();
    for(var e:mc.level.entitiesForRendering()){if(e.getUUID().equals(id))return e.getVehicle()!=null&&e.getVehicle().getUUID().equals(unit.id());}
    return false;}).orElse(false)).count();
  units.add(Map.of("id",unit.id().toString(),"kind",unit.kind().name(),"members",unit.members().size(),
    "seats",unit.seats().size(),"reserved",reserved,"aboard",aboard,"altitude",unit.altitude().name()));
}
var handler=OverheadTools.selectedHandler().orElse(null);
String focus="";List<String> selected=List.of();List<String> vehicles=List.of();
if(handler!=null&&handler.getClass().getName().endsWith(".CombatController")){
  var type=handler.getClass();
  var f=type.getDeclaredMethod("focus");f.setAccessible(true);
  focus=((java.util.Optional<?>)f.invoke(handler)).map(u->((io.github.izakyl.folkways.core.rts.combat.net.UnitSnapshot.Unit)u).id().toString()).orElse("");
  var s=type.getDeclaredField("selected");s.setAccessible(true);selected=((Set<?>)s.get(handler)).stream().map(Object::toString).toList();
  var v=type.getDeclaredField("vehicles");v.setAccessible(true);vehicles=((Set<?>)v.get(handler)).stream().map(Object::toString).toList();
}
return Map.of("units",units,"focus",focus,"selected",selected,"vehicles",vehicles);`,
  }),
});

const SELECT_VEHICLES = defineTask<{ ids: string[] }, boolean>({
  name: "folkways.rts-vehicle-window.select-vehicles", side: "client",
  source: javaTask({
    imports: ["java.util.Set", "java.util.UUID", "java.util.List",
      "io.github.izakyl.folkways.core.front.api.ui.OverheadTools"],
    body: `
var a=Args.of(ctx);var ids=new java.util.ArrayList<UUID>();
for(String id:a.strings("ids"))ids.add(UUID.fromString(id));
var handler=OverheadTools.selectedHandler().orElseThrow();var type=handler.getClass();
var one=type.getDeclaredMethod("selectVehicle",UUID.class);one.setAccessible(true);one.invoke(handler,ids.get(0));
var add=type.getDeclaredMethod("selectVehicles",Set.class,boolean.class);add.setAccessible(true);
for(var id:ids.subList(1,ids.size()))add.invoke(handler,Set.of(id),true);
return true;`,
  }),
});
