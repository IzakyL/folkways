import { expect, test } from "@playwright/test";
import { input } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { VisualRun } from "../shared/capture/visual-kit";
import { OPEN_PONDER, PONDER_FOLDERS, READ_PONDER, SET_GUI_SCALE, ponderCatalog, setVisualLanguage } from "../shared/capture/ponder";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { tracePath } from "../shared/harness/out-paths";
import { frames } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

test.describe.configure({ mode: "parallel" });

// PONDER_SCENES=housing,tavern records only those lessons, in whichever captures have them.
const only = process.env.PONDER_SCENES?.split(",").map(name => name.trim()).filter(Boolean);

// Discover at runtime: optional integrations can add lessons to any folder.
for (const capture of [
  { profile: "base", locale: "en_us", scale: 2 },
  { profile: "cookery-tavern", locale: "en_us", scale: 2 },
  { profile: "base", locale: "zh_cn", scale: 2 },
  { profile: "base", locale: "en_us", scale: 3 },
]) test(`ponder ${capture.profile} ${capture.locale} scale ${capture.scale}`, async ({}, testInfo) => {
  test.setTimeout(90 * 60_000);
  const { profile, locale, scale } = capture;
  const scope = `ponder-${profile}-${locale}-scale-${scale}`;
  const runId = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({ config: profile === "base" ? "blockwright.toml" : `blockwright.toml#${profile}`,
      server: { trace: tracePath("visual", `${scope}-server`), instance: seededOffThreadServer(`${scope}-server-${runId}`) },
      client: { trace: tracePath("visual", `${scope}-client`), instance: `${scope}-client-${runId}` } });
    const { server, client } = pair;
    await waitForGroundedPlayer(server);
    await setVisualLanguage(client, locale);
    await client.run(SET_GUI_SCALE, { scale });
    const folders = await client.run(PONDER_FOLDERS);
    const scenes = folders.filter(folder => profile === "base" || folder.id === "folkways:cookery")
      .flatMap(folder => folder.scenes);
    expect(scenes.length).toBeGreaterThan(0);
    if (profile !== "base") expect(scenes).toEqual(expect.arrayContaining(["folkways:tavern", "folkways:cookery_pots"]));
    const selected = scenes.filter(id => scale === 3 ? id === "folkways:farming"
      : locale === "zh_cn" ? ["folkways:housing", "folkways:dining", "folkways:household"].includes(id) : true)
      .filter(id => !only || only.includes(id.replace(/^folkways:/, "")));
    test.skip(only !== undefined && selected.length === 0, `none of ${process.env.PONDER_SCENES} in this capture`);
    expect(selected.length).toBeGreaterThan(0);
    if (locale === "zh_cn" && !only) expect(selected).toEqual(expect.arrayContaining([
      "folkways:housing", "folkways:dining", "folkways:household",
    ]));
    // Preflight the named entry points before spending time on clips. Ponder screens share context;
    // opening another lesson must reset the old screen instead of retaining its active scene.
    for (const id of selected) {
      await client.run(OPEN_PONDER, { id });
      await frames(client, 5);
      expect((await client.run(READ_PONDER, { seek: -1 })).id).toBe(id);
    }
    const english = ponderCatalog("en_us");
    const translated = ponderCatalog(locale);
    for (const id of selected) {
      const name = id.split(":")[1];
      const run = new VisualRun(`${scope}-${name}`);
      run.note("declared_folders", folders);
      await client.run(OPEN_PONDER, { id });
      await frames(client, 30);
      const scene = await client.run(READ_PONDER, { seek: -1 });
      expect(scene.id).toBe(id);
      expect(scene.keyframes.length).toBeGreaterThan(0);
      await input.move(client, { x: 0, y: 0 });
      await run.clip(client, server, name, {
        subject: `Ponder ${id}: the complete lesson (${locale}, GUI scale ${scale})`,
        worldState: { id, locale, scale, total: scene.total }, seconds: Math.max(75, scene.total / 20 + 3), fps: 20,
        during: async () => {
          await client.run(OPEN_PONDER, { id });
          await frames(client, 5);
          expect((await client.run(READ_PONDER, { seek: -1 })).id).toBe(id);
        },
      });
      const keyframes = [];
      for (const [index, time] of scene.keyframes.entries()) {
        await client.run(READ_PONDER, { seek: Math.min(time + 35, scene.total - 1) });
        await frames(client, 5);
        const shown = await client.run(READ_PONDER, { seek: -1 });
        expect(shown.id).toBe(id);
        expect(shown.text.join("\n")).not.toMatch(/Format error|folkways\.ponder\./);
        const textKeys = Object.keys(translated).filter(key => key.startsWith(`folkways.ponder.${name}.text_`)
          && shown.text.includes(translated[key]));
        expect(shown.text.length, `${id} keyframe ${index + 1} must show its text`).toBeGreaterThan(0);
        expect(textKeys.length, `${id} keyframe ${index + 1} needs matching English text`).toBeGreaterThan(0);
        const filename = `${String(index + 1).padStart(2, "0")}-keyframe`;
        const frame = { keyframe: index + 1, time, capturedTime: shown.time, text: shown.text,
          english: textKeys.map(key => english[key]), textKeys };
        keyframes.push(frame);
        await run.shot(client, filename, { subject: frame.english.join("\n"),
          worldState: frame, hard: true });
        run.note("frames", keyframes);
      }
      run.expectTaken([name, ...scene.keyframes.map((_, index) => `${String(index + 1).padStart(2, "0")}-keyframe`)]);
    }
  } catch (error) {
    pair?.failing(error); throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
