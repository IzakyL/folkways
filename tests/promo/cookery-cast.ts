import { execFileSync } from "node:child_process";
import path from "node:path";
import { defineTask, javaTask, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { frames, safeAction } from "../shared/harness/bw-helpers";
import { clientHasModelFile, waitForModelsOnClient } from "../shared/ui/model-folder";
import { sleepMs, type SeededModel } from "./promo-kit";

const MODEL_NAMES = [
  "wine_fox_wedding", "wine_fox_magical", "wine_fox_tactics",
  "wine_fox_survivor", "wine_fox_hanfu", "wine_fox_momo",
  "wine_fox_nine_tailed", "wine_fox_saint", "wine_fox_matured",
] as const;

// Use the existing pinned OpenYSM sources and vendor transform, including their attribution metadata.
export const WINE_FOX_MODELS: SeededModel[] = MODEL_NAMES.map((name) => ({
  source: path.resolve("tests/promo/models", name), name, manifest: { weight: 600 },
}));

/** Run before launchPromoPair; its models seed copies these prepared folders into the server datapack. */
export function prepareCookeryModels() {
  execFileSync("python3", [path.resolve("tests/promo/models/vendor.py")], { stdio: "inherit" });
}

type ResidentLook = { uuid: string; look: string };
type CastEvidence = { residents: ResidentLook[] };
const geometry = (name: string) => `assets/folkways/geo/resident_models/${name}.geo.json`;

/** Give every actor a fixed Wine Fox appearance, and verify the client receives the same entity data. */
export async function applyCookeryLooks(server: MinecraftServer, client: MinecraftClient, uuids: string[]) {
  if (!uuids.length) throw new Error("The cookery cast must contain at least one resident");
  const arrival = await waitForModelsOnClient(client, geometry(MODEL_NAMES[0]));
  const resources = MODEL_NAMES.map((name) => ({ name, arrived: clientHasModelFile(client, geometry(name)) }));
  if (resources.some((model) => !model.arrived)) {
    throw new Error(`The cookery's Wine Fox model pack is incomplete: ${JSON.stringify(resources)}`);
  }
  const assigned = await server.run(APPLY_LOOKS, { uuids, names: [...MODEL_NAMES] });
  await tick.sprint(server, 2);
  const deadline = Date.now() + 20_000;
  let seen: CastEvidence = { residents: [] };
  do {
    await safeAction(() => frames(client, 4));
    seen = await client.run(READ_CLIENT_LOOKS, { uuids });
    if (assigned.residents.every((resident) => seen.residents.some((one) =>
      one.uuid === resident.uuid && one.look === resident.look))) {
      return { models: MODEL_NAMES, arrival, resources, server: assigned.residents, client: seen.residents };
    }
    await sleepMs(250);
  } while (Date.now() < deadline);
  throw new Error(`Elevator cast appearance did not sync: ${JSON.stringify({ server: assigned, client: seen })}`);
}

const APPLY_LOOKS = defineTask<{ uuids: string[]; names: string[] }, CastEvidence>({
  name: "folkways.promo.cookery-wine-fox-cast",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.plugins.person.ResidentEntity",
      "io.github.izakyl.folkways.plugins.person.look.ResidentLooks",
      "net.minecraft.resources.ResourceKey",
    ],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var names=args.list("names");var uuids=args.list("uuids");
var residents=new ArrayList<Map<String,Object>>();
for(int index=0;index<uuids.size();index++){
    String uuid=String.valueOf(uuids.get(index));
    if(!(level.getEntity(UUID.fromString(uuid)) instanceof ResidentEntity resident))
        throw new IllegalStateException("Missing cookery resident "+uuid);
    String prefix="model/"+names.get(index%names.size())+"/";
    var id=ResidentLooks.pool(level.registryAccess()).keySet().stream()
        .filter(key->key.getNamespace().equals("folkways")&&key.getPath().startsWith(prefix))
        .sorted(Comparator.comparing(Object::toString)).findFirst()
        .orElseThrow(()->new IllegalStateException("Missing Wine Fox model "+prefix));
    resident.setLookId(ResourceKey.create(ResidentLooks.REGISTRY,id));
    residents.add(Map.of("uuid",uuid,"look",resident.lookId().orElseThrow().location().toString()));
}
return Map.of("residents",residents);
`,
  }),
});

const READ_CLIENT_LOOKS = defineTask<{ uuids: string[] }, CastEvidence>({
  name: "folkways.promo.cookery-client-cast",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.client.Minecraft",
      "io.github.izakyl.folkways.plugins.person.ResidentEntity",
    ],
    body: `
var level=Minecraft.getInstance().level;
var wanted=new HashSet<String>();for(var uuid:Args.of(ctx).list("uuids"))wanted.add(String.valueOf(uuid));
var residents=new ArrayList<Map<String,Object>>();
if(level!=null)for(var entity:level.entitiesForRendering()){
    if(entity instanceof ResidentEntity resident&&wanted.contains(resident.getUUID().toString()))
        residents.add(Map.of("uuid",resident.getUUID().toString(),
            "look",resident.lookId().map(key->key.location().toString()).orElse("")));
}
return Map.of("residents",residents);
`,
  }),
});
