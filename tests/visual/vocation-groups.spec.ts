import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, screen, tick, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { readFileSync } from "node:fs";
import { visibleBox, clickElement, panelElements, scrollElementIntoView, waitForElement } from "../ldlib2";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { VisualRun } from "../shared/capture/visual-kit";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { tracePath } from "../shared/harness/out-paths";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";
import { SET_GUI_SCALE, setVisualLanguage } from "../shared/capture/ponder";
import { clickTab } from "../shared/ui/colony-pages";
import { boardActs } from "../shared/ui/site-panel";

const GATHERING = "folkways_core.gathering";
const FARMING = "folkways_vanilla:farming";
const HERDING = "folkways_vanilla:herding";
const GROUPS = ["gathering", "processing", "construction", "transport", "defence"];
const SPRITES = (JSON.parse(readFileSync("tools/vocation-icons/icons.json", "utf8")) as {
  icons: Array<{ sprite: string }>;
}).icons.map(icon => icon.sprite);

// Check actual atlas entries and the paper/dark rendering paths, including the Create-owned sprites.
const ICONS = defineTask<{ sprites: string }, Array<{ id: string; width: number; height: number; dark: string; glyph: boolean }>>({
  name: "folkways.vocation-groups.icons", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft", "net.minecraft.resources.ResourceLocation",
    "io.github.izakyl.folkways.core.front.api.ui.Signs", "io.github.izakyl.folkways.core.front.api.notice.Sentence"], body: `
var mc = Minecraft.getInstance();
var result = new ArrayList<Map<String, Object>>();
for (var id : Args.of(ctx).string("sprites").split(",")) {
    var sprite = ResourceLocation.parse(id);
    var contents = mc.getGuiSprites().getSprite(sprite).contents();
    var signs = Signs.of(Sentence.of(Sentence.glyph(sprite)));
    var dark = Signs.onDark(sprite);
    var rim = mc.getGuiSprites().getSprite(dark).contents();
    if (rim.width() != 10 || rim.height() != 10) {
        throw new IllegalStateException("wrong dark sprite size: " + dark);
    }
    result.add(Map.of("id", contents.name().toString(), "width", contents.width(), "height", contents.height(),
        "dark", dark.toString(), "glyph", signs.size() == 1 && signs.getFirst() instanceof Signs.Sign.Mark));
}
return result;
` }),
});

// Levels in a few vocations across groups, so the resident's vocation list has headings to show.
const GROW = defineTask<{ prefix: string }, { uuid: string }>({
  name: "folkways.vocation-groups.grow",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "net.minecraft.resources.ResourceLocation", "net.minecraft.util.RandomSource",
      "io.github.izakyl.folkways.core.resident.api.Body"],
    body: `
for (var entity : ((MinecraftServer)ctx.server()).overworld().getAllEntities()) {
    if (entity instanceof Body body && entity.getUUID().toString().startsWith(Args.of(ctx).string("prefix"))) {
        var perks = body.perks();
        var add = perks.getClass().getDeclaredMethod("addXp", ResourceLocation.class, int.class, RandomSource.class);
        add.setAccessible(true);
        for (var trade : List.of("folkways_vanilla:farming", "folkways_vanilla:herding", "folkways_vanilla:building", "folkways_core:hauling", "folkways_vanilla:patrolling")) {
            add.invoke(perks, ResourceLocation.parse(trade), 300, RandomSource.create(17));
        }
        return Map.of("uuid", entity.getUUID().toString());
    }
}
throw new IllegalStateException("resident missing");
`,
  }),
});

