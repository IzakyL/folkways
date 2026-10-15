import { expect, test } from "@playwright/test";
import { chat, defineTask, javaTask, screen, tick, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { actsForColony, openColonyPanelViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { BIND_COLONY, runColonyTask } from "../shared/colony/colony-tasks";
import { clickElement, panelElements, scrollElementIntoView, typeIntoElement } from "../ldlib2";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { tryCommand, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { GROUND, around } from "../shared/harness/stage";

// A colony goes with the player who keeps it, not with a book: the panel's title lists the colonies a player keeps
// and founds more, the Members page names who keeps the one open, and only its founder may end it. A player asked
// in keeps a colony only once they accept.

const BOOK_ITEM = "folkways:colony_book";
// The colony a player acts for settles as the change that moves it lands; this leaves room for the packet.
const SETTLE_TICKS = 10;
// Someone who keeps colonies on this server without being online: what a second player looks like to the first.
const STRANGER = "00000000-0000-4000-8000-00000000f00d";

type Kept = {
  id: string; name: string; founder: string | null; members: string[]; invited: string[]; team: string | null; razed: boolean;
};
type Reading = { ok: true; player: string; current: string | null; book_names_colony: boolean; colonies: Kept[] };

const IMPORTS = [
  "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.core.api.colony.Colony",
  "io.github.izakyl.folkways.front.engine.charter.Charter",
  "io.github.izakyl.folkways.front.engine.charter.Charters",
  "io.github.izakyl.folkways.front.engine.charter.CurrentColony",
  "io.github.izakyl.folkways.front.engine.charter.Invitations",
  "io.github.izakyl.folkways.front.engine.registry.FolkwaysDataComponents",
  "net.minecraft.server.MinecraftServer",
  "net.minecraft.server.level.ServerPlayer",
];

const PLAYER = `
private static ServerPlayer player(MinecraftServer server, String who) {
    ServerPlayer player = server.getPlayerList().getPlayerByName(who);
    if (player == null) {
        throw Fail.notFound("no online player named " + who);
    }
    return player;
}
`;

/** Every colony with who keeps it, and the one the player acts for. */
const READ = defineTask<{ player: string }, Reading>({
  name: "folkways.charter.read",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: IMPORTS,
    members: PLAYER,
    body: `
MinecraftServer server = ctx.server();
ServerPlayer player = player(server, Args.of(ctx).string("player"));
List<Object> colonies = new ArrayList<Object>();
for (Colony colony : Colonies.all(server)) {
    Charter charter = Charters.of(server, colony);
    Map<String, Object> one = new LinkedHashMap<String, Object>();
    one.put("id", colony.id().toString());
    one.put("name", charter.name());
    one.put("founder", charter.founder().map(UUID::toString).orElse(null));
    List<Object> members = new ArrayList<Object>();
    for (UUID member : charter.members()) {
        members.add(member.toString());
    }
    one.put("members", members);
    List<Object> invited = new ArrayList<Object>();
    for (Charter.Invitation invitation : charter.invited()) {
        invited.add(invitation.player().toString());
    }
    one.put("invited", invited);
    one.put("team", charter.team().map(UUID::toString).orElse(null));
    one.put("razed", Boolean.valueOf(Colonies.razed(server, colony.id())));
    colonies.add(one);
}
Map<String, Object> out = new LinkedHashMap<String, Object>();
out.put("ok", Boolean.TRUE);
out.put("player", player.getUUID().toString());
out.put("current", CurrentColony.of(player).map(colony -> colony.id().toString()).orElse(null));
out.put("book_names_colony", Boolean.valueOf(player.getMainHandItem().has(FolkwaysDataComponents.COLONY_ID.get())));
out.put("colonies", colonies);
return Sync.stamp(out);
`,
  }),
});

/** Puts the stranger on a colony's list and makes them its founder, as a second player handed it would be. */
const HAND_TO_STRANGER = defineTask<{ colony: string; stranger: string }, { ok: true }>({
  name: "folkways.charter.hand-to-stranger",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: IMPORTS,
    body: `
MinecraftServer server = ctx.server();
Args args = Args.of(ctx);
Colony colony = Colonies.of(server, UUID.fromString(args.string("colony")))
    .orElseThrow(() -> Fail.notFound("no colony " + args.string("colony")));
Charters.keep(colony, Charters.of(server, colony).foundedBy(UUID.fromString(args.string("stranger"))));
return Sync.stamp(Map.of("ok", Boolean.TRUE));
`,
  }),
});

