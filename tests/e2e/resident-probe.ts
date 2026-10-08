import { reflect, type MinecraftServer } from "@izakyl/blockwright-minecraft";

export const RESIDENT_DOING_PATH = "doing().get()";

export const RESIDENT_DOING_WHAT_PATH = `${RESIDENT_DOING_PATH}.what`;

export type DoingVerdict = "working" | "idle" | "broken";

export function doingVerdict(error: unknown): DoingVerdict {
  const said = String(error);
  if (/NoSuchElement|hit null|path hit|is null, so/.test(said)) {
    return "idle";
  }
  return "broken";
}

async function stillDoing(server: MinecraftServer, uuid: string): Promise<boolean> {
  const present = await reflect.invoke(server, {
    target: { kind: "entity", uuid },
    path: "doing()",
    method: "isPresent",
    returnType: "boolean",
  });
  return present?.returned === true;
}

export async function readDoing(server: MinecraftServer, uuid: string): Promise<string | null> {
  let present: any;
  try {
    present = await reflect.invoke(server, {
      target: { kind: "entity", uuid },
      path: "doing()",
      method: "isPresent",
      returnType: "boolean",
    });
  } catch (error) {
    throw new Error(
      `cannot read what resident ${String(uuid).slice(0, 8)} is doing: even doing() fails`
        + ` (${String(error).slice(0, 200)}). This is not "he is idle"; the probe itself is broken, so do not treat it as a reading.`,
    );
  }
  if (present?.returned !== true) {
    return null;
  }
  const readWhat = () => reflect.invoke(server, {
    target: { kind: "entity", uuid },
    path: RESIDENT_DOING_WHAT_PATH,
    method: "toString",
    returnType: "java.lang.String",
    limits: { maxDepth: 2 },
  });
  let said: any;
  try {
    said = await readWhat();
  } catch (error) {
    if (doingVerdict(error) === "idle") {
      return null;
    }
    if (!(await stillDoing(server, uuid))) {
      return null;
    }
    try {
      said = await readWhat();
    } catch (again) {
      throw new Error(
        `resident ${String(uuid).slice(0, 8)} has a doing() value, but ${RESIDENT_DOING_WHAT_PATH} cannot be read`
          + ` (first try: ${String(error).slice(0, 120)}; re-read after recheck still failed: ${String(again).slice(0, 120)}). `
          + "Most likely the engine changed the type of Doing.what and this probe has not caught up.",
      );
    }
  }
  const id = said?.returned;
  const text = typeof id === "string" ? id : (id?.$string ?? said?.tree?.$string ?? null);
  if (typeof text !== "string" || text.length === 0) {
    throw new Error(
      `resident ${String(uuid).slice(0, 8)}: ${RESIDENT_DOING_WHAT_PATH}.toString() did not return a string`
        + ` (${JSON.stringify(said).slice(0, 200)})`,
    );
  }
  return text;
}

export function doingKind(doing: string | null | undefined): string {
  if (!doing) return "(idle)";
  const parsed = doing.match(/^[a-z0-9_.-]+:([a-z0-9_./-]+)$/);
  return parsed ? parsed[1] : doing;
}