// Reads one resident's licence for a vocation, first setting it when a priority is given (negative forbids).
const LICENCE = defineTask<{ prefix: string; vocation: string; priority: number }, { allowed: boolean; priority: number }>({
  name: "folkways.vocation-groups.licence",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "net.minecraft.resources.ResourceLocation",
      "io.github.izakyl.folkways.core.resident.api.Body", "io.github.izakyl.folkways.core.vocation.api.Vocations",
      "io.github.izakyl.folkways.core.vocation.api.Keenness", "io.github.izakyl.folkways.core.shell.neoforge.FolkwaysAttachments"],
    body: `
var args = Args.of(ctx);
for (var entity : ((MinecraftServer)ctx.server()).overworld().getAllEntities()) {
    if (entity instanceof Body body && entity.getUUID().toString().startsWith(args.string("prefix"))) {
        var licences = body.licences();
        var trade = licences.trade(Vocations.of(ResourceLocation.parse(args.string("vocation"))).orElseThrow()).orElseThrow();
        int priority = (int) args.integer("priority");
        if (priority != 0) {
            licences.allow(trade, priority > 0);
            licences.setKeenness(trade, Keenness.ofNumber(Math.abs(priority)).orElseThrow());
            body.mob().setData(FolkwaysAttachments.LICENCES, licences);
        }
        return Map.of("allowed", licences.allows(trade), "priority", licences.keennessOf(trade).number());
    }
}
throw new IllegalStateException("resident missing");
`,
  }),
});

