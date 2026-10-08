import { expect, test } from "@playwright/test";
import { reflect, world } from "@izakyl/blockwright-minecraft";
import { PERSON_KIND, writeColonyDat, type ColonySpec } from "./colony-nbt";
import {
  colonyRegistry, zoneCellsAt, zoneChoiceSettingAt, zoneCount, zoneCountSettingAt, zoneKindAt,
} from "./zone-tools";
import { doingKind, readDoing } from "./resident-probe";
import { launchPair, type E2EPair } from "../shared/pair";
import { REPO_ROOT, ensureOut } from "../out-paths";
import { BUDGET } from "../shared/budgets";
import { introspect } from "../shared/bw-helpers";
import { seededServer } from "../shared/mod-settings";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const COLONY_ID = "00000000-0000-4000-8000-00000000c064";
const BLUEPRINT_ID = "00000000-0000-4000-8000-0000000b0001";
const RESIDENTS = [
  "00000000-0000-4000-8000-0000000000a1",
  "00000000-0000-4000-8000-0000000000a2",
];

const SPEC: ColonySpec = {
  colonyId: COLONY_ID,
  residents: RESIDENTS,
  members: [{ at: { x: 4, y: 64, z: 4 }, as: "store" }],
  zones: [
    { kind: "farm", min: { x: 10, y: 64, z: 10 }, max: { x: 12, y: 64, z: 12 }, crop: "minecraft:wheat" },
    { kind: "pasture", min: { x: 20, y: 64, z: 20 }, max: { x: 22, y: 64, z: 22 }, animal: "minecraft:sheep", target: 6 },
  ],
  orders: [{ into: { x: 4, y: 64, z: 5 }, item: "minecraft:bread", count: 32, standing: true }],
  bedClaims: [{ bed: { x: 6, y: 64, z: 6 }, resident: RESIDENTS[0] }],
  blueprints: [{
    id: BLUEPRINT_ID,
    name: "hut",
    blocks: [{ offset: { x: 0, y: 0, z: 0 }, block: "minecraft:oak_planks" }],
  }],
  buildOrders: [{ blueprintId: BLUEPRINT_ID, anchor: { x: 30, y: 64, z: 30 } }],
  settings: { "folkways:wares": { fuel: { items: [{ item: "minecraft:oak_planks" }] } } },
};

async function serviceTally(server: any, registry: string): Promise<number> {
  const said = await reflect.invoke(server, {
    target: { kind: "handle", handle: registry },
    path: "colonies()[0].works().services", method: "size",
    returnType: "int",
  });
  if (typeof said?.returned !== "number") {
    throw new Error(`works().services.size() gave no count: ${JSON.stringify(said)}`);
  }
  return said.returned;
}

async function serviceOf(server: any, registry: string, domain: string) {
  const id = await reflect.invoke(server, {
    target: { kind: "static", className: "net.minecraft.resources.ResourceLocation" },
    method: "parse", args: [domain], argTypes: ["java.lang.String"],
    returnHandle: true, limits: { maxDepth: 1, maxNodes: 8 },
  });
  const type = await reflect.invoke(server, {
    target: { kind: "static", className: "java.lang.Class" },
    method: "forName", args: ["java.lang.Object"], argTypes: ["java.lang.String"],
    returnHandle: true, limits: { maxDepth: 1, maxNodes: 8 },
  });
  const held = await reflect.invoke(server, {
    target: { kind: "handle", handle: registry },
    path: "colonies()[0].works()", method: "service",
    args: [{ $handle: id.handle }, { $handle: type.handle }],
    argTypes: ["net.minecraft.resources.ResourceLocation", "java.lang.Class"],
    returnHandle: true, limits: { maxDepth: 1, maxNodes: 16 },
  });
  const inside = await reflect.invoke(server, {
    target: { kind: "handle", handle: held.handle },
    method: "get", returnHandle: true, limits: { maxDepth: 1, maxNodes: 8 },
  });
  return JSON.stringify(await introspect(server, {
    target: { kind: "handle", handle: inside.handle },
    limits: { maxDepth: 5, maxItems: 24, maxFields: 40, maxNodes: 8192 },
  }));
}

