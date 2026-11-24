import { expect, test } from "@playwright/test";
import { clickElement, panelElements, waitForElement } from "../ldlib2";
import { mkdirSync } from "node:fs";
import path from "node:path";
import { defineTask, input, javaTask, media, screen, tick } from "@izakyl/blockwright-minecraft";
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
    imports: ["io.github.izakyl.folkways.core.front.api.ui.OverheadTools", "io.github.izakyl.folkways.core.card.api.notice.Line",
      "io.github.izakyl.folkways.core.front.client.BookTools"],
    body: `
var layer = Class.forName("io.github.izakyl.folkways.core.front.client.Layer");
var preference = layer.getDeclaredField("showing"); preference.setAccessible(true);
var effective = layer.getDeclaredMethod("showing"); effective.setAccessible(true);
var saved = new ArrayList<String>(); var shown = new ArrayList<String>();
for (var one : layer.getEnumConstants()) {
    saved.add(preference.get(one).toString()); shown.add(effective.invoke(one).toString());
}
var marks = new ArrayList<Object>();
for (var source : OverheadTools.marks()) for (var mark : source.marks()) {
    marks.add(Map.of("color", mark.color(), "x", mark.min().getX(), "y", mark.min().getY(), "z", mark.min().getZ()));
}
var aimed = Class.forName("io.github.izakyl.folkways.core.front.client.ToolOverlay").getDeclaredMethod("aimed");
aimed.setAccessible(true);
var hovered = (java.util.Optional<OverheadTools.Mark>) aimed.invoke(null);
return Map.of("saved", saved, "shown", shown, "marks", marks, "chosen", BookTools.picked().map(BookTools.Entry::key).orElse(""),
    "tool", OverheadTools.selected().isPresent(), "drafting", BookTools.drafting(),
    "hovered", hovered.map(OverheadTools.Mark::color).orElse(0),
    "hoveredText", hovered.stream().flatMap(mark -> mark.lines().stream())
        .filter(Line.Text.class::isInstance).map(Line.Text.class::cast)
        .map(line -> line.component().getString()).toList());
`,
  }),
});

type Cell = { x: number; y: number; z: number };

/** Where on the window the middle of a box of cells shows from the camera above. */
const PIXEL_TASK = defineTask<Record<string, number>, { x: number; y: number }>({
  name: "folkways.damage-control.pixel", side: "client",
  source: javaTask({
    body: `
var mc = net.minecraft.client.Minecraft.getInstance();
var args = Args.of(ctx);
var min = new net.minecraft.core.BlockPos((int) args.integer("minX"), (int) args.integer("minY"), (int) args.integer("minZ"));
var max = new net.minecraft.core.BlockPos((int) args.integer("maxX"), (int) args.integer("maxY"), (int) args.integer("maxZ"));
var camera = mc.gameRenderer.getMainCamera();
var relative = net.minecraft.world.phys.Vec3.atCenterOf(min).add(
    net.minecraft.world.phys.Vec3.atLowerCornerOf(max.subtract(min)).scale(0.5)).subtract(camera.getPosition()).toVector3f();
double depth = relative.dot(camera.getLookVector());
double half = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
double wide = half * mc.getWindow().getScreenWidth() / mc.getWindow().getScreenHeight();
return Map.of("x", (1 - relative.dot(camera.getLeftVector()) / (depth * wide)) * mc.getWindow().getScreenWidth() / 2,
    "y", (1 - relative.dot(camera.getUpVector()) / (depth * half)) * mc.getWindow().getScreenHeight() / 2);
`,
  }),
});

const PIXEL = {
  of: (min: Cell, max: Cell) => ({ minX: min.x, minY: min.y, minZ: min.z, maxX: max.x, maxY: max.y, maxZ: max.z }),
};

const SERVER = defineTask<undefined, any>({
  name: "folkways.damage-control.server-probe", side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.colony.internal.ColonyRegistry", "io.github.izakyl.folkways.core.front.engine.colony.ColonyFront"],
    body: `
var server = ctx.server();
var colony = ColonyRegistry.get(server).colonies().iterator().next().works();
var areas = ColonyFront.of(colony).zonesIn(server.overworld()).stream()
    .filter(zone -> zone.delegation().getPath().equals("damage_control")).toList();
if (areas.isEmpty()) return Map.of("areas", 0, "baselines", 0);
var zone = areas.getFirst();
var at = zone.cells().stream().reduce(net.minecraft.core.BlockPos::min).orElseThrow();
var top = zone.cells().stream().reduce(net.minecraft.core.BlockPos::max).orElseThrow();
return Map.of("areas", areas.size(), "baselines", colony.kept(net.minecraft.resources.ResourceLocation.parse("folkways_vanilla:damage_control"))
    .getList("baselines", 10).size(), "extinguish", zone.settings().flag("extinguish"),
    "repair", zone.settings().flag("repair"), "refill", zone.settings().flag("refill"),
    "at", at.getX()+" "+at.getY()+" "+at.getZ(),
    "min", Map.of("x", at.getX(), "y", at.getY(), "z", at.getZ()),
    "max", Map.of("x", top.getX(), "y", top.getY(), "z", top.getZ()));
`,
  }),
});

const DAMAGE_CONTROL = "kind:folkways_vanilla:damage_control";
const CARD_WINDOW = "folkways.card.window";
const RECORD = "folkways.card.button.record";
const RELEASE = "folkways.card.button.release";
const flag = (key: string) => `folkways.card.flag.${key}`;

