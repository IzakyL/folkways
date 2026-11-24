import { expect, test } from "@playwright/test";
import { seedInstance } from "@izakyl/blockwright-client";
import { defineTask, input, javaTask, screen, world } from "@izakyl/blockwright-minecraft";
import { createServer } from "node:http";
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { clickElement, panelElements, typeIntoElement, waitForElement } from "../ldlib2";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { markSeeded, seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { ensureOut, outAbs, REPO_ROOT, tracePath } from "../shared/harness/out-paths";
import { newRunId } from "../shared/harness/run-id";
import { foundColonyFast, openColonyPanelViaBook } from "../shared/colony/colony-founding";

import { DRAWING_GHOSTS } from "../shared/ui/assistant-ghosts";

const DESK_BOXES = defineTask<void, Array<{ x: number; y: number; width: number; height: number }>>({
  name: "folkways.assistant.desk-boxes", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft",
    "com.lowdragmc.lowdraglib2.gui.ui.ModularUI", "io.github.izakyl.folkways.core.front.ui.screen.Desk"], body: `
var ui = ModularUI.of(Minecraft.getInstance().screen);
return Desk.occupied(ui).stream().map(box -> Map.of("x", box.x(), "y", box.y(),
    "width", box.width(), "height", box.height())).toList();
` }),
});

