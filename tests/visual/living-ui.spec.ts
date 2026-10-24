import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, player, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { ancestorsOf, clickElement, panelElements, visibleBox, waitForElement } from "../ldlib2";
import { foundColonyFast, isColonyMember, openColonyPanelViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { VisualRun } from "../shared/capture/visual-kit";
import { ponderCatalog, QUIET_TOASTS, setVisualLanguage } from "../shared/capture/ponder";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { tracePath } from "../shared/harness/out-paths";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";
import { clickTab } from "../shared/ui/colony-pages";
import { createMarkedZone, holdZoneAndOpen, setBookGesture } from "../shared/ui/zone-tools";
import { aimAndWorldLeftClick } from "../shared/ui/world-ui";

const PLACARDS = defineTask<void, string[][]>({
  name: "folkways.visual.bed-placards", side: "client",
  source: javaTask({ imports: ["io.github.izakyl.folkways.front.client.ColonyHighlightState",
    "io.github.izakyl.folkways.front.api.Placard", "io.github.izakyl.folkways.front.api.notice.Line"], body: `
var field = ColonyHighlightState.class.getDeclaredField("PLACARDS"); field.setAccessible(true);
var cards = (List<Placard>)field.get(null);
return cards.stream().filter(card -> card.lines().getFirst() instanceof Line.Said said
 && said.notice().key().endsWith("_bed")).map(card -> card.lines().stream().map(line -> switch(line) {
 case Line.Text text -> text.component().getString();
 case Line.Said said -> said.notice().component().getString();
 default -> line.toString();
}).toList()).toList();
` }),
});


const RESIDENT_CARD = defineTask<void, boolean>({
  name: "folkways.visual.resident-card", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft", "io.github.izakyl.folkways.front.client.ColonyLookState",
    "io.github.izakyl.folkways.front.api.notice.Line"], body: `
var minecraft = Minecraft.getInstance();
for (var card : ColonyLookState.residents()) {
 var entity = minecraft.level.getEntity(card.entityId());
 if (entity != null && entity.distanceToSqr(minecraft.player) < 64
   && entity.getBoundingBox().getCenter().subtract(minecraft.player.getEyePosition()).normalize()
      .dot(minecraft.player.getLookAngle()) > 0.9
   && card.lines().getFirst() instanceof Line.Text name && !name.after().isEmpty()) return true;
}
return false;
` }),
});
const POSE_RESIDENT = defineTask<Vec, { uuid: string; position: number[] }>({
  name: "folkways.visual.pose-resident", side: "server",
  source: javaTask({ imports: ["net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket",
    "io.github.izakyl.folkways.plugins.person.ResidentEntity"], body: `
var player = ctx.server().getPlayerList().getPlayers().getFirst();
for (var found : player.serverLevel().getAllEntities()) if (found instanceof ResidentEntity resident) {
 resident.teleportTo(Args.of(ctx).number("x"), Args.of(ctx).number("y"), Args.of(ctx).number("z"));
 resident.setYRot(0); resident.setYHeadRot(0); resident.yBodyRot = 0;
 player.connection.send(new ClientboundTeleportEntityPacket(resident));
 return Map.of("uuid", resident.getUUID().toString(), "position", List.of(resident.getX(), resident.getY(), resident.getZ()));
}
throw new IllegalStateException("resident missing");
` }),
});
const SYNC_RESIDENT_POSE = defineTask<{ uuid: string; x: number; y: number; z: number }, boolean>({
  name: "folkways.visual.sync-resident-pose", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var args = Args.of(ctx);
for (var entity : Minecraft.getInstance().level.entitiesForRendering()) {
 if (!entity.getUUID().toString().equals(args.string("uuid"))) continue;
 // Frozen entities do not consume the teleport packet's interpolation steps. Snap to the server's
 // same pose so rendering and its bounding box agree without advancing an old labour route.
 entity.moveTo(args.number("x"), args.number("y"), args.number("z"), 0, 0);
 entity.xo = entity.getX(); entity.yo = entity.getY(); entity.zo = entity.getZ();
 return true;
}
return false;
` }),
});
const DINING_CARD = defineTask<void, boolean>({
  name: "folkways.visual.dining-card", side: "client",
  source: javaTask({ imports: ["io.github.izakyl.folkways.front.client.ColonyHighlightState",
    "io.github.izakyl.folkways.front.api.notice.Line", "net.minecraft.network.chat.Component"], body: `
var field = ColonyHighlightState.class.getDeclaredField("NOTES"); field.setAccessible(true);
var notes = (Map<UUID, List<Line>>)field.get(null);
var expected = Component.translatable("folkways.dining.summary", 1, 2).getString();
return notes.values().stream().flatMap(List::stream)
 .anyMatch(line -> line instanceof Line.Text text && text.component().getString().equals(expected));
` }),
});

for (const locale of ["en_us", "zh_cn"]) test(`living UI: beds, mood, dining, elevator and household (${locale})`, async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const scope = `living-ui-${locale}`;
  const run = new VisualRun(scope);
  const runId = Date.now();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({ config: "blockwright.toml#cookery-tavern",
      server: { trace: tracePath("visual", `${scope}-server`), instance: seededOffThreadServer(`${scope}-server-${runId}`) },
      client: { trace: tracePath("visual", `${scope}-client`), instance: `${scope}-client-${runId}` } });
    const { server, client } = pair;
    await setVisualLanguage(client, locale);
    const grounded = await waitForGroundedPlayer(server);
    const who = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const { x, y, z } = origin;
    const founding = await foundColonyFast(server, client, who, { origin, residentCount: 1 });
    await screen.dismiss(client);
    const command = (value: string) => world.command(server, value);
    await command(`time set noon`);
    await command(`weather clear`);
    await server.run(defineTask<void, boolean>({
      name: "folkways.visual.flying-observer", side: "server",
      source: javaTask({ imports: ["net.minecraft.world.level.GameType"], body: `
var player = ctx.server().getPlayerList().getPlayers().getFirst();
player.setGameMode(GameType.CREATIVE);
player.getAbilities().flying = true; player.onUpdateAbilities(); return true;
` }),
    }));
    await command(`fill ${x - 16} ${y - 1} ${z - 16} ${x + 16} ${y - 1} ${z + 16} minecraft:stone_bricks`);
    // One real surveyed bedroom. Glass walls keep its furniture visible without opening the enclosure.
    await command(`fill ${x - 6} ${y} ${z - 3} ${x - 6} ${y + 4} ${z + 6} minecraft:oak_planks`);
    await command(`fill ${x + 6} ${y} ${z - 3} ${x + 6} ${y + 4} ${z + 6} minecraft:oak_planks`);
    await command(`fill ${x - 5} ${y} ${z - 3} ${x + 5} ${y + 4} ${z - 3} minecraft:oak_planks`);
    await command(`fill ${x - 5} ${y} ${z + 6} ${x + 5} ${y + 4} ${z + 6} minecraft:oak_planks`);
    await command(`fill ${x - 6} ${y + 5} ${z - 3} ${x + 6} ${y + 5} ${z + 6} minecraft:oak_planks`);
    await command(`fill ${x - 6} ${y + 1} ${z + 6} ${x + 6} ${y + 3} ${z + 6} minecraft:glass`);
    await command(`setblock ${x + 6} ${y} ${z + 3} minecraft:oak_door[half=lower,facing=east]`);
    await command(`setblock ${x + 6} ${y + 1} ${z + 3} minecraft:oak_door[half=upper,facing=east]`);
    await command(`setblock ${x + 4} ${y} ${z + 1} minecraft:chest`);
    await command(`setblock ${x + 4} ${y} ${z + 2} minecraft:potted_dandelion`);
    await command(`setblock ${x + 2} ${y} ${z + 2} minecraft:red_carpet`);
    await command(`fill ${x - 4} ${y + 4} ${z - 2} ${x + 4} ${y + 4} ${z - 2} minecraft:glowstone`);
    await command(`fill ${x - 4} ${y + 4} ${z + 4} ${x + 4} ${y + 4} ${z + 4} minecraft:glowstone`);
    const spare = { x: x + 11, y, z };
    const pulley = { x: x + 12, y, z: z + 6 };
    await command(`setblock ${spare.x} ${y} ${z} minecraft:blue_bed[part=foot,facing=east]`);
    await command(`setblock ${spare.x + 1} ${y} ${z} minecraft:blue_bed[part=head,facing=east]`);
    await command(`setblock ${pulley.x} ${y} ${pulley.z} create:elevator_pulley`);
    await runColonyTask(server, MARK_MEMBERS, { player: who, colony_id: String(founding.evidence.colonyId),
      cells: [spare, pulley].flatMap(at => [at.x, at.y, at.z]) });
    expect(await isColonyMember(server, pulley), "the elevator delegation must accept the real Create pulley").toBe(true);
    run.note("elevator", { at: pulley, delegation: "folkways:elevator", member: true });
    const catalog = ponderCatalog(locale);
    await expect.poll(async () => {
      await tick.sprint(server, 40);
      const cards = await client.run(PLACARDS);
      run.note("surveyed_placards", cards);
      const text = cards.flat().join("\n");
      return text.includes(catalog["folkways.housing.private"])
        && text.includes(catalog["folkways.housing.exposed"])
        && !text.includes(catalog["folkways.housing.pending"]);
    }, { timeout: 90_000, message: "both enrolled beds must finish their real room surveys" }).toBe(true);
    const placards = await client.run(PLACARDS);
    const bedText = placards.flat().join("\n");
    expect(bedText).toContain(catalog["folkways.housing.exposed"]);
    expect(bedText).toContain(catalog["folkways.page.living.unclaimed"]);
    expect(bedText).not.toContain(catalog["folkways.housing.pending"]);
    expect(bedText).not.toMatch(/folkways\.|Format error/);
    run.note("surveyed_placards", placards);

    const view = async (eye: Vec, target: Vec) => {
      await command(`tp ${who} ${eye.x} ${eye.y - 1.62} ${eye.z} ${cameraLookingAt(eye, target).yaw} ${cameraLookingAt(eye, target).pitch}`);
      await tick.sprint(server, 8);
      await new Promise(resolve => setTimeout(resolve, 600));
      await frames(client, 5);
    };
    const shot = async (name: string, subject: string) => {
      await client.run(QUIET_TOASTS);
      await input.move(client, { x: 0, y: 0 });
      await run.shot(client, name, { subject, worldState: { origin, locale }, hard: true });
    };
    await command(`tp @e[type=folkways:resident,limit=1] ${x - 10} ${y} ${z + 3}`);
    await view({ x: x + 1, y: y + 1.62, z: z + 5.5 }, { x: x + 1, y: y + 1.4, z: z + 0.5 });
    await shot("01-claimed-private-bed", "Held book: claimed bed, resident name, private bedroom grade and improvement advice");
    await view({ x: spare.x + 0.5, y: y + 1.62, z: z + 5 }, { x: spare.x + 0.8, y: y + 0.8, z: z + 0.5 });
    await shot("02-unclaimed-exposed-bed", "Held book: unclaimed bed outside, exposed grade and shelter advice");
    await command(`tp @e[type=folkways:resident,limit=1] ${x - 10} ${y} ${z + 3}`);
    await view({ x: x - 10, y: y + 1.62, z: z + 8 }, { x: x - 10, y: y + 1.5, z: z + 3 });
    // Pose after the observer settles, while simulation is frozen, so an old labour route cannot
    // walk the subject off camera. Send its teleport immediately rather than advancing that route.
    const pose = await server.run(POSE_RESIDENT, { x: x - 9.5, y, z: z + 3.5 });
    run.note("resident_card_pose", pose);
    expect(await client.run(SYNC_RESIDENT_POSE, { uuid: pose.uuid, x: pose.position[0], y: pose.position[1], z: pose.position[2] })).toBe(true);
    await new Promise(resolve => setTimeout(resolve, 600));
    await expect.poll(() => client.run(RESIDENT_CARD), { timeout: 15_000 }).toBe(true);
    await shot("03-resident-mood-card", "Resident world card: mood glyph after the name, fullness and rest gauges");

    const boxes = [
      { kind: "dining", min: { x: x + 3, y: y - 1, z: z + 9 }, max: { x: x + 8, y, z: z + 13 } },
    ] as const;
    await command(`setblock ${x + 5} ${y} ${z + 11} minecraft:oak_slab[type=top]`);
    await command(`setblock ${x + 5} ${y} ${z + 10} minecraft:oak_stairs[facing=south]`);
    await command(`setblock ${x + 5} ${y} ${z + 12} minecraft:oak_stairs[facing=north]`);
    for (const [index, box] of boxes.entries()) {
      await command(`setblock ${box.max.x} ${box.max.y} ${box.max.z} minecraft:stone`);
      await setBookGesture(server, client, who, "box");
      await aimAndWorldLeftClick(server, client, who, box.min);
      await aimAndWorldLeftClick(server, client, who, box.max);
      await createMarkedZone(server, client, who, founding.standBlock, box.kind);
      await command(`setblock ${box.max.x} ${box.max.y} ${box.max.z} minecraft:air`);
      await tick.sprint(server, 160);
      const center = { x: (box.min.x + box.max.x) / 2 + 0.5, y: y + 0.5, z: z + 11.5 };
      await view({ x: center.x, y: y + 3.5, z: z + 18 }, center);
      if (box.kind === "dining") {
        await expect.poll(async () => {
          await tick.sprint(server, 20);
          return client.run(DINING_CARD);
        }, { timeout: 15_000 }).toBe(true);
      }
      await shot(`0${4 + index * 2}-${box.kind}-card`, `${box.kind} area world card and outlined furniture${box.kind === "dining" ? ": table and chair counts" : ""}`);
      if (box.kind === "dining") {
        await holdZoneAndOpen(server, client, who, box);
        for (const key of ["food", "drink", "servings"]) await waitForElement(client, { id: `folkways.zoneconfig.value.${key}` });
      } else {
        await player.aim(server, box.min, { player: who, face: "up", from: { x: box.min.x + 0.5, y, z: box.min.z + 0.5 } });
        await player.click(client, { button: "right" });
        await waitForElement(client, { id: "folkways.selection.back" });
      }
      await frames(client, 8);
      await shot(`0${5 + index * 2}-${box.kind}-panel`, `${box.kind} zone panel${box.kind === "dining" ? ": food, drink and servings settings" : ": purpose and remove action"}`);
      await panelFits(client, run, box.kind);
      await screen.dismiss(client);
    }
    await view({ x: pulley.x + 0.5, y: y + 1.62, z: pulley.z + 4 }, { x: pulley.x + 0.5, y: y + 0.5, z: pulley.z + 0.5 });
    await shot("06-elevator-delegation", "Create elevator pulley delegated to the colony with the book");
    await openColonyPanelViaBook(server, client, who, founding.standBlock);
    await clickTab(client, "folkways.page.person");
    await frames(client, 8);
    await shot("07-household-board", "Household Board: needs, food policy, safety setting, food list and free beds");
    await panelFits(client, run, "household");
    const board = await panelElements(client);
    const caption = (key: string) => board.find(element => element.text === catalog[key]
      && visibleBox(board, element)
      && [element, ...ancestorsOf(board, element)].every(parent => parent.displayed && parent.visible));
    const policy = caption("folkways.settings.food_mode");
    const safety = caption("folkways.settings.food_safety");
    expect(policy, "food policy caption must remain visible").toBeDefined();
    expect(safety, "food safety caption must remain visible").toBeDefined();
    expect(safety!.y, "food policy and safety controls need separate rows").toBeGreaterThanOrEqual(policy!.y + policy!.height + 1);
    run.expectTaken(["01-claimed-private-bed", "02-unclaimed-exposed-bed", "03-resident-mood-card", "04-dining-card",
      "05-dining-panel", "06-elevator-delegation", "07-household-board"]);
  } catch (error) {
    pair?.failing(error); throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function panelFits(client: E2EPair["client"], run: VisualRun, label: string) {
  const width = await client.run(defineTask<void, number>({
    name: "folkways.visual.logical-width", side: "client",
    source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body:
      "return Minecraft.getInstance().getWindow().getGuiScaledWidth();" }),
  }));
  const all = await panelElements(client);
  run.note(`${label}_elements`, all);
  const visible = all.filter(element => visibleBox(all, element)
    && [element, ...ancestorsOf(all, element)].every(parent => parent.displayed && parent.visible));
  for (const element of visible) {
    expect(element.text ?? "").not.toMatch(/Format error|folkways\.(settings|dining|housing)\./);
    expect(element.x, element.id ?? element.text).toBeGreaterThanOrEqual(-1);
    expect(element.x + element.width, element.id ?? element.text).toBeLessThanOrEqual(width + 1);
  }
}