for (const locale of ["en_us", "zh_cn"]) test(`vocation groups fold the work grid (${locale})`, async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun(`vocation-groups-${locale}`);
  const runId = Date.now();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      server: { trace: tracePath("visual", `vocation-groups-${locale}-server`), instance: seededOffThreadServer(`vocation-groups-server-${runId}`) },
      client: { trace: tracePath("visual", `vocation-groups-${locale}-client`), instance: `vocation-groups-client-${runId}` },
    });
    const { server, client } = pair;
    await setVisualLanguage(client, locale);
    await client.run(SET_GUI_SCALE, { scale: 1 });
    expect(await client.run(ICONS, { sprites: SPRITES.join(",") })).toEqual(SPRITES.map(id => ({
      id, width: 10, height: 10, dark: id.replace(":", ":dark/"), glyph: true,
    })));
    const grounded = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const founding = await foundColonyFast(server, client, grounded.name, { origin, residentCount: 3 });
    await tick.sprint(server, 20);
    await openColonyPanelViaBook(server, client, grounded.name, founding.standBlock);
    await clickTab(client, "folkways.tab.residents");
    await frames(client, 5);
    const names = (await panelElements(client)).filter(element => element.id?.startsWith("folkways.resident.name."));
    expect(names.length).toBe(3);
    const [first, second] = names.map(element => element.id!.slice("folkways.resident.name.".length));
    const group = (who: string) => `folkways.resident.group.${GATHERING}.${who}`;
    const farming = (who: string) => `folkways.resident.vocation.folkways_vanilla.farming.${who}`;

    // Folded by default: one column per group, and no vocation's own column on show.
    expect(await shows(client, group(first))).toBe(true);
    expect(await shows(client, farming(first))).toBe(false);
    expect(await text(client, group(first))).toBe("3");
    await expectHeadings(client, "folkways.resident");
    await run.shot(client, "01-collapsed", { subject: "Work grid folded: one column per vocation group", worldState: {}, hard: true });
    expect(await run.hover(client, `folkways.resident.open.${GATHERING}`, 15)).toBeTruthy();
    await run.shot(client, "01b-header-tooltip", { subject: "Group heading: name, vocation count and click hint", worldState: {}, hard: true });
    await input.move(client, { x: 0, y: 0 });

    // A resident whose gathering vocations differ shows the group as mixed.
    await server.run(LICENCE, { prefix: first, vocation: HERDING, priority: -3 });
    await server.run(LICENCE, { prefix: second, vocation: FARMING, priority: 1 });
    await tick.sprint(server, 10);
    await expect.poll(() => text(client, group(first))).toBe("±");
    await expect.poll(() => text(client, group(second))).toBe("±");
    await input.move(client, { x: 0, y: 0 });
    await run.shot(client, "02-mixed", { subject: "Mixed group cells where a group's vocations differ", worldState: {}, hard: true });
    expect(await run.hover(client, group(first))).toBeTruthy();
    await run.shot(client, "03-mixed-tooltip", { subject: "A mixed group cell lists each vocation's standing", worldState: {}, hard: true });

    // Opening the group shows each vocation, editable as before; opening it again folds it back.
    await clickElement(client, { id: `folkways.resident.open.${GATHERING}` });
    await frames(client, 3);
    expect(await shows(client, group(first))).toBe(false);
    expect(await shows(client, farming(first))).toBe(true);
    expect(await text(client, `folkways.resident.vocation.folkways_vanilla.herding.${first}`)).toBe("–");
    await input.move(client, { x: 0, y: 0 });
    await run.shot(client, "04-expanded", { subject: "Gathering opened into its vocations", worldState: {}, hard: true });
    await clickElement(client, { id: `folkways.resident.open.${GATHERING}` });
    await frames(client, 3);
    expect(await shows(client, group(first))).toBe(true);
    expect(await shows(client, farming(first))).toBe(false);

    // A click on a mixed group allows every vocation in it.
    await clickElement(client, { id: group(first) });
    await tick.sprint(server, 10);
    expect((await server.run(LICENCE, { prefix: first, vocation: HERDING, priority: 0 })).allowed).toBe(true);
    await expect.poll(() => text(client, group(first))).toBe("3");
    await input.move(client, { x: 0, y: 0 });
    await run.shot(client, "05-applied", { subject: "A click on the mixed group set all its vocations alike", worldState: {}, hard: true });

    // The resident's own window keeps its vocation levels under the group headings.
    await server.run(GROW, { prefix: first });
    await clickElement(client, { id: `folkways.resident.name.${first}` });
    await waitForElement(client, { id: "folkways.resident.mood" });
    await scrollElementIntoView(client, { id: `folkways.resident.vocation_group.${GATHERING}` });
    await frames(client, 5);
    const detail = await panelElements(client);
    const heading = detail.find(element => element.id === `folkways.resident.vocation_group.${GATHERING}`)!;
    const icon = detail.find(element => element.depth === heading.depth + 1 && element.index > heading.index)!;
    expect(icon.width, "the detail group's label leaves room for its icon").toBe(10);
    await run.shot(client, "06-detail", { subject: "Resident detail vocation levels under group headings", worldState: {}, hard: true });
    await clickElement(client, { id: "folkways.resident.back" });

    // Arrival defaults fold the same way.
    await clickTab(client, "folkways.page.person");
    const opens = await boardActs(client, "open", "folkways_vanilla.person");
    await clickElement(client, { id: opens[2].id });
    await waitForElement(client, { id: `folkways.work_defaults.group.${GATHERING}` });
    await frames(client, 3);
    await expectHeadings(client, "folkways.work_defaults");
    const defaults = await panelElements(client);
    const defaultHeading = defaults.find(element => element.id === `folkways.work_defaults.open.${GATHERING}`)!;
    const defaultCell = defaults.find(element => element.id === `folkways.work_defaults.group.${GATHERING}`)!;
    expect(defaultCell.y - defaultHeading.y, "folded defaults have no redundant count/header row").toBeLessThan(24);
    await input.move(client, { x: 0, y: 0 });
    await run.shot(client, "07-defaults", { subject: "Work defaults folded by group", worldState: {}, hard: true });
    await clickElement(client, { id: `folkways.work_defaults.open.${GATHERING}` });
    await frames(client, 3);
    expect(await shows(client, "folkways.work_defaults.folkways_vanilla.farming")).toBe(true);
    await input.move(client, { x: 0, y: 0 });
    await run.shot(client, "08-defaults-expanded", { subject: "Work defaults with gathering opened", worldState: {}, hard: true });

  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function expectHeadings(client: MinecraftClient, prefix: string) {
  const elements = await panelElements(client);
  for (const group of GROUPS) {
    const heading = elements.find(element => element.id === `${prefix}.open.folkways_core.${group}`);
    expect(heading, `${group} heading exists`).toBeTruthy();
    const box = visibleBox(elements, heading!);
    expect(box, `${group} heading is visible`).toBeTruthy();
    expect(box!.width).toBe(24);
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(1280);
    expect(box!.y + box!.height).toBeLessThanOrEqual(720);
    expect(heading!.text ?? "", "group headings contain icons instead of translated labels").toBe("");
  }
}

async function text(client: MinecraftClient, id: string): Promise<string | undefined> {
  return (await panelElements(client)).find(one => one.id === id)?.text;
}

async function shows(client: MinecraftClient, id: string): Promise<boolean> {
  const element = (await panelElements(client)).find(one => one.id === id);
  return !!element && element.displayed !== false && element.width > 0;
}