/** A colony nobody keeps, or one the stranger keeps: what a bound book can name. */
const MINT = defineTask<{ stranger: string }, { ok: true; colony: string }>({
  name: "folkways.charter.mint",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: IMPORTS,
    body: `
MinecraftServer server = ctx.server();
String stranger = Args.of(ctx).string("stranger");
Colony colony = Colonies.mint(server);
if (!stranger.isEmpty()) {
    Charters.keep(colony, Charter.founded("", UUID.fromString(stranger)));
}
return Sync.stamp(Map.of("ok", Boolean.TRUE, "colony", colony.id().toString()));
`,
  }),
});

/** A colony the stranger keeps, named as given, that asks the player in: what a second player inviting them does. */
const INVITE = defineTask<{ player: string; name: string }, { ok: true; colony: string }>({
  name: "folkways.charter.invite",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: IMPORTS,
    members: PLAYER,
    body: `
MinecraftServer server = ctx.server();
Args args = Args.of(ctx);
ServerPlayer player = player(server, args.string("player"));
UUID stranger = UUID.fromString("${STRANGER}");
Colony colony = Colonies.mint(server);
Charters.keep(colony, Charter.founded(args.string("name"), stranger).inviting(player.getUUID(), stranger));
Invitations.tell(server, colony, player.getUUID());
return Sync.stamp(Map.of("ok", Boolean.TRUE, "colony", colony.id().toString()));
`,
  }),
});

async function read(server: MinecraftServer, player: string): Promise<Reading> {
  return (await runColonyTask(server, READ, { player })) as unknown as Reading;
}

function named(reading: Reading, name: string): Kept {
  const found = reading.colonies.find((colony) => colony.name === name);
  if (!found) {
    throw new Error(`no colony named ${name} in ${JSON.stringify(reading.colonies)}`);
  }
  return found;
}

function snbtUuid(id: string): string {
  const hex = id.replace(/-/g, "");
  const ints = [0, 8, 16, 24].map((at) => parseInt(hex.slice(at, at + 8), 16) | 0);
  return `[I;${ints.join(",")}]`;
}

async function panelTexts(client: any): Promise<string[]> {
  return (await panelElements(client)).map((element) => element.text ?? "").filter((text) => text.length > 0);
}

