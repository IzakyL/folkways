import { expect, test } from "@playwright/test";
import { defineTask, javaTask, screen, tick } from "@izakyl/blockwright-minecraft";
import { panelElements } from "../ldlib2";
import {
  foundColonyFast,
  foundColonyWithResidents,
  foundingBox,
  openColonyPanelViaBook,
  razeFoundingSet,
  waitForGroundedPlayer,
  type Vec,
} from "../shared/colony/colony-founding";
import { runColonyTask, type ColonyTaskValue } from "../shared/colony/colony-tasks";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { cover, twins } from "../shared/harness/stage";

const RESIDENTS = 2;

const MEMBER_CELLS = RESIDENTS + 1;

const PERSISTED = [
  { domain: "folkways:living", key: "hunger" },
  { domain: "folkways:person", key: "waiting" },
] as const;

const DUMP = defineTask<{ player: string; ox: number; oy: number; oz: number }, ColonyTaskValue>({
  name: "folkways.colony.dump",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colony",
      "io.github.izakyl.folkways.core.api.colony.Contributions",
      "io.github.izakyl.folkways.core.api.colony.Held",
      "io.github.izakyl.folkways.core.api.colony.Holding",
      "io.github.izakyl.folkways.front.engine.charter.CurrentColony",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.engine.item.ColonyBookItem",
      "net.minecraft.core.BlockPos",
      "net.minecraft.nbt.CompoundTag",
      "net.minecraft.nbt.Tag",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.server.level.ServerPlayer",
    ],
    members: `
private static List<String> offsets(Set<BlockPos> cells, int ox, int oy, int oz) {
    List<String> out = new ArrayList<String>();
    for (BlockPos at : cells) {
        out.add((at.getX() - ox) + "," + (at.getY() - oy) + "," + (at.getZ() - oz));
    }
    Collections.sort(out);
    return out;
}

private static String shape(CompoundTag tag) {
    if (tag == null) {
        return "null";
    }
    List<String> keys = new ArrayList<String>();
    for (String key : tag.getAllKeys()) {
        Tag value = tag.get(key);
        keys.add(key + ":" + (value == null ? "-" : String.valueOf(value.getId())));
    }
    Collections.sort(keys);
    return keys.toString();
}
`,
    body: `
MinecraftServer server = ctx.server();
Args args = Args.of(ctx);
int ox = (int) args.integer("ox");
int oy = (int) args.integer("oy");
int oz = (int) args.integer("oz");
Map<String, Object> out = new LinkedHashMap<String, Object>();
ServerPlayer player = server.getPlayerList().getPlayerByName(args.string("player"));
if (player == null) {
    throw Fail.notFound("no online player named " + args.string("player"));
}
UUID bound = CurrentColony.id(player).orElse(null);
out.put("acts_for_colony", Boolean.valueOf(bound != null));
if (bound == null) {
    throw Fail.notFound("the player acts for no colony", out);
}
out.put("colony_id", bound.toString());
out.put("holds_book_for", Boolean.valueOf(ColonyBookItem.holdsBookFor(player, bound)));
Colony colony = ColonyGround.of(server, bound).orElse(null);
out.put("registry_present", Boolean.valueOf(colony != null));
if (colony == null) {
    throw Fail.notFound("the id on the book is not in the colony registry", out);
}

List<String> kinds = new ArrayList<String>();
for (Object resident : colony.residents()) {
    kinds.add(resident.getClass().getName());
}
Collections.sort(kinds);
out.put("roster", Integer.valueOf(kinds.size()));
out.put("resident_classes", kinds);
out.put("view_class", colony.view(player.serverLevel()).getClass().getName());

Set<BlockPos> members = new LinkedHashSet<BlockPos>();
Set<BlockPos> claimed = new LinkedHashSet<BlockPos>();
for (Holding holding : colony.holdings()) {
    if (!(holding.what() instanceof Held.Block)) {
        continue;
    }
    BlockPos at = ((Held.Block) holding.what()).cell().cell();
    members.add(at);
    if (!holding.owner().toString().equals("folkways:store")) {
        claimed.add(at);
    }
}
out.put("members", offsets(members, ox, oy, oz));
out.put("claimed", offsets(claimed, ox, oy, oz));

List<String> domains = new ArrayList<String>();
for (Contributions.Entry domain : Contributions.all()) {
    ResourceLocation id = domain.owner();
    Object presence = colony.service(id, Object.class).orElse(null);
    domains.add(id + " presence=" + (presence == null ? "none" : presence.getClass().getName())
        + " kept=" + shape(colony.kept(id)));
}
Collections.sort(domains);
out.put("domains", domains);
out.put("ok", Boolean.TRUE);
return out;
`,
  }),
});

