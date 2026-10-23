import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements, scrollElementIntoView, visibleBox, waitForElement } from "../ldlib2";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { VisualRun } from "../shared/capture/visual-kit";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { tracePath } from "../shared/harness/out-paths";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";
import { setVisualLanguage } from "../shared/capture/ponder";
import { clickTab } from "../shared/ui/colony-pages";

const GROW = defineTask<{ uuid: string }, { ok: boolean }>({
  name: "folkways.resident-detail.grow",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "net.minecraft.resources.ResourceLocation", "net.minecraft.util.RandomSource",
      "io.github.izakyl.folkways.core.api.resident.body.Body", "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.plugins.person.living.LivingContent", "io.github.izakyl.folkways.front.api.Facing"],
    body: `
var level = ((MinecraftServer)ctx.server()).overworld();
var body = (Body)level.getEntity(UUID.fromString(Args.of(ctx).string("uuid")));
var perks = body.perks();
var add = perks.getClass().getDeclaredMethod("addXp", ResourceLocation.class, int.class, RandomSource.class);
add.setAccessible(true);
add.invoke(perks, ResourceLocation.parse("folkways:building"), 1120, RandomSource.create(17));
add.invoke(perks, ResourceLocation.parse("folkways:farming"), 200, RandomSource.create(19));
var colony = Colonies.of(level.getServer(), body.colonyId().orElseThrow()).orElseThrow();
var living = colony.service(LivingContent.ID, Facing.class).orElseThrow();
var find = living.getClass().getDeclaredMethod("mood", UUID.class);
find.setAccessible(true);
var mood = find.invoke(living, body.id());
long now = level.getGameTime();
var step = mood.getClass().getDeclaredMethod("step", long.class, int.class, int.class, int.class, boolean.class, boolean.class);
step.setAccessible(true);
step.invoke(mood, now - 12001, 20, 1200, -6, false, false);
var dined = mood.getClass().getDeclaredField("dined");
dined.setAccessible(true);
dined.setLong(mood, now - 12000);
step.invoke(mood, now - 12000, 20, 1200, -6, false, false);
var attacked = mood.getClass().getDeclaredMethod("attacked", long.class);
attacked.setAccessible(true);
attacked.invoke(mood, now);
var value = mood.getClass().getDeclaredField("value");
value.setAccessible(true);
value.setDouble(mood, 72);
var housed = mood.getClass().getDeclaredField("housed");
housed.setAccessible(true);
housed.setLong(mood, now);
var housing = mood.getClass().getDeclaredField("housing");
housing.setAccessible(true);
housing.setInt(mood, 6);
step.invoke(mood, now, 20, 1200, -6, false, false);
return Map.of("ok", LivingContent.reading(body).getCompound("reasons").getInt("housing") == 6);
`,
  }),
});

for (const locale of ["en_us", "zh_cn"]) test(`resident detail fits mood, recent events and vocation perks (${locale})`, async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun(`resident-detail-${locale}`);
  const runId = Date.now();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      server: { trace: tracePath("visual", `resident-detail-${locale}-server`), instance: seededOffThreadServer(`resident-detail-server-${runId}`) },
      client: { trace: tracePath("visual", `resident-detail-${locale}-client`), instance: `resident-detail-client-${runId}` },
    });
    const { server, client } = pair;
    await setVisualLanguage(client, locale);
    const grounded = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const founding = await foundColonyFast(server, client, grounded.name, { origin, residentCount: 2 });
    await tick.sprint(server, 20);
    await openColonyPanelViaBook(server, client, grounded.name, founding.standBlock);
    await clickTab(client, "folkways.tab.residents");
    const resident = (await panelElements(client)).find(element => element.id?.startsWith("folkways.resident.name."));
    expect(resident?.id).toBeTruthy();
    const row = resident!.id!;
    const uuid = (await server.run(defineTask<{ prefix: string }, { uuid: string }>({
      name: "folkways.resident-detail.resident",
      side: "server",
      source: javaTask({
        imports: ["net.minecraft.server.MinecraftServer"],
        body: `
for (var entity : ((MinecraftServer)ctx.server()).overworld().getAllEntities()) {
    if (entity.getUUID().toString().startsWith(Args.of(ctx).string("prefix"))) {
        return Map.of("uuid", entity.getUUID().toString());
    }
}
throw new IllegalStateException("resident missing");
`,
      }),
    }), { prefix: row.slice("folkways.resident.name.".length) })).uuid;
    expect((await server.run(GROW, { uuid })).ok).toBe(true);
    await clickElement(client, { id: row });
    await waitForElement(client, { id: "folkways.resident.mood" });
    await frames(client, 10);
    await run.shot(client, "01-mood-and-recent", {
      subject: "Resident detail: mood and alertness meters, colored reasons, recent events and vocation groups in one scrolling body",
      worldState: { uuid }, hard: true,
    });
    await fits(client, run);
    const rested = await waitForElement(client, { id: "folkways.resident.alertness" });
    expect(rested.width).toBeGreaterThan(50);
    for (const gone of ["fullness", "leisure"]) {
      expect((await panelElements(client)).some(element => element.id === `folkways.resident.${gone}`)).toBe(false);
    }
    expect(await run.hover(client, "folkways.resident.reason.housing")).toBeTruthy();
    await run.shot(client, "02-housing-tooltip", {
      subject: "Housing reason: room grade and improvement advice stay translated in the tooltip",
      worldState: { uuid }, hard: true,
    });
    await input.move(client, { x: 0, y: 0 });
    await scrollElementIntoView(client, { id: "folkways.resident.vocations" });
    await frames(client, 3);
    await run.shot(client, "03-vocations", {
      subject: "Resident detail: separate vocation levels out of ten, XP meters, perk rank pips and shared perks",
      worldState: { uuid }, hard: true,
    });
    await fits(client, run);
    expect((await panelElements(client)).some(element => element.id === "folkways.resident.untrained")).toBe(false);
    const perks = (await panelElements(client)).filter(element => element.id?.startsWith("folkways.resident.perk."));
    expect(perks.length).toBeGreaterThanOrEqual(3);
    for (const perk of perks) {
      expect(perk.width).toBeGreaterThan(50);
      expect(perk.height).toBeLessThanOrEqual(40);
    }
    expect(await run.hover(client, perks[0].id!)).toBeTruthy();
    await run.shot(client, "04-perk-tooltip", {
      subject: "Perk names show held rank pips, and hovering explains the perk",
      worldState: { uuid }, hard: true,
    });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function fits(client: E2EPair["client"], run: VisualRun) {
  const all = await panelElements(client);
  const window = all.find(element => element.id === "folkways.window.resident")!;
  const content = all.filter(element => ancestorsOf(all, element).some(parent => parent.id === window.id)
    && visibleBox(all, element) && [element, ...ancestorsOf(all, element)].every(parent => parent.displayed && parent.visible));
  run.note("resident-geometry", content);
  for (const element of content) {
    expect(element.x, element.id ?? element.text).toBeGreaterThanOrEqual(window.x - 1);
    expect(element.x + element.width, element.id ?? element.text).toBeLessThanOrEqual(window.x + window.width + 1);
  }
  const mood = all.find(element => element.id === "folkways.resident.living")!;
  expect(mood.width).toBeLessThanOrEqual(250);
}
