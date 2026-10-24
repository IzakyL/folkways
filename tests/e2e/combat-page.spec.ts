import { expect, test, type TestInfo } from "@playwright/test";
import { defineTask, javaTask, media, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements, scrollElementIntoView } from "../ldlib2";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { clickTab, discoverPages } from "../shared/ui/colony-pages";
import { frames } from "../shared/harness/bw-helpers";
import { mustCommand } from "../shared/ui/world-ui";
import { setVisualLanguage } from "../shared/capture/ponder";

type Vec = { x: number; y: number; z: number };
type Scene = {
  colony: string;
  player: string;
  residents: string[];
  origin: Vec;
};
type ResidentReading = {
  id: string;
  present: boolean;
  x: number;
  y: number;
  z: number;
  order: string;
  orderMode: string;
  orderStatus: string;
  destination?: Vec;
};
type Reading = {
  colony: string;
  group: string;
  groupMembers: string[];
  groupPhase: string;
  routeStops: number;
  patrolStop: number;
  loadouts: string[];
  player: Vec;
  residents: ResidentReading[];
};

const COMBAT_PAGE = "folkways.combat";
const COMBAT_TAB = "folkways.page.combat";
const CONTROL = "folkways.combat.";

test("the combat page creates a real GUARD, releases it, and executes its two-point patrol", async ({}, testInfo) => {
  test.setTimeout(420_000);
  let pair: E2EPair | undefined;
  let scene: Scene | undefined;
  let panelOpen = false;
  const evidence: Array<{ step: string; reading?: Reading; ui?: unknown }> = [];

  try {
    const run = newRunId();
    pair = await launchPair({
      server: { instance: `combat-page-server-${run}` },
      client: { instance: `combat-page-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident"] },
    });
    const { server, client } = pair;
    await setVisualLanguage(client, "zh_cn");
    const player = await waitForGroundedPlayer(server);
    const origin: Vec = {
      x: Math.round(player.pos.x) + 4,
      y: Math.round(player.pos.y),
      z: Math.round(player.pos.z) + 4,
    };
    await mustCommand(server, `gamemode survival ${player.name}`);
    await mustCommand(server, "difficulty peaceful");
    await mustCommand(server, "gamerule doMobSpawning false");
    await mustCommand(server, `fill ${origin.x - 8} ${origin.y - 1} ${origin.z - 5} ${origin.x + 10} ${origin.y - 1} ${origin.z + 14} minecraft:smooth_stone`);
    await world.command(server, `fill ${origin.x - 8} ${origin.y} ${origin.z - 5} ${origin.x + 10} ${origin.y + 5} ${origin.z + 14} minecraft:air`, { allowFailure: true });
    const founding = await foundColonyFast(server, client, player.name, { origin, residentCount: 2 });
    const residents = await server.run(RESIDENTS, { colony: String(founding.evidence.colonyId) });
    expect(residents.length, "the fixture must provide two loaded, colony-registered residents").toBe(2);
    scene = { colony: String(founding.evidence.colonyId), player: player.name, residents, origin };

    await openColonyPanelViaBook(server, client, player.name, founding.standBlock);
    panelOpen = true;
    const pages = await discoverPages(server, player.name);
    evidence.push({ step: "runtime page registry", ui: pages });
    expect(pages.some(page => page.name === COMBAT_PAGE), "the colony must register the dedicated combat page")
      .toBe(true);
    expect(pages.find(page => page.name === COMBAT_PAGE)?.token).toBe("folkways.page.combat");
    await clickTab(client, COMBAT_TAB);
    await expect.poll(async () => (await panelElements(client)).some(element => element.id === `${CONTROL}summary`), {
      timeout: 10_000,
      message: "the real combat page must render after clicking its discovered navigation tab",
    }).toBe(true);
    const initialUi = await panelElements(client);
    evidence.push({ step: "combat page opened", ui: initialUi.filter(element => element.id?.startsWith(CONTROL) || element.id === COMBAT_TAB) });
    await capture(client, testInfo.outputPath("combat-page-open.png"), testInfo, "combat-page-open.png");
    expect(initialUi.find(element => element.id === `${CONTROL}summary`)?.text?.trim().length ?? 0,
      "the page must render its localized state summary").toBeGreaterThan(0);
    expect(descendantsText(initialUi, COMBAT_TAB), "the visible Chinese navigation label must be localized")
      .toContain("战斗");

    // Pick two actual residents in the page, then create and select the real colony-owned group there.
    for (const slot of [0, 1]) {
      const token = `${CONTROL}resident.${slot}`;
      const row = `${CONTROL}resident_row.${slot}`;
      expect(initialUi.some(element => element.id === token && element.displayed && element.active),
        `${token} must be a real actionable resident selection`).toBe(true);
      await scrollElementIntoView(client, { id: row });
      await clickElement(client, { id: token });
    }
    await clickElement(client, { id: `${CONTROL}create_group` }, { scrollIntoView: true });
    await expect.poll(async () => (await panelElements(client)).some(element => element.id === `${CONTROL}group.0`), {
      timeout: 10_000,
      message: "creating a group from the selected residents must expose its real group row",
    }).toBe(true);
    await scrollElementIntoView(client, { id: `${CONTROL}group_row.0` });
    await clickElement(client, { id: `${CONTROL}group.0` });
    let reading = await sample(server, scene, "group selected");
    evidence.push({ step: "group selected from UI", reading });
    expect(reading.group, "both selected residents must now belong to a colony-owned group").not.toBe("");
    expect(reading.groupMembers).toEqual(expect.arrayContaining(residents));
    expect(reading.groupMembers).toHaveLength(2);

    const guardPoint = await playerPosition(server, player.name);
    evidence.push({ step: "player footing before UI-added guard point", ui: guardPoint });
    await clickElement(client, { id: `${CONTROL}add_point` }, { scrollIntoView: true });
    await expect.poll(async () => (await panelElements(client)).find(element => element.id === `${CONTROL}points`)?.text ?? "", {
      timeout: 10_000,
      message: "adding one point must update the combat page's visible point summary",
    }).toMatch(/1|一/);
    const onePointUi = await panelElements(client);
    expect(onePointUi.find(element => element.id === `${CONTROL}points`)?.text).toContain("站岗点");
    evidence.push({ step: "one UI-added guard point", ui: onePointUi.filter(element => element.id?.startsWith(CONTROL)) });
    await capture(client, testInfo.outputPath("combat-page-one-point.png"), testInfo, "combat-page-one-point.png");

    await clickElement(client, { id: `${CONTROL}execute` });
    reading = await waitFor(server, scene, "one-point GUARD formation reaches its assigned slots", state =>
      state.groupMembers.length === 2 && state.residents.length === 2
        && state.residents.every(resident => resident.present && resident.orderMode === "GUARD"
          && resident.orderStatus === "HOLDING" && resident.destination !== undefined
          && distance(resident, resident.destination) <= 1.25), 500);
    evidence.push({ step: "server confirms real GUARD and physical return", reading });
    expect(reading.groupPhase).not.toBe("FAILED");
    expect(reading.residents.every(resident => resident.destination !== undefined
      && distance(resident.destination, guardPoint) <= 3.5), "the UI-created slots must remain around its one-point player footing")
      .toBe(true);

    // Release is a page action. The check reads the actual command registry and group service afterward.
    await clickElement(client, { id: `${CONTROL}release` });
    reading = await waitFor(server, scene, "UI release removes every actual resident order", state =>
      state.residents.every(resident => resident.order === "NONE") && state.groupPhase === "IDLE", 80, 4);
    evidence.push({ step: "UI released actual GUARD handles", reading });
    expect(reading.residents.every(resident => resident.orderMode === "NONE")).toBe(true);

    // Keep the first point in the player's saved draft. Move the player through the server to a safe floor cell;
    // the second point itself is still added only by clicking the UI control.
    const secondPoint: Vec = { x: origin.x + 7, y: origin.y, z: origin.z + 10 };
    await mustCommand(server, `tp ${player.name} ${secondPoint.x + 0.5} ${secondPoint.y} ${secondPoint.z + 0.5} 180 0`);
    await tick.sprint(server, 2);
    const secondFooting = await playerPosition(server, player.name);
    evidence.push({ step: "player footing before second UI-added point", ui: secondFooting });
    await clickElement(client, { id: `${CONTROL}add_point` }, { scrollIntoView: true });
    const twoPointUi = await panelElements(client);
    const pointText = twoPointUi.find(element => element.id === `${CONTROL}points`)?.text ?? "";
    const summaryText = twoPointUi.filter(element => element.id?.startsWith(CONTROL))
      .map(element => element.text ?? "").join(" ");
    evidence.push({ step: "two-point patrol preview", ui: twoPointUi.filter(element => element.id?.startsWith(CONTROL)) });
    expect(pointText, "the page must visibly show two stored route points").toMatch(/2|二/);
    expect(pointText).toContain("循环巡逻");
    expect(summaryText, "the page must identify the two-point mode as patrol in the active locale")
      .toMatch(/patrol|巡逻|巡邏|巡迴|巡回/i);
    await capture(client, testInfo.outputPath("combat-page-two-point-patrol.png"), testInfo, "combat-page-two-point-patrol.png");

    await clickElement(client, { id: `${CONTROL}execute` });
    reading = await waitFor(server, scene, "UI execution creates a real two-stop patrol", state =>
      state.routeStops === 2 && state.groupPhase !== "IDLE" && state.groupPhase !== "FAILED"
        && state.residents.length === 2 && state.residents.every(resident => resident.orderMode === "GUARD"
          && resident.orderStatus !== "FAILED"), 160, 4);
    expect(reading.patrolStop).toBeGreaterThanOrEqual(0);
    expect(reading.patrolStop).toBeLessThan(2);
    const patrolProgress = await waitFor(server, scene, "the real controller advances to its second point", state =>
      state.routeStops === 2 && state.patrolStop === 1 && state.groupPhase !== "FAILED", 600, 4);
    evidence.push({ step: "server confirms real patrol controller advanced stops", reading: patrolProgress });
    await capture(client, testInfo.outputPath("combat-page-patrol-executing.png"), testInfo, "combat-page-patrol-executing.png");
    await clickElement(client, { id: `${CONTROL}release` });
    reading = await waitFor(server, scene, "UI release stops the patrol controller", state =>
      state.groupPhase === "IDLE" && state.residents.every(resident => resident.order === "NONE"), 80, 4);
    evidence.push({ step: "UI released actual patrol orders", reading });

    const selectedGroup = reading.group;
    await clickElement(client, { id: `${CONTROL}retreat` });
    reading = await waitFor(server, scene, "UI retreat issues real RETREAT orders and both residents reach the player", state =>
      state.group === selectedGroup && state.groupMembers.length === 2 && state.residents.length === 2
        && state.residents.every(resident => resident.present && resident.orderMode === "RETREAT"
          && resident.orderStatus === "HOLDING" && resident.destination !== undefined
          && distance(resident, resident.destination) <= 1.25), 600, 4);
    evidence.push({ step: "UI retreat reached its actual two-resident destination", reading });
    expect(reading.group).toBe(selectedGroup);
    await clickElement(client, { id: `${CONTROL}release` });
    reading = await waitFor(server, scene, "UI release clears the retreat handles while preserving the actual group", state =>
      state.group === selectedGroup && state.groupPhase === "IDLE"
        && state.residents.every(resident => resident.order === "NONE"), 80, 4);
    evidence.push({ step: "UI released actual retreat orders", reading });
    expect(reading.residents.every(resident => resident.orderMode === "NONE")).toBe(true);

    await scrollElementIntoView(client, { id: `${CONTROL}loadout_bow` });
    await clickElement(client, { id: `${CONTROL}loadout_bow` });
    reading = await waitFor(server, scene, "UI bow preference is persisted for both actual group members", state =>
      state.group === selectedGroup && state.loadouts.length === 2 && state.loadouts.every(loadout => loadout === "BOW"), 80, 4);
    await expect.poll(async () => (await panelElements(client)).find(element => element.id === `${CONTROL}preference`)?.text ?? "", {
      timeout: 10_000,
      message: "the real page must reflect the server-persisted bow preference",
    }).toContain("弓");
    const bowPreferenceUi = await panelElements(client);
    evidence.push({ step: "UI bow loadout preference persisted without issuing equipment", reading, ui: bowPreferenceUi.filter(element => element.id?.startsWith(CONTROL)) });

    // Capture the same real page in English after the Chinese interaction run, without replaying any action.
    await setVisualLanguage(client, "en_us");
    await waitForLanguageReload(client, "en_us");
    await screen.dismiss(client);
    panelOpen = false;
    await openColonyPanelViaBook(server, client, player.name, founding.standBlock);
    panelOpen = true;
    await clickTab(client, COMBAT_TAB);
    await expect.poll(async () => (await panelElements(client)).some(element => element.id === `${CONTROL}summary`), {
      timeout: 10_000,
      message: "reopening the colony book after the English resource reload must rebuild the combat page",
    }).toBe(true);
    await scrollElementIntoView(client, { id: `${CONTROL}resident_row.0` });
    const englishUi = await panelElements(client);
    const pageElements = englishUi.filter(element => element.id?.startsWith(CONTROL) || element.id === COMBAT_TAB
      || ancestorsOf(englishUi, element).some(ancestor => ancestor.id === `${CONTROL}scroll`));
    const nakedKeys = pageElements.filter(element => element.displayed)
      .filter(element => /^folkways\.[a-z0-9_.-]+$/i.test(element.text?.trim() ?? ""))
      .map(element => ({ id: element.id, text: element.text }));
    expect(nakedKeys, "English combat-page controls must resolve to localized labels, not translation keys").toEqual([]);
    expect(descendantsText(englishUi, COMBAT_TAB), "the visible English navigation label must be localized")
      .toContain("Combat");
    expect(englishUi.find(element => element.id === `${CONTROL}summary`)?.text).toContain("Squad");
    expect(englishUi.find(element => element.id === `${CONTROL}points`)?.text).toContain("Looping patrol");
    expect(englishUi.find(element => element.id === `${CONTROL}preference`)?.text)
      .toContain("Weapon preference");
    expect(englishUi.find(element => element.id === `${CONTROL}preference`)?.text)
      .toContain("Bow");
    const englishReading = await sample(server, scene, "English page reopened with persisted bow preference");
    expect(englishReading.loadouts).toEqual(["BOW", "BOW"]);
    for (const [token, label] of [
      [`${CONTROL}execute`, "Start guard / patrol"],
      [`${CONTROL}retreat`, "Retreat here"],
      [`${CONTROL}release`, "Release"],
    ]) {
      expect(descendantsText(englishUi, token), `${token} must rebuild with its English action label`)
        .toContain(label);
    }
    evidence.push({ step: "English page localization and persisted bow preference", reading: englishReading, ui: pageElements });
    await capture(client, testInfo.outputPath("combat-page-open-en-us.png"), testInfo, "combat-page-open-en-us.png");
  } catch (error) {
    if (pair) {
      try {
        await capture(pair.client, testInfo.outputPath("combat-page-failure.png"), testInfo, "combat-page-failure.png");
      } catch { /* Preserve the original assertion if the screen is no longer capturable. */ }
    }
    pair?.failing(error);
    throw error;
  } finally {
    if (pair && panelOpen) {
      try { await screen.dismiss(pair.client); } catch { /* Preserve primary UI assertion and pair evidence. */ }
    }
    if (pair && scene) {
      try { await pair.server.run(CLEANUP, scene); } catch { /* Preserve primary UI assertion and pair evidence. */ }
    }
    await testInfo.attach("combat-page-readings.json", {
      body: JSON.stringify({
        controls: { page: COMBAT_PAGE, tab: COMBAT_TAB, prefix: CONTROL },
        locales: ["zh_cn", "en_us"],
        pointInput: "Both route points were captured only by clicking add_point while the server reports the player at the recorded safe footing.",
        serverAssertions: "Real UnitGroups membership, resident command modes/statuses/handles, slot destinations, physical resident positions, release, and two-stop patrol state.",
        observations: evidence,
      }, null, 2),
      contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

async function capture(client: MinecraftClient, path: string, testInfo: TestInfo, name: string) {
  await media.screenshot(client, { path });
  await testInfo.attach(name, { path, contentType: "image/png" });
}

async function waitForLanguageReload(client: MinecraftClient, locale: string) {
  for (let attempt = 0; attempt < 120; attempt++) {
    if (await client.run(LANGUAGE_RELOAD_SETTLED, { locale })) return;
    await frames(client, 5);
  }
  throw new Error(`the client resource-reload overlay did not close after switching to ${locale}`);
}

function descendantsText(elements: Awaited<ReturnType<typeof panelElements>>, id: string): string {
  const parent = elements.find(element => element.id === id);
  if (!parent) return "";
  const labels: string[] = [];
  for (let at = parent.index + 1; at < elements.length && elements[at].depth > parent.depth; at++) {
    const element = elements[at];
    const text = element.text?.trim();
    if (element.displayed && element.visible !== false && text && text !== "Button") labels.push(text);
  }
  return labels.join(" ");
}

const LANGUAGE_RELOAD_SETTLED = defineTask<{ locale: string }, boolean>({
  name: "folkways.combat-page.language-reload-settled",
  side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var mc=Minecraft.getInstance();var locale=Args.of(ctx).string("locale");
return mc.getOverlay()==null&&mc.getLanguageManager().getSelected().equals(locale);`,
  }),
});

async function sample(server: MinecraftServer, scene: Scene, _step: string): Promise<Reading> {
  return server.run(READ, scene);
}

async function waitFor(
  server: MinecraftServer,
  scene: Scene,
  label: string,
  predicate: (reading: Reading) => boolean,
  budget: number,
  stride = 8,
): Promise<Reading> {
  let last: Reading | undefined;
  for (let elapsed = 0; elapsed < budget; elapsed += stride) {
    await tick.sprint(server, stride);
    last = await sample(server, scene, label);
    if (predicate(last)) return last;
  }
  throw new Error(`${label} timed out after ${budget} ticks: ${JSON.stringify(last)}`);
}

async function playerPosition(server: MinecraftServer, name: string): Promise<Vec> {
  return server.run(PLAYER_POSITION, { player: name });
}

function distance(entity: Pick<ResidentReading, "x" | "z">, destination: Vec): number {
  return Math.hypot(entity.x - (destination.x + 0.5), entity.z - (destination.z + 0.5));
}

const TASK_IMPORTS = [
  "java.util.ArrayList", "java.util.LinkedHashMap", "java.util.List", "java.util.Map", "java.util.UUID",
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.world.entity.Entity",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.core.api.resident.body.Bodies",
  "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands",
  "io.github.izakyl.folkways.plugins.combat.group.UnitGroups", "io.github.izakyl.folkways.plugins.combat.group.UnitGroup",
  "io.github.izakyl.folkways.plugins.combat.weapon.PatrolSupplies",
];

const RESIDENTS = defineTask<{ colony: string }, string[]>({
  name: "folkways.combat-page.registered-residents",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "java.util.UUID", "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.ResidentEntity"],
    body: `
var level=((MinecraftServer)ctx.server()).overworld();var a=Args.of(ctx);
var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();
return colony.residents().stream().filter(r->r.kind().equals(PersonBody.ID))
  .filter(r->{var entity=level.getEntity(r.id());return entity instanceof ResidentEntity resident&&resident.isAlive()&&!resident.isRemoved();})
  .map(r->r.id().toString()).limit(2).toList();`,
  }),
});