for (const stage of twins()) test(stage.title("founding fast and founding through the panel leave the same colony state"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: any = { residents: RESIDENTS };
  let server: any;
  let client: any;
  try {
    pair = await launchPair({
      config: stage.config,
      server: {
        trace: tracePath("e2e", stage.name("equivalence-server")),
        instance: seededServer(stage.name(`equivalence-server-${runId}`)),
      },
      client: {
        trace: tracePath("e2e", stage.name("equivalence-client")),
        instance: stage.name(`equivalence-client-${runId}`),
      },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `op ${playerName}`);
    await tryCommand(server, `gamemode creative ${playerName}`);

    const base = {
      x: Math.round(grounded.pos.x),
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z),
    };
    const fastAt: Vec = { x: base.x + 4, y: base.y, z: base.z + 4 };
    const panelAt: Vec = { x: base.x + 4, y: base.y, z: base.z + 84 };

    // Both foundings on the one stage: aboard, one ship long enough for the two of them.
    await stage.lay(server, cover(foundingBox(fastAt), foundingBox(panelAt)));
    const fastFounding = await foundColonyFast(server, client, playerName,
      { origin: fastAt, residentCount: RESIDENTS, stage });
    evidence.fast_founding = fastFounding.evidence;
    const fast = await dump(server, playerName, fastAt);
    evidence.fast = fast;

    await tryCommand(server, "kill @e[type=folkways:resident]");
    await tick.sprint(server, 20);
    evidence.razed = await razeFoundingSet(server, fastAt, stage);
    await tick.sprint(server, 20);

    const panelFounding = await foundColonyWithResidents(server, client, playerName,
      { origin: panelAt, residentCount: RESIDENTS, stage });
    evidence.panel_founding = panelFounding.evidence;
    const panel = await dump(server, playerName, panelAt);
    evidence.panel = panel;

    // The panel a founded colony opens on: its own pages, and no zones tab.
    await openColonyPanelViaBook(server, client, playerName, panelFounding.standBlock, stage);
    const tabs = (await panelElements(client)).map((element) => element.id).filter(Boolean);
    expect(tabs).toContain("folkways.tab.residents");
    expect(tabs).toContain("folkways.tab.metrics");
    expect(tabs).toContain("folkways.tab.settings");
    expect(tabs).not.toContain("folkways.tab.zones");
    await screen.dismiss(client);

    substantial(fast, "task");
    substantial(panel, "panel");

    expect(fast.colony_id, "each path should mint its own colony, not read the same one")
      .not.toBe(panel.colony_id);

    const diff = compare(fast, panel);
    evidence.diff = diff;
    expect(diff, "fast and panel founding left different colony state: every key named is missing while specs that "
      + "found with foundColonyFast run - fix that path (colony-founding.ts, colony-tasks.ts), not these assertions")
      .toEqual({});
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    testInfo.attach(stage.name("founding-equivalence"),
      { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

async function dump(server: any, playerName: string, origin: Vec): Promise<ColonyTaskValue> {
  return runColonyTask(server, DUMP, {
    player: playerName, ox: origin.x, oy: origin.y, oz: origin.z,
  });
}

function substantial(state: ColonyTaskValue, path: string) {
  const seen = () => ` (the ${path} dump read ${JSON.stringify(state)})`;

  expect(state.acts_for_colony, `after ${path} founding the player acts for no colony${seen()}`).toBe(true);
  expect(state.holds_book_for, `after ${path} founding holdsBookFor does not recognize the book in hand${seen()}`).toBe(true);
  expect(state.registry_present, `the colony minted by ${path} founding is not in the registry${seen()}`).toBe(true);
  expect(state.roster, `the ${path} founding roster does not hold ${RESIDENTS} people${seen()}`).toBe(RESIDENTS);
  expect(state.members, `the ${path} founding member cells are not "${RESIDENTS} beds + 1 food chest"${seen()}`)
    .toHaveLength(MEMBER_CELLS);
  expect((state.claimed as string[]).length,
    `after ${path} founding no block is held for anything but storage${seen()}`)
    .toBeGreaterThan(0);

  const domains = (state.domains ?? []) as string[];
  expect(domains.length, `after ${path} founding Contributions.all() is empty - `
    + `this dump is comparing nothing${seen()}`).toBeGreaterThan(0);
  // Fear and breathing only declare urges on each body: neither keeps a presence on the colony.
  const urgeOnly = ["folkways:fear ", "folkways:breathing "];
  const unsettled = domains.filter((line) => !urgeOnly.some((prefix) => line.startsWith(prefix))
    && line.includes("presence=none"));
  expect(unsettled, `after ${path} founding some domains left no presence on the colony`).toEqual([]);
  for (const { domain, key } of PERSISTED) {
    const line = domains.find((entry) => entry.startsWith(`${domain} `));
    expect(line, `after ${path} founding ${domain} is not in Contributions.all()${seen()}`).toBeDefined();
    expect(line, `after ${path} founding the tag ${domain} left has no ${key} - `
      + `founding always sets this entry, so missing it means the dump is blind, not that the state changed`)
      .toContain(`${key}:`);
  }
}

const IDENTITY = new Set(["colony_id", "$task", "ok"]);

function compare(fast: ColonyTaskValue, panel: ColonyTaskValue): Record<string, { fast: unknown; panel: unknown }> {
  const off: Record<string, { fast: unknown; panel: unknown }> = {};
  for (const key of [...new Set([...Object.keys(fast), ...Object.keys(panel)])].sort()) {
    if (IDENTITY.has(key)) {
      continue;
    }
    if (JSON.stringify(fast[key]) !== JSON.stringify(panel[key])) {
      off[key] = { fast: fast[key], panel: panel[key] };
    }
  }
  return off;
}
