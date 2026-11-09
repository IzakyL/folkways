import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, type Element } from "../ldlib2";
import { commandPos, createColonyViaBook, isColonyMember, tryCommand, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { markBox, openBookPage, setBookGesture } from "../shared/ui/zone-tools";
import { drainActionBar, readActionBar } from "../shared/ui/zone-receipt";
import { settlePanel } from "../shared/ui/site-panel";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeScreen, screenOpen, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { GROUND, around, twins, type Vec } from "../shared/harness/stage";

const BOOK_ITEM = "folkways_core:colony_book";
const OFFER = "folkways.zoneconfig.offer.folkways_core.sweep";
const STORES = "folkways.sweep.kind.folkways_vanilla.store";
const STATIONS = "folkways.sweep.kind.folkways_vanilla.station";
const TAKE = "folkways.sweep.take";

type SweepClaim = { colony: string; claim: string; owner: string; shape: string };
type SweepLedger = {
  current: string;
  colonies: Array<{ id: string; claims: number }>;
  cells: Array<{ key: string; blockEntity: boolean; claims: SweepClaim[] }>;
};

// Observation only: all enrollment in these regressions goes through the actual drawing and Take button.
const SWEEP_LEDGER = defineTask<{ player: string; cells: Vec[] }, SweepLedger>({
  name: "folkways.block-sweep.ledger",
  side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.core.colony.api.Claimed",
      "io.github.izakyl.folkways.core.world.api.WorldPos",
      "io.github.izakyl.folkways.core.front.engine.charter.CurrentColony",
      "net.minecraft.core.BlockPos",
    ],
    body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
var player=ctx.server().getPlayerList().getPlayerByName(args.string("player"));
if(player==null)throw Fail.notFound("the sweep player is offline");
var colonies=new ArrayList<Object>();
for(var colony:Colonies.all(ctx.server()))
  colonies.add(Map.of("id",colony.id().toString(),"claims",colony.claims().size()));
var cells=new ArrayList<Object>();
for(var raw:args.list("cells")){
  var cell=(Map<?,?>)raw;
  var at=new BlockPos(((Number)cell.get("x")).intValue(),((Number)cell.get("y")).intValue(),((Number)cell.get("z")).intValue());
  var held=new ArrayList<Object>();
  for(var colony:Colonies.all(ctx.server())){
    var claim=colony.claimAt(WorldPos.of(level,at)).orElse(null);
    if(claim!=null)held.add(Map.of("colony",colony.id().toString(),"claim",claim.id().toString(),
      "owner",claim.owner().toString(),"shape",claim.what() instanceof Claimed.Point?"point":claim.what() instanceof Claimed.Block?"block":"other"));
  }
  cells.add(Map.of("key",at.getX()+","+at.getY()+","+at.getZ(),"blockEntity",level.getBlockEntity(at)!=null,"claims",held));
}
return Map.of("current",CurrentColony.id(player).map(UUID::toString).orElse(""),"colonies",colonies,"cells",cells);
`,
  }),
});

const SWEEP_CLIENT_MEMBERS = defineTask<void, { colony: string; members: string[] }>({
  name: "folkways.block-sweep.client-members",
  side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "io.github.izakyl.folkways.core.front.api.Books",
      "io.github.izakyl.folkways.core.front.client.ColonyHighlightState"],
    body: `var player=Minecraft.getInstance().player;
return Map.of("colony",player==null?"":Books.heldColony(player).map(UUID::toString).orElse(""),
  "members",ColonyHighlightState.members().stream().map(at->at.getX()+","+at.getY()+","+at.getZ()).toList());`,
  }),
});

function sweepRowTexts(elements: Element[], token: string): string[] {
  const row = elements.find(element => element.id === token);
  if (!row) throw new Error(`the sweep panel has no kind row ${token}`);
  const texts: string[] = [];
  for (let at = row.index + 1; at < elements.length && elements[at].depth > row.depth; at++) {
    if (elements[at].text) texts.push(elements[at].text!);
  }
  return texts;
}

async function takeSweep(pair: E2EPair, expectedReceipt: string) {
  await drainActionBar(pair.client);
  const click = await clickElement(pair.client, { id: TAKE });
  await expect.poll(async () => {
    await tick.sprint(pair.server, 3);
    return screenOpen(await safeScreen(pair.client));
  }, { timeout: 8_000, message: "the actual Take button closes the sweep panel" }).toBe(false);
  await expect.poll(async () => {
    await tick.sprint(pair.server, 3);
    return (await readActionBar(pair.client)).text;
  }, { timeout: 8_000, message: "the server acknowledges the mixed sweep" }).toBe(expectedReceipt);
  return { click, receipt: await readActionBar(pair.client) };
}

const MIXED_SWEEPS = [
  ...twins().map(stage => ({ stage, config: stage.config, name: "composter",
    block: "minecraft:composter", owner: "folkways_vanilla:compost", title: "Composter" })),
  { stage: GROUND, config: "blockwright.toml#cookery", name: "cookery-stove",
    block: "kaleidoscope_cookery:stove", owner: "folkways_cookery:stove", title: "Stove" },
];

for (const scene of MIXED_SWEEPS) test(scene.stage.title(`a mixed ${scene.name} point and chest box enrolls both shapes without taking another colony's members`), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const { stage } = scene;
  const name = stage.name(`block-sweep-${scene.name}-mixed`);
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: any = {};

  try {
    pair = await launchPair({
      config: scene.config,
      server: { trace: tracePath("e2e", `${name}-server`), instance: seededServer(`${name}-server-${runId}`) },
      client: { trace: tracePath("e2e", `${name}-client`), instance: `${name}-client-${runId}` },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const plot = around([origin], 10, 4);
    await stage.lay(server, plot);
    await tryCommand(server, `fill ${commandPos(stage.at(plot.min))} ${commandPos(stage.at(plot.max))} minecraft:air`);
    await tryCommand(server, `fill ${commandPos(stage.at({ ...plot.min, y: origin.y - 1 }))} ${commandPos(stage.at({ ...plot.max, y: origin.y - 1 }))} minecraft:smooth_stone`);
    await tryCommand(server, `setblock ${commandPos(stage.at(origin))} minecraft:smooth_stone`);
    await tryCommand(server, `gamemode survival ${playerName}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);
    evidence.firstColony = await createColonyViaBook(server, client, playerName, origin, stage);

    const first = { x: origin.x + 2, y: origin.y, z: origin.z + 2 };
    const pointA = { x: origin.x + 3, y: origin.y, z: origin.z + 2 };
    const chestA = { x: origin.x + 4, y: origin.y, z: origin.z + 2 };
    const pointB = { x: origin.x + 5, y: origin.y, z: origin.z + 2 };
    const chestB = { x: origin.x + 6, y: origin.y, z: origin.z + 2 };
    const last = { x: origin.x + 7, y: origin.y, z: origin.z + 2 };
    const standAt = { x: origin.x + 4.5, y: origin.y, z: origin.z - 0.5 };
    const key = (at: Vec) => { const cell = stage.at(at); return `${cell.x},${cell.y},${cell.z}`; };
    const read = (cells: Vec[]) => server.run(SWEEP_LEDGER, { player: playerName, cells: cells.map(cell => stage.at(cell)) });
    const place = (at: Vec, block: string) => tryCommand(server, `setblock ${commandPos(stage.at(at))} ${block}`);
    await place(first, "minecraft:smooth_stone");
    await place(pointA, scene.block);
    await place(chestA, "minecraft:chest");
    // A furnace closes the first box: its kind is unticked, so it stays out while the point and the chest go in.
    await place(pointB, "minecraft:furnace");
    await place(last, "minecraft:smooth_stone");
    await tick.sprint(server, 5);
    const before = evidence.before = await read([pointA, chestA]);
    const firstColony = before.current;
    expect(firstColony).not.toBe("");
    expect(before.cells.map(cell => cell.blockEntity), "the point has no block entity; the chest does").toEqual([false, true]);
    expect(before.cells.map(cell => cell.claims)).toEqual([[], []]);

    const pointToken = `folkways.sweep.kind.${scene.owner.replace(":", ".")}`;
    const open = async (corner: Vec, count: string, untick?: string) => {
      await setBookGesture(server, client, playerName, "box");
      const marked = await markBox(server, client, playerName, standAt, first, corner, stage);
      expect(marked.marked.text, "solid corner blocks define the mixed box").toMatch(/marked$/);
      await openBookPage(server, client, playerName, origin, stage);
      await waitForElement(client, { id: OFFER }, { timeoutMs: 8_000 });
      await clickElement(client, { id: OFFER });
      await settlePanel(server, client);
      await waitForElement(client, { id: pointToken }, { timeoutMs: 8_000 });
      await waitForElement(client, { id: STORES }, { timeoutMs: 8_000 });
      const tree = await panelElements(client);
      const rows = { point: sweepRowTexts(tree, pointToken), chest: sweepRowTexts(tree, STORES) };
      expect(rows.point).toContain(scene.title);
      expect(rows.chest).toContain("Storage");
      expect(rows.point).toContain(count);
      expect(rows.chest).toContain(count);
      if (untick) {
        await waitForElement(client, { id: untick }, { timeoutMs: 8_000 });
        await clickElement(client, { id: untick });
        await settlePanel(server, client);
      }
      return { marked, rows };
    };

    evidence.firstOffer = await open(pointB, "1", STATIONS);
    evidence.firstTake = await takeSweep(pair, "Took in 2 blocks");
    expect(await isColonyMember(server, pointB, stage), "the furnace, its kind unticked, stays out").toBe(false);
    const enrolled = evidence.enrolled = await read([pointA, chestA]);
    expect(enrolled.cells[0].claims).toEqual([expect.objectContaining({ colony: firstColony, owner: scene.owner, shape: "point" })]);
    expect(enrolled.cells[1].claims).toEqual([expect.objectContaining({ colony: firstColony, owner: "folkways_vanilla:store", shape: "block" })]);

    // A different current colony sees the original kinds too; only the two new cells may actually be taken.
    evidence.secondColony = await createColonyViaBook(server, client, playerName, origin, stage);
    const secondColony = (await read([])).current;
    expect(secondColony).not.toBe(firstColony);
    await expect.poll(async () => {
      await tick.sprint(server, 5);
      await frames(client, 5);
      return client.run(SWEEP_CLIENT_MEMBERS);
    }, { timeout: 8_000, message: "the new colony's empty overview replaces the old member list" }).toEqual({ colony: secondColony, members: [] });
    await place(pointB, scene.block);
    await place(chestB, "minecraft:chest");
    await tick.sprint(server, 5);
    evidence.secondOffer = await open(last, "2");
    evidence.secondTake = await takeSweep(pair, "Took in 2 blocks");
    const after = evidence.after = await read([pointA, chestA, pointB, chestB]);
    expect(after.cells.slice(0, 2), "the first colony retains the exact claim identities").toEqual(enrolled.cells);
    expect(after.cells[2].claims).toEqual([expect.objectContaining({ colony: secondColony, owner: scene.owner, shape: "point" })]);
    expect(after.cells[3].claims).toEqual([expect.objectContaining({ colony: secondColony, owner: "folkways_vanilla:store", shape: "block" })]);
    expect(after.colonies.find(colony => colony.id === firstColony)?.claims).toBe(2);
    expect(after.colonies.find(colony => colony.id === secondColony)?.claims).toBe(2);

    await expect.poll(async () => {
      await tick.sprint(server, 5);
      await frames(client, 5);
      return (await client.run(SWEEP_CLIENT_MEMBERS)).members.sort();
    }, { timeout: 8_000, message: "the current colony's two new members reach the client" }).toEqual([key(pointB), key(chestB)].sort());
    evidence.repeatOffer = await open(last, "1");
    evidence.repeatTake = await takeSweep(pair, "nothing in the box can be taken in");
    evidence.repeated = await read([pointA, chestA, pointB, chestB]);
    expect(evidence.repeated, "repeating cannot duplicate ours or steal the other colony's point or chest").toEqual(after);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach(`${name}-evidence.json`, { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});