const PLAYER_POSITION = defineTask<{ player: string }, Vec>({
  name: "folkways.combat-page.player-footing",
  side: "server",
  source: javaTask({
    imports: ["java.util.Map", "net.minecraft.server.MinecraftServer"],
    body: `
var server=(MinecraftServer)ctx.server();var name=Args.of(ctx).string("player");
var player=server.getPlayerList().getPlayerByName(name);if(player==null)throw new IllegalStateException("test player is not online");
return Map.of("x",player.getX(),"y",player.getY(),"z",player.getZ());`,
  }),
});

const READ = defineTask<Scene, Reading>({
  name: "folkways.combat-page.read-live-state",
  side: "server",
  source: javaTask({
    imports: TASK_IMPORTS,
    body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var level=server.overworld();
var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var player=server.getPlayerList().getPlayerByName(a.string("player"));
if(player==null)throw new IllegalStateException("test player is not online");
var ids=a.strings("residents").stream().map(UUID::fromString).toList();
var commands=ResidentCommands.of(colony);var memberships=UnitGroups.of(colony);
var group=ids.stream().map(memberships::ofResident).flatMap(java.util.Optional::stream).findFirst()
  .flatMap(member->memberships.group(member.id())).orElse(null);
var readings=new ArrayList<Map<String,Object>>();
for(var id:ids){
  Entity entity=level.getEntity(id);
  if(!(entity instanceof ResidentEntity resident)||resident.isRemoved()){
    readings.add(Map.of("id",id.toString(),"present",false,"x",0.0,"y",0.0,"z",0.0,
      "order","NONE","orderMode","NONE","orderStatus","NONE"));continue;
  }
  var order=commands.current(id);
  var row=new LinkedHashMap<String,Object>();row.put("id",id.toString());row.put("present",resident.isAlive());
  row.put("x",resident.getX());row.put("y",resident.getY());row.put("z",resident.getZ());
  row.put("order",order.map(o->o.id().toString()).orElse("NONE"));
  row.put("orderMode",order.map(o->o.mode().name()).orElse("NONE"));
  row.put("orderStatus",order.map(o->o.status().name()).orElse("NONE"));
  order.ifPresent(o->{BlockPos cell=o.destination().cell();row.put("destination",Map.of("x",cell.getX(),"y",cell.getY(),"z",cell.getZ()));});
  readings.add(row);
}
var memberIds=group==null?List.<String>of():group.members().stream().map(UUID::toString).sorted().toList();
var loadouts=ids.stream().map(id->PatrolSupplies.of(colony).preference(id).map(choice->choice.name()).orElse("AUTO")).toList();
return Map.of("colony",colony.id().toString(),"group",group==null?"":group.id().toString(),
  "groupMembers",memberIds,"groupPhase",group==null?"NONE":group.phase().name(),
  "routeStops",group==null?0:group.patrolRoute().size(),"patrolStop",group==null?-1:group.patrolStop(),
  "loadouts",loadouts,"player",Map.of("x",player.getX(),"y",player.getY(),"z",player.getZ()),"residents",readings);`,
  }),
});

const CLEANUP = defineTask<Scene, { done: true }>({
  name: "folkways.combat-page.cleanup",
  side: "server",
  source: javaTask({
    imports: ["java.util.Map", "java.util.UUID", "net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.plugins.combat.group.UnitGroups"],
    body: `
var server=(MinecraftServer)ctx.server();var level=server.overworld();var a=Args.of(ctx);
for(String raw:a.strings("residents")){var entity=level.getEntity(UUID.fromString(raw));if(entity!=null)entity.discard();}
Colonies.of(server,UUID.fromString(a.string("colony"))).ifPresent(colony->{
  try{UnitGroups.of(colony).ofResident(UUID.fromString(a.strings("residents").getFirst())).flatMap(group->UnitGroups.of(colony).group(group.id())).ifPresent(UnitGroups.of(colony)::dissolve);}catch(Exception ignored){}
  colony.raze();
});
return Map.of("done",true);`,
  }),
});
