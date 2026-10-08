import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { defineTask, tick } from "@izakyl/blockwright-minecraft";
import {
  foundColonyFast,
  foundColonyWithResidents,
  razeFoundingSet,
  waitForGroundedPlayer,
  type Vec,
} from "./colony-founding";
import { runColonyTask, type ColonyTaskValue } from "./colony-tasks";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, outAbs, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

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
  source: `
import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Fail;
import dev.blockwright.api.Context;

public final class Task {

    public static Object run(Context ctx) throws Exception {
        Object server = ctx.server();
        loader = server.getClass().getClassLoader();
        Args args = Args.of(ctx);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();

        String who = args.string("player");
        int ox = (int) args.integer("ox");
        int oy = (int) args.integer("oy");
        int oz = (int) args.integer("oz");

        Object player = call(call(server, "getPlayerList"), "getPlayerByName", who);
        if (player == null) {
            throw Fail.notFound("no online player named " + who);
        }
        Object book = call(player, "getMainHandItem");
        Object bound = maybe(callStatic(BOOK_ITEM, "boundColonyId", book)).orElse(null);
        out.put("book_bound", Boolean.valueOf(bound != null));
        if (bound == null) {
            throw Fail.notFound("the main hand book carries no folkways:colony_id", out);
        }
        out.put("colony_id", String.valueOf(bound));
        out.put("holds_book_bound_to", callStatic(BOOK_ITEM, "holdsBookBoundTo", player, bound));

        Object colony = maybe(callStatic(GROUND, "of", server, bound)).orElse(null);
        out.put("registry_present", Boolean.valueOf(colony != null));
        if (colony == null) {
            throw Fail.notFound("the id on the book is not in the colony registry", out);
        }
        Object level = call(player, "serverLevel");

        java.util.List<?> roster = (java.util.List<?>) call(colony, "residents");
        out.put("roster", Integer.valueOf(roster.size()));
        java.util.List<String> kinds = new java.util.ArrayList<String>();
        for (Object resident : roster) {
            kinds.add(resident.getClass().getName());
        }
        java.util.Collections.sort(kinds);
        out.put("resident_classes", kinds);

        Object view = call(colony, "view", level);
        out.put("view_class", view.getClass().getName());
        java.util.Set<Object> members = new java.util.LinkedHashSet<Object>();
        java.util.Set<Object> claimed = new java.util.LinkedHashSet<Object>();
        for (Object holding : (java.util.List<?>) call(colony, "holdings")) {
            Object what = call(holding, "what");
            if (!what.getClass().getSimpleName().equals("Block")) {
                continue;
            }
            Object at = call(call(what, "cell"), "cell");
            members.add(at);
            if (!String.valueOf(call(holding, "owner")).equals(STORE)) {
                claimed.add(at);
            }
        }
        out.put("members", offsets(members, ox, oy, oz));
        out.put("claimed", offsets(claimed, ox, oy, oz));

        java.util.List<String> domains = new java.util.ArrayList<String>();
        for (Object domain : (java.util.Collection<?>) callStatic(DOMAINS, "all")) {
            Object id = call(domain, "owner");
            Object presence = maybe(call(colony, "service", id, Object.class)).orElse(null);
            domains.add(id + " presence="
                + (presence == null ? "none" : presence.getClass().getName())
                + " kept=" + tagShape(call(colony, "kept", id)));
        }
        java.util.Collections.sort(domains);
        out.put("domains", domains);

        out.put("ok", Boolean.TRUE);
        return out;
    }

    private static java.util.List<String> offsets(java.util.Set<?> cells, int ox, int oy, int oz)
            throws Exception {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Object at : cells) {
            out.add((((Number) call(at, "getX")).intValue() - ox) + ","
                + (((Number) call(at, "getY")).intValue() - oy) + ","
                + (((Number) call(at, "getZ")).intValue() - oz));
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static String tagShape(Object tag) throws Exception {
        if (tag == null) {
            return "null";
        }
        java.util.List<String> keys = new java.util.ArrayList<String>();
        for (Object key : (java.util.Collection<?>) call(tag, "getAllKeys")) {
            Object value = call(tag, "get", String.valueOf(key));
            keys.add(key + ":" + (value == null ? "-" : String.valueOf(call(value, "getId"))));
        }
        java.util.Collections.sort(keys);
        return keys.toString();
    }

    private static ClassLoader loader;

    private static Class<?> cls(String name) throws Exception {
        return Class.forName(name, true, loader);
    }

    private static java.lang.reflect.Method find(Class<?> owner, String name, int arity) {
        for (Class<?> here = owner; here != null; here = here.getSuperclass()) {
            java.lang.reflect.Method found = declared(here, name, arity);
            if (found != null) {
                return found;
            }
            for (Class<?> face : here.getInterfaces()) {
                found = declared(face, name, arity);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new IllegalStateException(
            "no method " + owner.getName() + "." + name + " taking " + arity + " argument(s)");
    }

    private static java.lang.reflect.Method declared(Class<?> here, String name, int arity) {
        for (java.lang.reflect.Method method : here.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arity) {
                try {
                    method.setAccessible(true);
                } catch (RuntimeException blocked) {
                }
                return method;
            }
        }
        return null;
    }

    private static Object call(Object target, String name, Object... a) throws Exception {
        return find(target.getClass(), name, a.length).invoke(target, a);
    }

    private static Object callStatic(String className, String name, Object... a) throws Exception {
        return find(cls(className), name, a.length).invoke(null, a);
    }

    private static java.util.Optional<?> maybe(Object value) {
        return (java.util.Optional<?>) value;
    }

    private static final String BOOK_ITEM = "io.github.izakyl.folkways.front.engine.item.ColonyBookItem";
    private static final String GROUND = "io.github.izakyl.folkways.front.engine.colony.ColonyGround";
    private static final String STORE = "folkways:store";
    private static final String DOMAINS = "io.github.izakyl.folkways.core.api.colony.Contributions";
}
`,
});

