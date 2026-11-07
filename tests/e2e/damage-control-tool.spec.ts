import { expect, test } from "@playwright/test";
import { clickElement, panelElements, waitForElement } from "../ldlib2";
import { defineTask, input, javaTask, screen, tick } from "@izakyl/blockwright-minecraft";
import { createColonyViaBook, tryCommand, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { newRunId } from "../shared/harness/run-id";
import { BUDGET } from "../shared/harness/budgets";

const PROBE = defineTask<undefined, any>({
  name: "folkways.damage-control.tool-probe", side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.api.ui.OverheadTools", "io.github.izakyl.folkways.front.api.notice.Line"],
    body: `
var mc = net.minecraft.client.Minecraft.getInstance();
var layer = Class.forName("io.github.izakyl.folkways.front.client.Layer");
var preference = layer.getDeclaredField("showing"); preference.setAccessible(true);
var effective = layer.getDeclaredMethod("showing"); effective.setAccessible(true);
var saved = new ArrayList<String>(); var shown = new ArrayList<String>();
for (var one : layer.getEnumConstants()) {
    saved.add(preference.get(one).toString()); shown.add(effective.invoke(one).toString());
}
var marks = new ArrayList<Object>();
for (var tool : OverheadTools.active().stream().toList()) for (var mark : tool.marks()) {
    marks.add(Map.of("color", mark.color(), "x", mark.min().getX(), "y", mark.min().getY(), "z", mark.min().getZ()));
}
var aimed = Class.forName("io.github.izakyl.folkways.front.client.ToolOverlay").getDeclaredMethod("aimed");
aimed.setAccessible(true);
var hovered = (java.util.Optional<OverheadTools.Mark>) aimed.invoke(null);
return Map.of("saved", saved, "shown", shown, "marks", marks, "active", OverheadTools.active().isPresent(),
    "drafting", OverheadTools.drafting(), "hovered", hovered.map(OverheadTools.Mark::color).orElse(0),
    "hoveredText", hovered.stream().flatMap(mark -> mark.lines().stream())
        .filter(Line.Text.class::isInstance).map(Line.Text.class::cast)
        .map(line -> line.component().getString()).toList());
`,
  }),
});

const MARK_PIXEL = defineTask<{ color: number }, { x: number; y: number }>({
  name: "folkways.damage-control.mark-pixel", side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.api.ui.OverheadTools"],
    body: `
var mc = net.minecraft.client.Minecraft.getInstance();
int color = (int) Args.of(ctx).integer("color");
var mark = OverheadTools.active().orElseThrow().marks().stream()
    .filter(one -> one.color() == color || color == 0x7CFFAD && one.color() == 0x3A9D70)
    .findFirst().orElseThrow();
var camera = mc.gameRenderer.getMainCamera();
var relative = net.minecraft.world.phys.Vec3.atCenterOf(mark.min()).add(
    net.minecraft.world.phys.Vec3.atLowerCornerOf(mark.max().subtract(mark.min())).scale(0.5)).subtract(camera.getPosition()).toVector3f();
double depth = relative.dot(camera.getLookVector());
double half = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
double wide = half * mc.getWindow().getScreenWidth() / mc.getWindow().getScreenHeight();
return Map.of("x", (1 - relative.dot(camera.getLeftVector()) / (depth * wide)) * mc.getWindow().getScreenWidth() / 2,
    "y", (1 - relative.dot(camera.getUpVector()) / (depth * half)) * mc.getWindow().getScreenHeight() / 2);
`,
  }),
});

const SERVER = defineTask<undefined, any>({
  name: "folkways.damage-control.server-probe", side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.colony.internal.ColonyRegistry", "io.github.izakyl.folkways.front.engine.colony.ColonyFront"],
    body: `
var server = ctx.server();
var colony = ColonyRegistry.get(server).colonies().iterator().next().works();
var areas = ColonyFront.of(colony).zonesIn(server.overworld()).stream()
    .filter(zone -> zone.delegation().getPath().equals("damage_control")).toList();
if (areas.isEmpty()) return Map.of("areas", 0, "baselines", 0);
var zone = areas.getFirst();
var at = zone.cells().stream().reduce(net.minecraft.core.BlockPos::min).orElseThrow();
return Map.of("areas", areas.size(), "baselines", colony.kept(net.minecraft.resources.ResourceLocation.parse("folkways:damage_control"))
    .getList("baselines", 10).size(), "extinguish", zone.settings().flag("extinguish"),
    "repair", zone.settings().flag("repair"), "refill", zone.settings().flag("refill"),
    "at", at.getX()+" "+at.getY()+" "+at.getZ());
`,
  }),
});

