import { defineTask, javaTask, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../../ldlib2";
import { frames } from "../harness/bw-helpers";

/** The vocation group a vocation's column sits in, as a token, or null when it names no group. */
const GROUP_OF = defineTask<{ vocation: string }, { group: string | null }>({
  name: "folkways.vocation-groups.group-of",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.resources.ResourceLocation", "io.github.izakyl.folkways.core.vocation.api.Vocations",
      "io.github.izakyl.folkways.core.vocation.api.VocationGroup"],
    body: `
var group = Vocations.of(ResourceLocation.parse(Args.of(ctx).string("vocation"))).orElseThrow().group();
var map = new HashMap<String, Object>();
map.put("group", group.equals(VocationGroup.OTHER) ? null : group.id().toString().replace(':', '.').replace('/', '.'));
return map;
`,
  }),
});

export async function vocationGroup(server: MinecraftServer, vocation: string): Promise<string | null> {
  return (await server.run(GROUP_OF, { vocation })).group;
}

/**
 * Opens the group holding a vocation in a priority table, so that vocation's own column shows. A table shows one
 * column per group until a group is opened; `prefix` is the table's ("folkways.resident" or "folkways.work_defaults").
 * `cell` is any cell of the vocation's column; when it already shows, nothing is clicked.
 */
export async function showVocationColumn(
  server: MinecraftServer, client: MinecraftClient, prefix: string, vocation: string, cell: string,
) {
  const shown = (await panelElements(client)).find(element => element.id === cell);
  if (shown?.displayed) {
    return { opened: null };
  }
  const group = await vocationGroup(server, vocation);
  if (!group) {
    return { opened: null };
  }
  const toggle = `${prefix}.open.${group}`;
  await clickElement(client, { id: toggle });
  await frames(client, 2);
  return { opened: toggle };
}
