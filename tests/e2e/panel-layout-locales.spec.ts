import { expect, test } from "@playwright/test";
import { defineTask, javaTask, media, tick, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook, tryCommand, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { LayoutProbe, formatReport, type LintReport } from "../shared/capture/layout-lint";
import { clickTab, discoverPages } from "../shared/ui/colony-pages";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, reportPath, shotPath, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeAction } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { writeFileSync } from "node:fs";

// The visual suite lints every page, but in English on a wide interface. Players run Chinese, whose words do not
// break, on a large interface: 1280x720 at scale 3 is 427 wide, about what 1920x1080 at its automatic scale 4
// leaves. There the list of pages folds behind the menu button and the cards go one to a line, and a label that
// only fits in English must still not draw over its neighbour.
const LOCALES = ["en_us", "zh_cn"] as const;
const GUI_SCALE = 3;

const LANGUAGE = defineTask<{ code: string }, { ok: true }>({
  name: "folkways.panel-layout.language",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var mc = Minecraft.getInstance();
String code = Args.of(ctx).string("code");
mc.getLanguageManager().setSelected(code);
mc.options.languageCode = code;
mc.reloadResourcePacks();
return Map.of("ok", true);
`,
  }),
});

const SETTLED = defineTask<{ code: string; scale: number }, { settled: boolean; width: number }>({
  name: "folkways.panel-layout.settled",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var mc = Minecraft.getInstance();
Args args = Args.of(ctx);
int scale = (int) args.integer("scale");
if (mc.options.guiScale().get() != scale) {
    mc.options.guiScale().set(scale);
    mc.resizeDisplay();
}
boolean settled = mc.getOverlay() == null && mc.getLanguageManager().getSelected().equals(args.string("code"));
return Map.of("settled", settled, "width", mc.getWindow().getGuiScaledWidth());
`,
  }),
});

test("every colony page fits its words in each language on a large interface", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const runId = newRunId();
  const reports: LintReport[] = [];
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml",
      server: { trace: tracePath("e2e", "panel-layout-server"), instance: seededServer(`panel-layout-server-${runId}`) },
      client: { trace: tracePath("e2e", "panel-layout-client"), instance: `panel-layout-client-${runId}` },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await tryCommand(server, `gamemode survival ${grounded.name}`);
    const founding = await foundColonyFast(server, client, grounded.name, { origin, residentCount: 2 });
    // A queue at its cap tells the longest line the person page has: that nobody new is coming.
    await tryCommand(server, "time add 240000");
    await tick.sprint(server, 200);
    const pages = await discoverPages(server, grounded.name);

    for (const code of LOCALES) {
      await client.run(LANGUAGE, { code });
      await settle(client, code);
      await openColonyPanelViaBook(server, client, grounded.name, founding.standBlock);
      const probe = new LayoutProbe(client);
      for (const page of pages) {
        await clickTab(client, page.token);
        await safeAction(() => frames(client, 3));
        await safeAction(() => media.screenshot(client, { path: shotPath("e2e", `panel-layout-${code}-${page.name}`) }));
        reports.push(await probe.lint(`${code}@${GUI_SCALE}:${page.name}`));
      }
    }
  } finally {
    writeFileSync(reportPath("e2e", "panel-layout-locales"), JSON.stringify(reports, null, 2));
    await pair?.teardown(testInfo);
  }

  const dirty = reports.filter((report) => report.errors > 0);
  expect(dirty.map((report) => report.scope),
    `pages whose layout breaks:\n${dirty.map(formatReport).join("\n")}`).toEqual([]);
});

async function settle(client: MinecraftClient, code: string) {
  for (let attempt = 0; attempt < 120; attempt++) {
    const state = await client.run(SETTLED, { code, scale: GUI_SCALE });
    if (state.settled) {
      return;
    }
    await frames(client, 5);
  }
  throw new Error(`the client never finished switching to ${code} at scale ${GUI_SCALE}`);
}