test("damage control is drawn from the defense tools from above, set up on its card and marked in the world", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const shots = path.resolve("tests/out/e2e/drawing");
  mkdirSync(shots, { recursive: true });
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
    await tryCommand(server, `item replace entity ${name} weapon.mainhand with folkways_core:colony_book`);
    await tick.sprint(server, 5);
    await createColonyViaBook(server, client, name, origin);
    await screen.dismiss(client);
    await tryCommand(server, `tp ${name} ${origin.x + 0.5} ${origin.y} ${origin.z + 0.5} 0 30`);
    await tick.sprint(server, 10);
    // Held in the player's own view, the book shows its key hints and no toolbar.
    await frames(client, 10);
    await media.screenshot(client, { path: path.join(shots, "book-held.png") });
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
    const labels = (await screen.widgets(client)).map((widget: any) => widget.label);
    expect(labels, "the view has no damage control mode of its own").not.toContain("Damage Control");
    expect(labels).toEqual(expect.arrayContaining(["Take in", "Defense", "Build", "Control"]));
    expect(labels, "a group's tools show only while it is open").not.toContain("Damage control");
    await screen.clickWidget(client, { label: "Defense" });
    await frames(client, 4);
    await screen.clickWidget(client, { label: "Damage control" });
    await frames(client, 8);
    const picked = await client.run(PROBE, undefined);
    expect(picked.chosen).toBe(DAMAGE_CONTROL);
    expect(picked.shown).toEqual(before.shown);
    expect(picked.saved).toEqual(before.saved);
    const view = await screen.current(client);
    const centre = { x: Math.round(view!.width / 2), y: Math.round(view!.height / 2) };
    await input.click(client, centre);
    await frames(client, 3);
    expect((await client.run(PROBE, undefined)).drafting).toBe(true);
    // Another shape's entry lets the half-drawn box go.
    await screen.clickWidget(client, { label: "Take in" });
    await frames(client, 3);
    expect((await client.run(PROBE, undefined)).drafting).toBe(false);
    await screen.clickWidget(client, { label: "Defense" });
    await frames(client, 4);
    expect((await client.run(PROBE, undefined)).chosen, "a group opens on its last pick").toBe(DAMAGE_CONTROL);
    await input.click(client, centre);
    await tick.sprint(server, 3);
    await input.click(client, { x: centre.x + 60, y: centre.y - 30 });
    await tick.sprint(server, 3);
    await frames(client, 5);
    await media.screenshot(client, { path: path.join(shots, "tools-overhead.png") });
    await screen.clickWidget(client, { label: "Set it up" });
    await expect.poll(async () => { await tick.sprint(server, 3); return (await server.run(SERVER, undefined)).areas; }).toBe(1);
    // The new area's card opens by itself, with the baseline button damage control adds to it.
    await waitForElement(client, { id: CARD_WINDOW });
    await waitForElement(client, { id: RECORD });
    await frames(client, 5);
    await media.screenshot(client, { path: path.join(shots, "card-after-drawing.png") });
    expect((await panelElements(client)).some(element => element.id === flag("enabled"))).toBe(false);
    await clickElement(client, { id: RECORD });
    await expect.poll(async () => { await tick.sprint(server, 3); return (await server.run(SERVER, undefined)).baselines; }).toBe(1);
    for (const [key, value] of [
      ["extinguish", false],
      ["repair", true],
      ["refill", false],
    ] as const) {
      await waitForElement(client, { id: flag(key) });
      await clickElement(client, { id: flag(key) });
      await expect.poll(async () => { await tick.sprint(server, 3); return (await server.run(SERVER, undefined))[key]; }).toBe(value);
    }
    // Leave repair off while inspecting the missing cell, so workers cannot refill it first.
    await clickElement(client, { id: flag("repair") });
    await expect.poll(async () => { await tick.sprint(server, 3); return (await server.run(SERVER, undefined)).repair; }).toBe(false);
    await screen.dismiss(client);
    await screen.waitFor(client, "OverheadScreen");
    // Right-clicking the area from above opens its card again.
    const area = await server.run(SERVER, undefined);
    await input.click(client, await client.run(PIXEL_TASK, PIXEL.of(area.min, area.max)), { button: "right" });
    await waitForElement(client, { id: flag("repair") });
    const reopened = await server.run(SERVER, undefined);
    expect([reopened.extinguish, reopened.repair, reopened.refill]).toEqual([false, false, false]);
    await screen.dismiss(client);
    await screen.waitFor(client, "OverheadScreen");
    await tryCommand(server, `setblock ${area.at} air`);
    await expect.poll(async () => {
      await tick.sprint(server, 25);
      const probe = await client.run(PROBE, undefined);
      return probe.marks.some((mark: any) => mark.color === 0xFFD45C);
    }).toBe(true);
    const missingMark = (await client.run(PROBE, undefined)).marks.find((mark: any) => mark.color === 0xFFD45C);
    await input.move(client, await client.run(PIXEL_TASK, PIXEL.of(missingMark, missingMark)));
    await frames(client, 6);
    const missing = await client.run(PROBE, undefined);
    expect(missing.hovered).toBe(0xFFD45C);
    expect(missing.hoveredText).toContain("Missing: Smooth Stone");
    await screen.clickWidget(client, { label: "Take in" });
    await frames(client, 6);
    const after = await client.run(PROBE, undefined);
    expect(after.chosen).toBe("point");
    expect(after.saved).toEqual(before.saved);
    expect(after.shown).toEqual(before.shown);
    expect(after.marks.length, "the marks stay whatever the book has picked").toBeGreaterThan(0);
    await input.click(client, await client.run(PIXEL_TASK, PIXEL.of(area.min, area.max)), { button: "right" });
    await waitForElement(client, { id: RELEASE });
    await clickElement(client, { id: RELEASE });
    await expect.poll(async () => { await tick.sprint(server, 3); return (await server.run(SERVER, undefined)).areas; }).toBe(0);
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