const PLAYER_CAMERA_RESTORED = defineTask<void, boolean>({
  name: "folkways.assistant.camera-restored", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var client = Minecraft.getInstance();
return client.getCameraEntity() == client.player && client.options.getCameraType().isFirstPerson();
` }),
});

type Request = { tools: Array<{ function: { name: string } }>; messages: Array<{
  role: string; content: string | Array<{ type: string; image_url?: { url: string } }>;
}> };

function images(request: Request): string[] {
  return request.messages.flatMap(message => Array.isArray(message.content)
    ? message.content.filter(block => block.type === "image_url").map(block => block.image_url!.url) : []);
}

function jpegSize(url: string): [number, number] {
  expect(url.startsWith("data:image/jpeg;base64,")).toBe(true);
  const bytes = Buffer.from(url.split(",")[1], "base64");
  expect(bytes.readUInt16BE(0)).toBe(0xffd8);
  for (let offset = 2; offset < bytes.length;) {
    const marker = bytes.readUInt16BE(offset);
    offset += 2;
    if (marker === 0xffc0 || marker === 0xffc2) {
      return [bytes.readUInt16BE(offset + 5), bytes.readUInt16BE(offset + 3)];
    }
    offset += bytes.readUInt16BE(offset);
  }
  throw new Error("JPEG has no frame dimensions");
}

test("local assistant uses a bounded camera and reads the captured player view", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  ensureOut("e2e");
  const requests: Request[] = [];
  let poses: object[] = [];
  let buildScript: string | undefined;
  const provider = createServer(async (request, response) => {
    const chunks: Buffer[] = [];
    for await (const chunk of request) {
      chunks.push(Buffer.from(chunk));
    }
    requests.push(JSON.parse(Buffer.concat(chunks).toString("utf8")));
    const count = requests.length;
    const capture = count <= poses.length;
    const delta = capture ? { tool_calls: [{ index: 0, id: `shot${count}`, type: "function",
      function: { name: "run", arguments: JSON.stringify({
        script: (count === 1 ? 'print(get("client/view"))\n' : "")
          + `capture(camera=${JSON.stringify(poses[count - 1])})`,
      }) } }] }
      : buildScript ? { tool_calls: [{ index: 0, id: `build${count}`, type: "function",
        function: { name: "run", arguments: JSON.stringify({ script: buildScript }) } }] }
        : { content: "I inspected the scene." };
    const toolCall = "tool_calls" in delta;
    buildScript = undefined;
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    response.end(`data: ${JSON.stringify({ choices: [{ index: 0, delta, finish_reason: null }] })}\n\n`
      + `data: ${JSON.stringify({ choices: [{ index: 0, delta: {},
        finish_reason: toolCall ? "tool_calls" : "stop" }] })}\n\ndata: [DONE]\n\n`);
  });
  await new Promise<void>(resolve => provider.listen(0, "127.0.0.1", resolve));
  const address = provider.address();
  if (!address || typeof address === "string") {
    throw new Error("Missing loopback server address");
  }
  const run = newRunId();
  const clientName = `assistant-shot-client-${run}`;
  const seed = seedInstance(clientName, { cwd: REPO_ROOT });
  seed.write("config/folkways-assistant.json", JSON.stringify({
    entries: [{
      id: "local-vision", provider: "openai-compatible",
      baseUrl: `http://127.0.0.1:${address.port}/v1`, model: "local-vision", imageInput: true,
    }],
    selectedEntryId: "local-vision",
    autoApprove: [],
  }));
  const credentials = seed.write("config/jpi-mini/credentials.json",
    JSON.stringify({ "folkways_assistant.entry.local-vision": { apiKey: "local-test-key" } }));
  markSeeded(clientName);
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#assistant-screenshot",
      server: { instance: seededServer(`assistant-shot-server-${run}`),
        trace: tracePath("e2e", "assistant-shot-server") },
      client: { instance: clientName, trace: tracePath("e2e", "assistant-shot-client") },
    });
    const { client, server } = pair;
    const player = (await world.players(server))[0];
    const x = Math.floor(player.position.x);
    const y = Math.floor(player.position.y) + 3;
    const z = Math.floor(player.position.z);
    await world.command(server, `fill ${x - 8} ${y - 4} ${z - 10} ${x + 8} ${y + 8} ${z + 8} minecraft:air`);
    await world.command(server, `fill ${x - 8} ${y - 3} ${z - 10} ${x + 8} ${y - 3} ${z + 8} minecraft:stone`);
    await world.command(server, `fill ${x - 5} ${y - 2} ${z + 8} ${x + 5} ${y + 6} ${z + 8} minecraft:red_concrete`);
    await world.command(server, `setblock ${x + 2} ${y} ${z} minecraft:stone`);
    await world.command(server, `tp ${player.name} ${x + 0.5} ${y - 2} ${z - 2.5} 180 0`);
    poses = [
      { position: [x + 0.5, y + 0.5, z + 0.5], look_at: [x + 0.5, y + 0.5, z + 8] },
      { position: [x - 1.5, y + 0.5, z + 0.5], yaw: 0, pitch: 0, fov: 60 },
      { position: [x + 1.5, y + 1.5, z + 0.5], look_at: [x + 1.5, y + 1.5, z + 8] },
      { position: [x + 10000, y, z] },
      { position: [x + 2.5, y + 0.5, z + 0.5] },
    ];
    await screen.dismiss(client);
    await input.key(client, { keybind: "key.folkways.assistant" });
    await waitForElement(client, { id: "folkways.assistant.input" });
    await typeIntoElement(client, { id: "folkways.assistant.input" }, "Inspect behind me.", { submit: true });
    await expect.poll(() => requests.length, { timeout: 60_000 }).toBe(6);
    expect(requests[0].tools.map(tool => tool.function.name)).toEqual(["run"]);
    expect(requests[0].messages.find(message => message.role === "user")?.content).toBe("Inspect behind me.");
    expect(JSON.stringify(requests[1].messages)).toMatch(/dimension.*minecraft:overworld.*eye.*yaw.*pitch.*facing.*target/);
    for (let index = 0; index < 3; index++) {
      const url = images(requests[index + 1]).at(-1)!;
      const [width, height] = jpegSize(url);
      expect(Math.max(width, height)).toBe(1024);
      const bytes = Buffer.from(url.split(",")[1], "base64");
      writeFileSync(testInfo.outputPath(`camera-${index + 1}.jpg`), bytes);
      if (process.env.FOLKWAYS_ASSISTANT_SHOTS) {
        mkdirSync(process.env.FOLKWAYS_ASSISTANT_SHOTS, { recursive: true });
        writeFileSync(`${process.env.FOLKWAYS_ASSISTANT_SHOTS}/camera-${index + 1}.jpg`, bytes);
      }
      const crop = `crop=${Math.floor(width / 4)}:${Math.floor(height / 4)}:`
        + `${Math.floor(width * 3 / 8)}:${Math.floor(height * 3 / 8)}`;
      const rgb = execFileSync("ffmpeg", ["-loglevel", "error", "-f", "image2pipe", "-i", "pipe:0",
        "-vf", crop,
        "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"], { input: bytes, maxBuffer: 4_000_000 });
      let red = 0;
      for (let i = 0; i < rgb.length; i += 3) {
        if (rgb[i] > rgb[i + 1] * 1.5 && rgb[i] > rgb[i + 2] * 1.5 && rgb[i] > 70) { red++; }
      }
      expect(red / (rgb.length / 3)).toBeGreaterThan(0.7);
      expect(JSON.stringify(requests[index + 1].messages)).toContain("Camera: dimension=");
    }
    expect(JSON.stringify(requests[1].messages)).toContain(
      `position=(${x + 0.5},${y + 0.5},${z + 0.5})`);
    expect(await client.run(PLAYER_CAMERA_RESTORED, undefined)).toBe(true);
    expect(JSON.stringify(requests[4].messages)).toContain("camera_out_of_range");
    expect(JSON.stringify(requests[5].messages)).toContain("camera_inside_solid");
    expect(JSON.stringify(requests[5].messages)).toContain("[earlier screenshot omitted]");
    await expect.poll(async () => (await panelElements(client)).find(element =>
      element.id === "folkways.assistant.status")?.text).toBe("Ready");
    await clickElement(client, { id: "folkways.assistant.settings" });
    await clickElement(client, { id: "folkways.assistant.image_input" }, { scrollIntoView: true });
    await clickElement(client, { id: "folkways.assistant.save" }, { scrollIntoView: true });
    await expect.poll(async () => (await panelElements(client)).some(element =>
      element.text?.startsWith("Settings saved."))).toBe(true);
    await clickElement(client, { id: "folkways.window.close.folkways.assistant.preferences" });
    poses = [];
    await typeIntoElement(client, { id: "folkways.assistant.input" }, "Hello.", { submit: true });
    await expect.poll(() => requests.length, { timeout: 30_000 }).toBe(7);
    expect(requests[6].tools.map(tool => tool.function.name)).toEqual(["run"]);
    await clickElement(client, { id: "folkways.window.close.folkways.assistant" });
    const origin = { x: x + 4, y: y + 3, z: z + 4 };
    await world.command(server, `fill ${origin.x - 20} ${origin.y - 1} ${origin.z - 20} `
      + `${origin.x + 20} ${origin.y - 1} ${origin.z + 20} minecraft:stone`);
    const colony = await foundColonyFast(server, client, player.name, { origin, residentCount: 2 });
    await openColonyPanelViaBook(server, client, player.name, colony.standBlock);
    await waitForElement(client, { id: "folkways.assistant.launcher" });
    const launcher = (await panelElements(client)).find(element => element.id === "folkways.assistant.launcher")!;
    const boxes = await client.run(DESK_BOXES, undefined);
    expect(boxes.some(box => box.x === Math.round(launcher.x) && box.y === Math.round(launcher.y)
      && box.width === Math.round(launcher.width) && box.height === Math.round(launcher.height))).toBe(true);
    await clickElement(client, { id: "folkways.assistant.launcher" });
    await waitForElement(client, { id: "folkways.assistant.input" });
    expect((await panelElements(client)).some(element => element.id === "folkways.window.colony"
      && element.displayed)).toBe(true);

    const railway = (row: number) => {
      const points = [{ x: origin.x - 6, y: origin.y + 2, z: row },
        { x: origin.x + 6, y: origin.y + 2, z: row }];
      return `set("builds/railway-${row}", {"pattern": "folkways_vanilla:railway", "dimension": "minecraft:overworld", "points": ${JSON.stringify(points)}})`;
    };
    const firstRow = origin.z - 4;
    const secondRow = origin.z + 4;

    // Framing a staged railway holds its review and returns a smaller image per view, each showing the draft.
    const views = ["north", "southeast", "south", "top"];
    await clickElement(client, { id: "folkways.assistant.settings" });
    await clickElement(client, { id: "folkways.assistant.image_input" }, { scrollIntoView: true });
    await clickElement(client, { id: "folkways.assistant.save" }, { scrollIntoView: true });
    await expect.poll(async () => (await panelElements(client)).some(element =>
      element.text?.startsWith("Settings saved."))).toBe(true);
    await clickElement(client, { id: "folkways.window.close.folkways.assistant.preferences" });
    const ask = async (script: string, prompt: string) => {
      const before = requests.length;
      buildScript = script;
      await typeIntoElement(client, { id: "folkways.assistant.input" }, prompt, { submit: true });
      await expect.poll(() => requests.length, { timeout: 60_000 }).toBe(before + 2);
      await expect.poll(async () => (await panelElements(client)).find(element =>
        element.id === "folkways.assistant.status")?.text, { timeout: 30_000 }).toBe("Ready");
      const reply = requests[before + 1];
      const shots = images(reply).slice(-views.length);
      expect(shots, JSON.stringify(reply.messages.slice(-2)).slice(0, 2000)).toHaveLength(views.length);
      for (const shot of shots) {
        expect(Math.max(...jpegSize(shot))).toBe(768);
      }
      return { text: JSON.stringify(reply.messages.slice(-2)), shots };
    };
    const held = await ask(`${railway(firstRow)}\ncapture(around="changes", views=${JSON.stringify(views)})`,
      "Check the railway draft.");
    expect(held.text).toContain('\\"held\\":true');
    expect((await panelElements(client)).some(element => element.id === "folkways.assistant.confirm")).toBe(false);
    expect(await client.run(DRAWING_GHOSTS, undefined)).toMatchObject({ count: 0 });
    expect(await client.run(PLAYER_CAMERA_RESTORED, undefined)).toBe(true);
    const framed = [...held.text.matchAll(
      /position=\(([-\d.E]+),([-\d.E]+),([-\d.E]+)\) yaw=([-\d.E]+) pitch=([-\d.E]+) fov=[\d.]+ view=(\w+)/g)];
    expect(framed.map(pose => pose[6])).toEqual(views);
    const plain = await ask(framed.map(pose => `capture(position=[${pose[1]}, ${pose[2]}, ${pose[3]}], `
      + `yaw=${pose[4]}, pitch=${pose[5]})`).join("\n"), "Look at the same spots again.");
    const shotDir = outAbs("e2e", "shots", "assistant-vision");
    mkdirSync(shotDir, { recursive: true });
    for (let index = 0; index < views.length; index++) {
      const staged = Buffer.from(held.shots[index].split(",")[1], "base64");
      const empty = Buffer.from(plain.shots[index].split(",")[1], "base64");
      writeFileSync(`${shotDir}/railway-draft-${views[index]}.jpg`, staged);
      writeFileSync(`${shotDir}/railway-site-${views[index]}.jpg`, empty);
      const [a, b] = [staged, empty].map(bytes => execFileSync("ffmpeg", ["-loglevel", "error", "-f",
        "image2pipe", "-i", "pipe:0", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"],
      { input: bytes, maxBuffer: 8_000_000 }));
      let changed = 0;
      for (let i = 0; i < a.length; i += 3) {
        if (Math.max(Math.abs(a[i] - b[i]), Math.abs(a[i + 1] - b[i + 1]), Math.abs(a[i + 2] - b[i + 2])) > 48) {
          changed++;
        }
      }
      const share = changed / (a.length / 3);
      testInfo.annotations.push({ type: "draft-pixels", description: `${views[index]}: ${share.toFixed(4)}` });
      expect(share, `${views[index]} view shows the staged railway`).toBeGreaterThan(0.005);
    }

    for (const decision of ["confirm", "reject", "stop"]) {
      buildScript = [railway(firstRow), railway(secondRow)].join("\n");
      await typeIntoElement(client, { id: "folkways.assistant.input" }, `Stage two railways (${decision}).`,
        { submit: true });
      await waitForElement(client, { id: "folkways.assistant.confirm" }, { timeoutMs: 30_000 }).catch(error => {
        const result = requests.at(-1)?.messages.filter(message => message.role === "tool").at(-1)?.content;
        throw new Error(`No build confirmation for ${decision}. Last tool result: ${JSON.stringify(result)}`,
          { cause: error });
      });
      const ghosts = await client.run(DRAWING_GHOSTS, undefined);
      expect(ghosts.count).toBeGreaterThan(0);
      expect(ghosts.rows).toEqual(expect.arrayContaining([firstRow, secondRow]));
      const visibleReviews = async () => (await panelElements(client)).filter(element =>
        element.id === "folkways.assistant.confirm" && element.displayed && element.width > 0).length;
      await expect.poll(visibleReviews).toBe(1);
      await clickElement(client, { id: `folkways.assistant.${decision}` });
      if (decision !== "stop") {
        await expect.poll(async () => (await client.run(DRAWING_GHOSTS, undefined)).rows
          .filter(row => row === firstRow || row === secondRow)).toHaveLength(1);
        await expect.poll(visibleReviews).toBe(1);
        await clickElement(client, { id: `folkways.assistant.${decision}` });
      }
      await expect.poll(async () => (await client.run(DRAWING_GHOSTS, undefined)).count).toBe(0);
      await expect.poll(async () => (await panelElements(client)).find(element =>
        element.id === "folkways.assistant.status")?.text, { timeout: 30_000 }).toBe("Ready");
    }

  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    rmSync(credentials, { force: true });
    await pair?.teardown(testInfo);
    await new Promise<void>((resolve, reject) => provider.close(error => error ? reject(error) : resolve()));
  }
});