test("damage control marks open zone settings, persist flags and release through the panel", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const run = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      server: { instance: seededServer(`damage-tool-server-${run}`), trace: tracePath("e2e", "damage-tool-server") },
      client: { instance: `damage-tool-client-${run}`, trace: tracePath("e2e", "damage-tool-client") },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const name = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await tryCommand(server, `fill ${origin.x - 16} ${origin.y} ${origin.z - 20} ${origin.x + 16} ${origin.y + 16} ${origin.z + 16} air`);
    await tryCommand(server, `fill ${origin.x - 16} ${origin.y - 1} ${origin.z - 20} ${origin.x + 16} ${origin.y - 1} ${origin.z + 16} smooth_stone`);
    await tryCommand(server, `item replace entity ${name} weapon.mainhand with folkways:colony_book`);
    await tick.sprint(server, 5);
    await createColonyViaBook(server, client, name, origin);
    await screen.dismiss(client);
    await tryCommand(server, `tp ${name} ${origin.x + 0.5} ${origin.y} ${origin.z + 0.5} 0 0`);
    await tick.sprint(server, 10);
    const tap = async (keyCode: number) => {
      await input.key(client, { keyCode, action: "press" });
      await input.key(client, { keyCode, action: "release" });
      await frames(client, 5);
    };
    await tap(86);
    await screen.waitFor(client, "OverheadScreen");
    await screen.clickWidget(client, { label: "Zones" });
    await screen.clickWidget(client, { label: "Paths" });
    await screen.clickWidget(client, { label: "Paths" });
    await screen.clickWidget(client, { label: "Residents" });
    const before = await client.run(PROBE, undefined);
    expect(before.saved).toEqual(["MARKS", "NONE", "ALL", "NONE"]);
    await screen.clickWidget(client, { label: "Damage Control" });
    await frames(client, 8);
    const focused = await client.run(PROBE, undefined);
    expect(focused.shown).toEqual(before.shown);
    expect(focused.saved).toEqual(before.saved);
    const view = await screen.current(client);
    const centre = { x: Math.round(view!.width / 2), y: Math.round(view!.height / 2) };
    await input.click(client, centre);
    await frames(client, 3);
    expect((await client.run(PROBE, undefined)).drafting).toBe(true);
    await screen.clickWidget(client, { label: "Build" });
    await frames(client, 3);
    expect((await client.run(PROBE, undefined)).drafting).toBe(false);
    await screen.clickWidget(client, { label: "Damage Control" });
    await input.click(client, centre);
    await tick.sprint(server, 3);
    await input.click(client, { x: centre.x + 60, y: centre.y - 30 });
    await tick.sprint(server, 3);
    await screen.clickWidget(client, { label: "Protect marked area" });
    await expect.poll(async () => { await tick.sprint(server, 3); return (await server.run(SERVER, undefined)).areas; }).toBe(1);
    await frames(client, 8);
    await input.click(client, await client.run(MARK_PIXEL, { color: 0x7CFFAD }), { button: "right" });
    await waitForElement(client, { id: "folkways.zoneconfig.action.record" });
    expect((await panelElements(client)).some(element =>
      element.id === "folkways.zoneconfig.value.enabled")).toBe(false);
    await clickElement(client, { id: "folkways.zoneconfig.action.record" });
    await expect.poll(async () => { await tick.sprint(server, 3); return (await server.run(SERVER, undefined)).baselines; }).toBe(1);
    for (const [key, value] of [
      ["extinguish", false],
      ["repair", true],
      ["refill", false],
    ] as const) {
      await clickElement(client, { id: `folkways.zoneconfig.value.${key}` });
      await expect.poll(async () => { await tick.sprint(server, 3); return (await server.run(SERVER, undefined))[key]; }).toBe(value);
    }
    // Leave repair off while inspecting the missing cell, so workers cannot refill it first.
    await clickElement(client, { id: "folkways.zoneconfig.value.repair" });
    await screen.dismiss(client);
    await screen.waitFor(client, "OverheadScreen");
    await input.click(client, await client.run(MARK_PIXEL, { color: 0x7CFFAD }), { button: "right" });
    await waitForElement(client, { id: "folkways.zoneconfig.value.repair" });
    const reopened = await server.run(SERVER, undefined);
    expect([reopened.extinguish, reopened.repair, reopened.refill]).toEqual([false, false, false]);
    await screen.dismiss(client);
    await screen.waitFor(client, "OverheadScreen");
    const state = await server.run(SERVER, undefined);
    await tryCommand(server, `setblock ${state.at} air`);
    await expect.poll(async () => {
      await tick.sprint(server, 25);
      const probe = await client.run(PROBE, undefined);
      return probe.marks.some((mark: any) => mark.color === 0xFFD45C);
    }).toBe(true);
    await input.move(client, await client.run(MARK_PIXEL, { color: 0xFFD45C }));
    await frames(client, 6);
    const missing = await client.run(PROBE, undefined);
    expect(missing.hovered).toBe(0xFFD45C);
    expect(missing.hoveredText).toContain("Missing: Smooth Stone");
    await screen.clickWidget(client, { label: "Build" });
    await frames(client, 6);
    const after = await client.run(PROBE, undefined);
    expect(after.active).toBe(false);
    expect(after.saved).toEqual(before.saved);
    expect(after.shown).toEqual(before.shown);
    await screen.clickWidget(client, { label: "Damage Control" });
    await frames(client, 8);
    await input.click(client, await client.run(MARK_PIXEL, { color: 0x7CFFAD }), { button: "right" });
    await clickElement(client, { id: "folkways.selection.release" });
    await expect.poll(async () => (await server.run(SERVER, undefined)).areas).toBe(0);
    await screen.dismiss(client);
    await screen.waitFor(client, "OverheadScreen");
    await tap(86);
    expect((await client.run(PROBE, undefined)).saved).toEqual(before.saved);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
