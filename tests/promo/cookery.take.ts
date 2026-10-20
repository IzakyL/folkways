import { expect, test } from "@playwright/test";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { defineTask, javaTask, player, screen, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { setMaintainDemandInView } from "../shared/colony/membership-optin";
import { openContainerScreen } from "../shared/ui/site-panel";
import { commandPos, frames } from "../shared/harness/bw-helpers";
import { shaderEvidence } from "../shared/capture/shaders";
import { admitPromoSettlersFast, foundPromoColonyFast } from "./fast-setup";
import { dressCookeryGarden } from "./cookery-set";
import { cinematicWorld, hideHud, launchPromoPair, promoClip, setFov, setTickRate, sleepMs, take, type PromoPair } from "./promo-kit";

// One order, only raw ingredients: residents make every intermediate themselves.
// The set is dressed by commands, as in declare.take.ts; production uses the real native appliances.
const K = "kaleidoscope_cookery:";
const DISH = K + "baozi_plate";
const RAW = ["minecraft:wheat", "minecraft:porkchop", K + "green_chili", "minecraft:water_bucket",
  "minecraft:oak_planks", "minecraft:iron_ingot", "minecraft:brick"];
const PRODUCTS = ["flour", "raw_dough", "stuffed_dough_food", "baozi", "baozi_plate"].map(id => K + id);

test("cookery: an order becomes a freshly steamed plate of baozi", async ({}, testInfo) => {
  const run = take("cookery");
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair("cookery", { config: "blockwright.toml#promo-cookery" });
    const { server, client, clientInstance } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const name = grounded.name;
    await world.command(server, `op ${name}`);
    await cinematicWorld(server);
    await world.command(server, "time set 3500");
    const o = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
    const at = (x: number, z: number, y = 0): Vec => ({ x: o.x + x, y: o.y + y, z: o.z + z });
    const cells = {
      food: at(-6, 3), source: at(-6, 5), store: at(-6, 7), target: at(1, 2), table: at(-3, 5), board: at(0, 5, 1),
      stockpot: at(4, 8, 1), pot: at(4, 11, 1), steamer: at(4, 14, 1), mill: at(-3, 11),
    };
    const set = async (p: Vec, block: string) => world.command(server, `setblock ${commandPos(p)} ${block}`);
    const scenery = await dressCookeryGarden(server, o);
    const beds = scenery.beds;
    run.note("scenery", scenery);
    for (const key of ["food", "store", "target"] as const) await set(cells[key], "minecraft:chest[facing=north]{Items:[]}");
    await world.command(server, `item replace block ${commandPos(cells.food)} container.0 with minecraft:cooked_beef 64`);
    await set(cells.source, `folkways:creative_supply_box{all:0b,whitelist:${JSON.stringify(RAW.map(id => "i" + id))}}`);
    await set(cells.table, "minecraft:crafting_table");
    await set(cells.board, K + "chopping_board");
    for (const key of ["stockpot", "pot", "steamer"] as const) {
      await set({ ...cells[key], y: cells[key].y - 1 }, K + "stove[facing=west,lit=true]");
      await set(cells[key], K + key);
    }
    await set(cells.mill, K + "millstone[part=center]");
    for (const [dx, dz, part] of [[-1,-1,"left_up"],[0,-1,"up"],[1,-1,"right_up"],[-1,0,"left_center"],
      [1,0,"right_center"],[-1,1,"left_down"],[0,1,"down"],[1,1,"right_down"]] as const) {
      await set({ x: cells.mill.x + dx, y: cells.mill.y, z: cells.mill.z + dz }, K + `millstone[part=${part}]`);
    }
    await world.command(server, `tp ${name} ${o.x} ${o.y} ${o.z + 2}`);
    await world.command(server, `item replace entity ${name} weapon.mainhand with folkways:colony_book`);
    await tick.sprint(server, 10);
    const founded = await foundPromoColonyFast(server, name, [...Object.values(cells), ...beds]);
    run.note("founded", founded);
    run.note("admitted", await admitPromoSettlersFast(server, name, founded.colony_id, 3));
    const residents = (await world.entities(server, { types: ["folkways:resident"], limit: 20 })).entities;
    expect(residents).toHaveLength(3);
    run.note("cast", await server.run(EMPTY_PACKS, { uuids: residents.map(one => one.uuid) }));
    for (const [i, resident] of residents.entries()) {
      const post = at(-4 + i * 3, 7);
      await world.command(server, `tp ${resident.uuid} ${post.x + 0.5} ${post.y} ${post.z + 0.5}`);
    }
    await world.command(server, `gamemode survival ${name}`);
    await world.command(server, "gamerule fallDamage false");
    await world.command(server, `attribute ${name} minecraft:player.block_interaction_range base set 64`);
    await setFov(client, 65);
    const view = async (feet: Vec, aim: Vec) => {
      await set({ ...feet, y: feet.y - 1 }, "minecraft:barrier");
      const look = cameraLookingAt({ x: feet.x + 0.5, y: feet.y + 1.62, z: feet.z + 0.5 },
        { x: aim.x + 0.5, y: aim.y + 0.5, z: aim.z + 0.5 });
      await world.command(server, `tp ${name} ${feet.x + 0.5} ${feet.y} ${feet.z + 0.5} ${look.yaw} ${look.pitch}`);
      await tick.sprint(server, 5);
      await player.place(client, look);
      await frames(client, 30);
      await sleepMs(1500);
    };
    await screen.dismiss(client);
    await view(at(-11, -3, 5), at(0, 10, 1));
    await world.command(server, `item replace entity ${name} weapon.mainhand with minecraft:air`);
    await hideHud(client);
    await sleepMs(10_000);
    await promoClip(run, client, server, "01-kitchen", {
      subject: "Sunlit courtyard kitchen, native millstone and steaming appliances, three residents ready to cook",
      worldState: { cells, raw: RAW }, seconds: 6,
    });
    await hideHud(client);
    await view(at(1, -2, 3), cells.target);
    await world.command(server, `item replace entity ${name} weapon.mainhand with folkways:colony_book`);
    const before = await snapshot(server, cells);
    run.note("before", before);
    for (const id of PRODUCTS) expect(JSON.stringify(before), "no ready-made intermediate before ordering").not.toContain(`"id":"${id}"`);
    await promoClip(run, client, server, "02-order", {
      subject: "The player orders one plate of baozi using the real colony book UI",
      worldState: { dish: DISH, count: 1 }, seconds: 1,
      during: async () => {
        await sleepMs(1200);
        run.note("order", await setMaintainDemandInView(server, client, cells.target, DISH, 1, 650));
        await sleepMs(1200);
      },
    });
    await screen.dismiss(client);
    await world.command(server, `item replace entity ${name} weapon.mainhand with minecraft:air`);
    await view(at(-10, 1, 5), at(0, 10, 1));
    await hideHud(client);
    const marks: Record<string, number> = {};
    const samples: any[] = [];
    let completed = false;
    await promoClip(run, client, server, "03-chain", {
      subject: "Residents mill wheat, prepare dough and filling, steam baozi, assemble and deliver the plate",
      worldState: { cells, products: PRODUCTS, rawOnlySupply: RAW }, seconds: 1,
      during: async () => {
        const started = Date.now();
        const mark = () => (Date.now() - started) / 1000;
        await setTickRate(server, 40);
        try {
          while (mark() < 600) {
            const sample = await snapshot(server, cells);
            samples.push({ seconds: mark(), ...sample });
            const items = (sample.target as any)?.Items ?? [];
            if (items.some((stack: any) => stack.id === DISH && Number(stack.count ?? 1) >= 1)) {
              completed = true;
              marks.done = mark();
              break;
            }
            await sleepMs(1000);
          }
        } finally {
          await setTickRate(server, 20);
          await sleepMs(4000);
          marks.end = mark();
          run.note("chain", { marks, completed, samples });
        }
      },
    });
    expect(completed, "the native cooking chain must deliver the real dish before the reveal").toBe(true);
    const source = (await world.block(server, cells.source, { nbt: true })).nbt as any;
    const generated = source.generated ?? {};
    expect(Object.keys(generated).every(id => RAW.includes(id)), "only raw inputs may come from the source").toBe(true);
    for (const id of PRODUCTS) expect(Number(generated[id] ?? 0), id + " must be made, never supplied").toBe(0);
    run.note("generated", generated);
    await hideHud(client);
    await view(at(1, -1, 1), cells.target);
    await world.command(server, `item replace entity ${name} weapon.mainhand with minecraft:air`);
    run.note("reveal", await openContainerScreen(client, cells.target));
    await promoClip(run, client, server, "04-result", {
      subject: "The completed plate of baozi in the destination chest", worldState: { completed, generated }, seconds: 6,
    });
    await screen.dismiss(client);
    run.note("shaders", shaderEvidence(clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function snapshot(server: MinecraftServer, cells: Record<string, Vec>) {
  return Object.fromEntries(await Promise.all(Object.entries(cells).map(async ([key, at]) =>
    [key, (await world.block(server, at, { nbt: true })).nbt] as const)));
}

const EMPTY_PACKS = defineTask<{ uuids: string[] }, any>({
  name: "folkways.promo.cookery-empty-packs", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.api.resident.body.Bodies"], body: `
var ids = Args.of(ctx).strings("uuids");
for (var id : ids) {
    var entity = ctx.server().overworld().getEntity(UUID.fromString(id));
    Bodies.of(entity).orElseThrow().pack().clearContent();
}
return Map.of("emptied", ids);
` }),
});