async function waitForText(client: any, wanted: RegExp, timeoutMs = 10_000): Promise<string[]> {
  const deadline = Date.now() + timeoutMs;
  let texts: string[] = [];
  while (Date.now() < deadline) {
    texts = await panelTexts(client);
    if (texts.some((text) => wanted.test(text))) {
      return texts;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`the panel never showed ${wanted} (last saw ${JSON.stringify(texts)})`);
}

test("a player founds, names and switches colonies from the panel's title, and only a founder ends one", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: any = {};

  try {
    pair = await launchPair({
      server: { trace: tracePath("e2e", "charter-server"), instance: seededServer(`charter-server-${runId}`) },
      client: { trace: tracePath("e2e", "charter-client"), instance: `charter-client-${runId}` },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const stand: Vec = { x: Math.round(grounded.pos.x) + 2, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 2 };
    await GROUND.lay(server, around([stand], 4, 3));
    await tryCommand(server, `gamemode survival ${playerName}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);

    // With no colony kept, the panel is the list alone, and New Colony founds one under the name typed.
    await openColonyPanelViaBook(server, client, playerName, stand);
    await waitForElement(client, { id: "folkways.colony.create" }, { timeoutMs: 10_000 });
    await typeIntoElement(client, { id: "folkways.charter.name.field" }, "Alpha");
    await tick.sprint(server, 2);
    await clickElement(client, { id: "folkways.colony.create" });
    await tick.sprint(server, 10);
    expect(await actsForColony(server, playerName)).toBe(true);
    let state = await read(server, playerName);
    evidence.founded = state;
    const alpha = named(state, "Alpha");
    expect(state.current).toBe(alpha.id);
    expect(alpha.founder).toBe(state.player);
    expect(alpha.members).toEqual([state.player]);

    // The panel reopens on the colony, named in its title; pressed, the title lists it and founds another.
    await waitForText(client, /Alpha ▾/);
    await clickElement(client, { id: "folkways.charter.heading" });
    await waitForElement(client, { id: "folkways.colony.create" }, { timeoutMs: 10_000 });
    await typeIntoElement(client, { id: "folkways.charter.name.field" }, "Beta");
    await tick.sprint(server, 2);
    await clickElement(client, { id: "folkways.colony.create" });
    await tick.sprint(server, 10);
    state = await read(server, playerName);
    evidence.second = state;
    const beta = named(state, "Beta");
    expect(state.current).toBe(beta.id);
    await waitForText(client, /Beta ▾/);

    // Switching back is one press on the colony's row.
    await clickElement(client, { id: "folkways.charter.heading" });
    await waitForElement(client, { id: "folkways.charter.switch.0" }, { timeoutMs: 10_000 });
    await clickElement(client, { id: "folkways.charter.switch.0" });
    await tick.sprint(server, 10);
    state = await read(server, playerName);
    expect(state.current).toBe(alpha.id);
    await waitForText(client, /Alpha ▾/);

    // The Members page names who keeps it, and marks its founder.
    await clickElement(client, { id: "folkways.tab.members" });
    evidence.members_page = await waitForText(client, new RegExp(`${playerName} \\(founder\\)`));

    // Handed to someone else, the colony is no longer this player's to end: the raze button asks nothing.
    await runColonyTask(server, HAND_TO_STRANGER, { colony: alpha.id, stranger: STRANGER });
    await tick.sprint(server, 5);
    // Settings is the last entry of the page list, below the plugins' pages.
    await scrollElementIntoView(client, { id: "folkways.tab.settings" });
    await clickElement(client, { id: "folkways.tab.settings" });
    await clickElement(client, { id: "folkways.metrics.raze" });
    await tick.sprint(server, 5);
    const confirm = (await panelElements(client)).find((element) => element.id === "folkways.metrics.raze.confirm");
    evidence.confirm_after_handover = confirm ?? null;
    expect(confirm?.displayed && (confirm?.width ?? 0) > 0).toBeFalsy();
    state = await read(server, playerName);
    expect(named(state, "Alpha").razed).toBe(false);
    expect(named(state, "Alpha").founder).toBe(STRANGER);

    // Leaving it, the player acts for the colony they still keep.
    await scrollElementIntoView(client, { id: "folkways.tab.members" });
    await clickElement(client, { id: "folkways.tab.members" });
    await clickElement(client, { id: "folkways.charter.leave" });
    await tick.sprint(server, SETTLE_TICKS);
    state = await read(server, playerName);
    evidence.left = state;
    expect(named(state, "Alpha").members).toEqual([STRANGER]);
    expect(state.current).toBe(beta.id);

    // Its founder can end the colony still theirs; after that they keep none, and act for none.
    await screen.dismiss(client);
    await openColonyPanelViaBook(server, client, playerName, stand);
    await waitForText(client, /Beta ▾/);
    await scrollElementIntoView(client, { id: "folkways.tab.settings" });
    await clickElement(client, { id: "folkways.tab.settings" });
    await clickElement(client, { id: "folkways.metrics.raze" });
    await typeIntoElement(client, { id: "folkways.metrics.raze.field" }, "DELETE THIS COLONY");
    await tick.sprint(server, 2);
    await clickElement(client, { id: "folkways.metrics.raze.confirm" });
    await tick.sprint(server, SETTLE_TICKS);
    state = await read(server, playerName);
    evidence.razed = state;
    expect(state.colonies.filter((colony) => colony.id === beta.id && !colony.razed)).toEqual([]);
    expect(state.current).toBeNull();
    await screen.dismiss(client);

    testInfo.attach("charter", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

test("a bound book takes up its colony once, and only if no one keeps it", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: any = {};

  try {
    pair = await launchPair({
      server: { trace: tracePath("e2e", "legacy-book-server"), instance: seededServer(`legacy-book-server-${runId}`) },
      client: { trace: tracePath("e2e", "legacy-book-client"), instance: `legacy-book-client-${runId}` },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const stand: Vec = { x: Math.round(grounded.pos.x) + 2, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 2 };
    await GROUND.lay(server, around([stand], 4, 3));
    const useBook = async () => {
      await openColonyPanelViaBook(server, client, playerName, stand);
      await screen.dismiss(client);
      await tick.sprint(server, 5);
    };

    const unclaimed = (await runColonyTask(server, MINT, { stranger: "" })).colony as string;
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with `
      + `${BOOK_ITEM}[folkways:colony_id=${snbtUuid(unclaimed)}] 1`);
    await tick.sprint(server, 5);
    // Held, the book is left as it is: nothing watches the hand. Using it is what takes the colony up.
    let state = await read(server, playerName);
    evidence.held = state;
    expect(state.book_names_colony).toBe(true);
    expect(state.current).toBeNull();
    await useBook();
    state = await read(server, playerName);
    evidence.claimed = state;
    expect(state.book_names_colony).toBe(false);
    expect(state.current).toBe(unclaimed);
    const taken = state.colonies.find((colony) => colony.id === unclaimed)!;
    expect(taken.founder).toBe(state.player);

    const theirs = (await runColonyTask(server, MINT, { stranger: STRANGER })).colony as string;
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with `
      + `${BOOK_ITEM}[folkways:colony_id=${snbtUuid(theirs)}] 1`);
    await tick.sprint(server, 5);
    await useBook();
    state = await read(server, playerName);
    evidence.refused = state;
    expect(state.book_names_colony).toBe(false);
    expect(state.current).toBe(unclaimed);
    const kept = state.colonies.find((colony) => colony.id === theirs)!;
    expect(kept.members).toEqual([STRANGER]);

    testInfo.attach("legacy-book", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

test("an invitation makes a player a keeper only once they accept it, in the panel or from chat", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: any = {};

  try {
    pair = await launchPair({
      server: { trace: tracePath("e2e", "invitation-server"), instance: seededServer(`invitation-server-${runId}`) },
      client: { trace: tracePath("e2e", "invitation-client"), instance: `invitation-client-${runId}` },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const stand: Vec = { x: Math.round(grounded.pos.x) + 2, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 2 };
    await GROUND.lay(server, around([stand], 4, 3));
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);

    // Asked in, the player is told in chat and keeps nothing yet.
    const gamma = (await runColonyTask(server, INVITE, { player: playerName, name: "Gamma" })).colony as string;
    evidence.told = await chat.waitFor(client, "invites you to join the colony Gamma", { includeExisting: true });
    let state = await read(server, playerName);
    expect(named(state, "Gamma").members).toEqual([STRANGER]);
    expect(named(state, "Gamma").invited).toEqual([state.player]);
    expect(state.current).toBeNull();

    // Keeping no colony, the panel is the list, with the invitation above it; accepting makes them a keeper.
    await openColonyPanelViaBook(server, client, playerName, stand);
    await waitForElement(client, { id: "folkways.charter.chooser.accept.0" }, { timeoutMs: 10_000 });
    await clickElement(client, { id: "folkways.charter.chooser.accept.0" });
    await tick.sprint(server, SETTLE_TICKS);
    state = await read(server, playerName);
    evidence.accepted = state;
    expect(named(state, "Gamma").members).toEqual([STRANGER, state.player]);
    expect(named(state, "Gamma").invited).toEqual([]);
    expect(state.current).toBe(gamma);
    await waitForText(client, /Gamma ▾/);
    await screen.dismiss(client);

    // A later invitation stands above the Members page; declined, it is gone and the player keeps what they kept.
    await runColonyTask(server, INVITE, { player: playerName, name: "Delta" });
    await openColonyPanelViaBook(server, client, playerName, stand);
    await scrollElementIntoView(client, { id: "folkways.tab.members" });
    await clickElement(client, { id: "folkways.tab.members" });
    await waitForElement(client, { id: "folkways.charter.members.decline.0" }, { timeoutMs: 10_000 });
    await clickElement(client, { id: "folkways.charter.members.decline.0" });
    await tick.sprint(server, SETTLE_TICKS);
    state = await read(server, playerName);
    evidence.declined = state;
    expect(named(state, "Delta").members).toEqual([STRANGER]);
    expect(named(state, "Delta").invited).toEqual([]);
    expect(state.current).toBe(gamma);
    await screen.dismiss(client);

    // The chat message's answer is a command the player runs; accepting there joins the colony the same way.
    const epsilon = (await runColonyTask(server, INVITE, { player: playerName, name: "Epsilon" })).colony as string;
    evidence.command = await chat.send(client, `/folkways invitation accept ${epsilon}`);
    await tick.sprint(server, SETTLE_TICKS);
    state = await read(server, playerName);
    evidence.by_command = state;
    expect(named(state, "Epsilon").members).toEqual([STRANGER, state.player]);
    expect(state.current).toBe(gamma);

    testInfo.attach("invitation", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

test("with FTB Teams, the founder hands the colony to their party, which then says who keeps it", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: any = {};

  try {
    pair = await launchPair({
      config: "blockwright.toml#ftbteams",
      server: { trace: tracePath("e2e", "charter-ftb-server"), instance: seededServer(`charter-ftb-server-${runId}`) },
      client: { trace: tracePath("e2e", "charter-ftb-client"), instance: `charter-ftb-client-${runId}` },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const stand: Vec = { x: Math.round(grounded.pos.x) + 2, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 2 };
    await GROUND.lay(server, around([stand], 4, 3));
    await tryCommand(server, `op ${playerName}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);
    const colony = String((await runColonyTask(server, BIND_COLONY, { player: playerName })).colony_id);
    const kept = async () => (await read(server, playerName)).colonies.find((one) => one.id === colony)!;

    const openMembers = async () => {
      await screen.dismiss(client);
      await openColonyPanelViaBook(server, client, playerName, stand);
      await scrollElementIntoView(client, { id: "folkways.tab.members" });
      await clickElement(client, { id: "folkways.tab.members" });
    };
    const entrust = async () => {
      await clickElement(client, { id: "folkways.charter.entrust" });
      await clickElement(client, { id: "folkways.charter.entrust.confirm" });
      await tick.sprint(server, 5);
    };

    // A player in no party has nothing to hand it to.
    await openMembers();
    await entrust();
    expect((await kept()).team).toBeNull();

    // In a party, the colony goes to it: the page says so, and turns no one in or out itself.
    // FTB Teams takes party commands from a player only, so the client sends them.
    await screen.dismiss(client);
    evidence.party = await chat.send(client, "/ftbteams party create Settlers");
    await tick.sprint(server, 10);
    await openMembers();
    await entrust();
    const handed = await kept();
    evidence.handed = handed;
    expect(handed.team).not.toBeNull();
    expect(handed.founder).toBe((await read(server, playerName)).player);
    await waitForText(client, /Kept by the FTB team .*Settlers/);
    const shown = (id: string) => panelElements(client)
      .then((elements) => elements.some((element) => element.id === id && element.displayed && element.width > 0));
    expect(await shown("folkways.charter.admit")).toBe(false);
    expect(await shown("folkways.charter.leave")).toBe(false);

    // Its owner takes it back, keeping the keepers the party had.
    await clickElement(client, { id: "folkways.charter.release" });
    await tick.sprint(server, 5);
    const released = await kept();
    evidence.released = released;
    expect(released.team).toBeNull();
    expect(released.members).toEqual([(await read(server, playerName)).player]);

    // Handed again and the party then disbanded, the colony keeps the keepers it last heard of.
    await openMembers();
    await entrust();
    expect((await kept()).team).not.toBeNull();
    await screen.dismiss(client);
    evidence.disband = await chat.send(client, "/ftbteams party leave");
    await tick.sprint(server, SETTLE_TICKS);
    const orphaned = await read(server, playerName);
    evidence.orphaned = orphaned;
    expect(orphaned.current).toBe(colony);
    expect(orphaned.colonies.find((one) => one.id === colony)!.members).toEqual([orphaned.player]);
    await openMembers();
    await waitForText(client, /a team that is gone/);
    await clickElement(client, { id: "folkways.charter.release" });
    await tick.sprint(server, 5);
    expect((await kept()).team).toBeNull();
    await screen.dismiss(client);

    testInfo.attach("charter-ftb", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