test("the mod reads back every section of a colony save the test writes", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  const instance = seededServer(`save-seed-server-${runId}`);
  writeColonyDat(REPO_ROOT, instance, SPEC);

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { instance },
      client: { instance: `save-seed-client-${runId}` },
    });
    ({ server, client } = pair);
    const registry = await colonyRegistry(server);
    const seatedPlayer = (await world.players(server))[0]?.uuid;
    expect(typeof seatedPlayer, "need a player first so reflection has a foothold").toBe("string");

    const roster = JSON.stringify(await reflect.invoke(server, {
      target: { kind: "handle", handle: registry },
      path: "colonies()[0].holdings()", method: "residents",
      limits: { maxDepth: 3, maxItems: 8, maxNodes: 1024 },
    }));
    for (const who of RESIDENTS) {
      expect(roster, "the roster should list this person").toContain(who);
    }
    expect(roster, `the roster kind should be ${PERSON_KIND}`).toContain(PERSON_KIND);

    expect(await zoneCount(server, registry)).toBe(2);
    expect(await zoneKindAt(server, registry, 0)).toBe("folkways:farm");
    expect(await zoneCellsAt(server, registry, 0)).toBe(9);
    expect(await zoneChoiceSettingAt(server, registry, "crop", 0)).toBe("minecraft:wheat");
    expect(await zoneKindAt(server, registry, 1)).toBe("folkways:pasture");
    expect(await zoneCountSettingAt(server, registry, "target", 1)).toBe(6);

    const orders = await serviceOf(server, registry, "folkways:orders");
    expect(orders, "orders should read back with their positions").toContain("BlockPos{x=4, y=64, z=5}");
    expect(orders).toContain("minecraft:bread");

    const living = await serviceOf(server, registry, "folkways:living");
    expect(living, "bed claims should read back").toContain("BlockPos{x=6, y=64, z=6}");
    expect(living).toContain(RESIDENTS[0]);

    const build = await serviceOf(server, registry, "folkways:build");
    expect(build, "the blueprint should read back").toContain(BLUEPRINT_ID);
    expect(build, "the build order anchor should read back").toContain("minecraft:overworld@30, 64, 30");

    const settled = await serviceTally(server, registry);
    expect(settled, "a colony loaded at server start should carry its domains").toBeGreaterThan(0);

    const dropped = await reflect.invoke(server, {
      target: { kind: "entity", uuid: seatedPlayer },
      path: "level().getDataStorage().cache", method: "remove",
      args: ["folkways_colonies"], argTypes: ["java.lang.Object"],
    });
    expect(dropped?.found, "the cache should hold a colony save to swap out").toBe(true);

    const afterReload = await colonyRegistry(server);
    expect(await serviceTally(server, afterReload), "a colony read back on reload should settle too")
      .toBe(settled);
    const ordersAgain = await serviceOf(server, afterReload, "folkways:orders");
    expect(ordersAgain, "after reload the orders should still belong to this domain").toContain("minecraft:bread");

    const players = await world.players(server).catch(() => []);
    const stand = players[0]?.position ?? { x: 0, y: 64, z: 0 };
    const at = { x: Math.round(stand.x) + 2, y: Math.round(stand.y), z: Math.round(stand.z) + 2 };
    await world.command(server, `summon folkways:resident ${at.x} ${at.y} ${at.z}`);
    const listed = await world.entities(server, {
      dimension: "minecraft:overworld", types: ["folkways:resident"], limit: 8,
    }).catch(() => ({ entities: [] }));
    const who = (listed.entities ?? [])[0]?.uuid;
    expect(typeof who, "need a resident first to ask what they are doing").toBe("string");
    expect(await readDoing(server, who), "a freshly summoned resident has no job").toBeNull();

    const workingId = await reflect.invoke(server, {
      target: { kind: "static", className: "net.minecraft.resources.ResourceLocation" },
      method: "parse", args: ["folkways:working"], argTypes: ["java.lang.String"],
      returnHandle: true, limits: { maxDepth: 1, maxNodes: 8 },
    });
    const doing = await reflect.invoke(server, {
      target: { kind: "static", className: "io.github.izakyl.folkways.core.api.work.Doing" },
      method: "open", args: [{ $handle: workingId.handle }],
      argTypes: ["net.minecraft.resources.ResourceLocation"],
      returnHandle: true, limits: { maxDepth: 1, maxNodes: 8 },
    });
    await reflect.invoke(server, {
      target: { kind: "entity", uuid: who },
      method: "setDoing", args: [{ $handle: doing.handle }],
      argTypes: ["io.github.izakyl.folkways.core.api.work.Doing"],
      limits: { maxDepth: 1, maxNodes: 8 },
    });
    const said = await readDoing(server, who);
    expect(said).toBe("folkways:working");
    expect(doingKind(said)).toBe("working");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