test("founding fast and founding through the panel leave the same colony state", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: any = { residents: RESIDENTS };
  let server: any;
  let client: any;
  try {
    pair = await launchPair({
      config: "blockwright.toml",
      server: {
        trace: tracePath("e2e", "equivalence-server"),
        instance: seededServer(`equivalence-server-${runId}`),
      },
      client: {
        trace: tracePath("e2e", "equivalence-client"),
        instance: `equivalence-client-${runId}`,
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

    const fastFounding = await foundColonyFast(server, client, playerName,
      { origin: fastAt, residentCount: RESIDENTS });
    evidence.fast_founding = fastFounding.evidence;
    const fast = await dump(server, playerName, fastAt);
    evidence.fast = fast;

    await tryCommand(server, "kill @e[type=folkways:resident]");
    await tick.sprint(server, 20);
    evidence.razed = await razeFoundingSet(server, fastAt);
    await tick.sprint(server, 20);

    const panelFounding = await foundColonyWithResidents(server, client, playerName,
      { origin: panelAt, residentCount: RESIDENTS });
    evidence.panel_founding = panelFounding.evidence;
    const panel = await dump(server, playerName, panelAt);
    evidence.panel = panel;

    substantial(fast, "task");
    substantial(panel, "panel");

    expect(fast.colony_id, "each path should mint its own colony, not read the same one")
      .not.toBe(panel.colony_id);

    const diff = compare(fast, panel);
    evidence.diff = diff;
    expect(diff, verdict(diff)).toEqual({});
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    ensureOut("e2e");
    const body = JSON.stringify(evidence, null, 2);
    writeFileSync(outAbs("e2e", "reports", "founding-equivalence.json"), body);
    testInfo.attach("founding-equivalence", { body, contentType: "application/json" });
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

  expect(state.book_bound, `after ${path} founding the main-hand book is not bound to the colony${seen()}`).toBe(true);
  expect(state.holds_book_bound_to, `after ${path} founding holdsBookBoundTo does not recognize the book${seen()}`).toBe(true);
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
  const unsettled = domains.filter((line) => !line.startsWith("folkways:fear ")
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

function verdict(diff: Record<string, { fast: unknown; panel: unknown }>): string {
  const named = Object.keys(diff);
  if (named.length === 0) {
    return "both paths left the same colony state";
  }
  const spelled = named
    .map((key) => `  ${key}\n`
      + `    task      = ${JSON.stringify(diff[key].fast)}\n`
      + `    panel     = ${JSON.stringify(diff[key].panel)}`)
    .join("\n");
  return `fast founding and panel founding left different colony state, differing in ${named.length} places:\n${spelled}\n`
    + `\nfoundColonyFast is the setup founding for most specs in e2e / visual / promo - `
    + `every difference above is something missing from the world while those specs run, `
    + `but present in a colony a player founds through the panel; their failures will take other shapes, `
    + `and may not point back here.\n`
    + `Fix foundColonyFast (tests/e2e/colony-founding.ts) and the tasks it runs `
    + `(tests/e2e/colony-tasks.ts): make the fast path set this state through the mod's own entry points too, `
    + `do not change this spec's assertions, and do not add a panel click to the fast path.`;
}
