import { gzipSync } from "node:zlib";
import { writeFileSync, existsSync, mkdirSync } from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";

export const DATA_VERSION = 3955;

export const SCHEMA_VERSION = 1;

type Nbt =
  | { k: "byte"; v: number }
  | { k: "int"; v: number }
  | { k: "long"; v: number | bigint }
  | { k: "string"; v: string }
  | { k: "intArray"; v: number[] }
  | { k: "list"; item: number; v: Nbt[] }
  | { k: "compound"; v: Record<string, Nbt> };

const TAG = { end: 0, byte: 1, int: 3, long: 4, string: 8, list: 9, compound: 10, intArray: 11 } as const;

const B = (v: number): Nbt => ({ k: "byte", v });
const I = (v: number): Nbt => ({ k: "int", v });
const L = (v: number | bigint): Nbt => ({ k: "long", v });
const S = (v: string): Nbt => ({ k: "string", v });
const IA = (v: number[]): Nbt => ({ k: "intArray", v });
const C = (v: Record<string, Nbt>): Nbt => ({ k: "compound", v });
const LIST = (item: number, v: Nbt[]): Nbt => ({ k: "list", item: v.length ? item : TAG.end, v });

function tagId(node: Nbt): number {
  return TAG[node.k];
}

class Writer {
  private chunks: Buffer[] = [];
  private push(b: Buffer) {
    this.chunks.push(b);
  }
  u8(v: number) {
    const b = Buffer.alloc(1);
    b.writeUInt8(v & 0xff, 0);
    this.push(b);
  }
  i16(v: number) {
    const b = Buffer.alloc(2);
    b.writeInt16BE(v, 0);
    this.push(b);
  }
  i32(v: number) {
    const b = Buffer.alloc(4);
    b.writeInt32BE(v | 0, 0);
    this.push(b);
  }
  i64(v: number | bigint) {
    const b = Buffer.alloc(8);
    b.writeBigInt64BE(BigInt(v), 0);
    this.push(b);
  }
  str(v: string) {
    const utf = Buffer.from(v, "utf8");
    this.i16(utf.length);
    this.push(utf);
  }
  payload(node: Nbt) {
    switch (node.k) {
      case "byte":
        return this.u8(node.v);
      case "int":
        return this.i32(node.v);
      case "long":
        return this.i64(node.v);
      case "string":
        return this.str(node.v);
      case "intArray":
        this.i32(node.v.length);
        for (const x of node.v) this.i32(x);
        return;
      case "list":
        this.u8(node.item);
        this.i32(node.v.length);
        for (const item of node.v) this.payload(item);
        return;
      case "compound":
        for (const [name, child] of Object.entries(node.v)) {
          this.u8(tagId(child));
          this.str(name);
          this.payload(child);
        }
        this.u8(TAG.end);
        return;
    }
  }
  buffer(): Buffer {
    return Buffer.concat(this.chunks);
  }
}

export function encodeNbtFile(root: Record<string, Nbt>): Buffer {
  const w = new Writer();
  const node = C(root);
  w.u8(TAG.compound);
  w.str("");
  w.payload(node);
  return gzipSync(w.buffer());
}

export function uuidToIntArray(uuid: string): number[] {
  const hex = uuid.replace(/-/g, "");
  const msb = BigInt("0x" + hex.slice(0, 16));
  const lsb = BigInt("0x" + hex.slice(16, 32));
  const s32 = (n: bigint) => {
    const v = Number(n & 0xffffffffn);
    return v > 0x7fffffff ? v - 0x100000000 : v;
  };
  return [s32(msb >> 32n), s32(msb), s32(lsb >> 32n), s32(lsb)];
}

export function uuidToSnbt(uuid: string): string {
  return `[I;${uuidToIntArray(uuid).join(",")}]`;
}

export function newUuids(n: number): string[] {
  return Array.from({ length: n }, () => randomUUID());
}

export const SCOPE = {
  core: "folkways:colony",
  wares: "folkways:wares",
  living: "folkways:living",
} as const;

export const KEEP = {
  front: "folkways:front",
  orders: "folkways:orders",
  build: "folkways:build",
  living: "folkways:living",
} as const;

export const PERSON_KIND = "folkways:resident";

// Whom a held block is held for: the delegation the book would hand it over under.
export const HELD = {
  store: "folkways:store",
  station: "folkways:station",
  bed: "folkways:bed",
} as const;

const PERSON_OWNER = "folkways:person";

const ZONE_DELEGATION = {
  farm: "folkways:farm",
  pasture: "folkways:pasture",
  fish: "folkways:fish",
} as const;

export type Vec3 = { x: number; y: number; z: number };

export type ItemFilterSpec = { item: string } | { tag: string };

export type SettingValue =
  | { flag: boolean }
  | { count: number }
  | { choice: string }
  | { items: ItemFilterSpec[] };

export type SettingsSpec = Record<string, SettingValue>;

// A zone covers its min..max box, or exactly the cells listed when cells is given.
export type ZoneSpec =
  | { kind: "farm"; min: Vec3; max: Vec3; cells?: Vec3[]; crop?: string }
  | { kind: "pasture"; min: Vec3; max: Vec3; cells?: Vec3[]; animal: string; target: number; shear?: boolean }
  | { kind: "fish"; min: Vec3; max: Vec3; cells?: Vec3[] };

export type OrderSpec = {
  into: Vec3;
  item: string;
  count: number;
  low?: number;
  standing: boolean;
  rank?: number;
};

export type BlueprintBlockSpec = { offset: Vec3; block: string; properties?: Record<string, string> };
export type BlueprintSpec = { id: string; name: string; blocks: BlueprintBlockSpec[]; createdGameTime?: number };
export type BuildOrderSpec = {
  id?: string;
  blueprintId: string;
  anchor: Vec3;
  clears?: Vec3[];
  playerId?: string;
  playerName?: string;
  createdGameTime?: number;
};

export type BedClaimSpec = { bed: Vec3; resident: string };

export type MemberSpec = { at: Vec3; as: keyof typeof HELD };

export type ColonySpec = {
  colonyId: string;
  residents: string[];
  members: MemberSpec[];
  zones: ZoneSpec[];
  orders: OrderSpec[];
  bedClaims?: BedClaimSpec[];
  blueprints?: BlueprintSpec[];
  buildOrders?: BuildOrderSpec[];
  settings?: Record<string, SettingsSpec>;
};

const posIA = (p: Vec3): Nbt => IA([Math.trunc(p.x), Math.trunc(p.y), Math.trunc(p.z)]);
const uuidIA = (u: string): Nbt => IA(uuidToIntArray(u));

const DIMENSION = "minecraft:overworld";

const worldPos = (p: Vec3, dim = DIMENSION): Nbt => C({ realm: C({ dim: S(dim) }), pos: posIA(p) });

function settingNbt(value: SettingValue): Nbt {
  if ("flag" in value) return C({ flag: B(value.flag ? 1 : 0) });
  if ("count" in value) return C({ count: I(value.count) });
  if ("choice" in value) return C({ choice: S(value.choice) });
  return C({
    items: LIST(
      TAG.compound,
      value.items.map((entry) => ("tag" in entry ? C({ tag: S(entry.tag) }) : C({ item: S(entry.item) }))),
    ),
  });
}

function settingsNbt(store: SettingsSpec): Nbt {
  return C(Object.fromEntries(Object.entries(store).map(([key, value]) => [key, settingNbt(value)])));
}

function packedCells(min: Vec3, max: Vec3): number[] {
  const packed: number[] = [];
  const span = (a: number, b: number) => [Math.min(a, b), Math.max(a, b)] as const;
  const [x0, x1] = span(Math.trunc(min.x), Math.trunc(max.x));
  const [y0, y1] = span(Math.trunc(min.y), Math.trunc(max.y));
  const [z0, z1] = span(Math.trunc(min.z), Math.trunc(max.z));
  for (let x = x0; x <= x1; x++) {
    for (let y = y0; y <= y1; y++) {
      for (let z = z0; z <= z1; z++) packed.push(x, y, z);
    }
  }
  return packed;
}

function zoneSettings(z: ZoneSpec): SettingsSpec {
  return z.kind === "farm"
    ? (z.crop ? { crop: { choice: z.crop } } : {})
    : z.kind === "pasture"
      ? {
        animal: { choice: z.animal },
        target: { count: z.target },
        ...(z.shear === undefined ? {} : { shear: { flag: z.shear } }),
      }
      : {};
}

function zoneHoldingNbt(z: ZoneSpec, id: string): Nbt {
  return C({
    id: uuidIA(id),
    owner: S(ZONE_DELEGATION[z.kind]),
    area: LIST(TAG.compound, [C({
      realm: C({ dim: S(DIMENSION) }),
      cells: IA(z.cells ? z.cells.flatMap((c) => [Math.trunc(c.x), Math.trunc(c.y), Math.trunc(c.z)]) : packedCells(z.min, z.max)),
    })]),
  });
}

function residentHoldingNbt(resident: string): Nbt {
  return C({ id: uuidIA(randomUUID()), owner: S(PERSON_OWNER), entity: uuidIA(resident), type: S(PERSON_KIND) });
}

function memberHoldingNbt(m: MemberSpec): Nbt {
  return C({ id: uuidIA(randomUUID()), owner: S(HELD[m.as]), block: worldPos(m.at) });
}

function blueprintBlockNbt(b: BlueprintBlockSpec): Nbt {
  const state: Record<string, Nbt> = { Name: S(b.block) };
  if (b.properties && Object.keys(b.properties).length) {
    state.Properties = C(Object.fromEntries(Object.entries(b.properties).map(([k, v]) => [k, S(v)])));
  }
  return C({ offset: posIA(b.offset), state: C(state) });
}

function blueprintNbt(bp: BlueprintSpec): Nbt {
  const size = bp.blocks.reduce(
    (extent, block) => [
      Math.max(extent[0], Math.trunc(block.offset.x) + 1),
      Math.max(extent[1], Math.trunc(block.offset.y) + 1),
      Math.max(extent[2], Math.trunc(block.offset.z) + 1),
    ],
    [0, 0, 0],
  );
  return C({
    id: uuidIA(bp.id),
    name: S(bp.name),
    createdGameTime: L(bp.createdGameTime ?? 0),
    size: IA(size),
    blocks: LIST(TAG.compound, bp.blocks.map(blueprintBlockNbt)),
  });
}

function buildOrderNbt(o: BuildOrderSpec): Nbt {
  const fields: Record<string, Nbt> = {
    id: uuidIA(o.id ?? randomUUID()),
    blueprintId: uuidIA(o.blueprintId),
    anchor: worldPos(o.anchor),
    playerName: S(o.playerName ?? "BlockwrightBot"),
    createdGameTime: L(o.createdGameTime ?? 0),
  };
  if (o.clears?.length) {
    fields.clears = IA(o.clears.flatMap((c) => [Math.trunc(c.x), Math.trunc(c.y), Math.trunc(c.z)]));
  }
  if (o.playerId) fields.playerId = uuidIA(o.playerId);
  return C(fields);
}

function orderNbt(o: OrderSpec): Nbt {
  return C({
    into: worldPos(o.into),
    what: C({ item: S(o.item) }),
    low: I(o.low ?? o.count),
    count: I(o.count),
    standing: B(o.standing ? 1 : 0),
    rank: I(Math.max(-2, Math.min(2, o.rank ?? 0))),
  });
}

function frontKeepNbt(spec: ColonySpec, zoneIds: string[]): Nbt | null {
  const fields: Record<string, Nbt> = {
    drawn: C(Object.fromEntries(spec.zones.map((z, i) => [zoneIds[i], settingsNbt(zoneSettings(z))]))),
  };
  if (spec.settings && Object.keys(spec.settings).length) {
    fields.settings = C(Object.fromEntries(
      Object.entries(spec.settings).map(([scope, store]) => [scope, settingsNbt(store)]),
    ));
  }
  return spec.zones.length || fields.settings ? C(fields) : null;
}

function keepsNbt(spec: ColonySpec, zoneIds: string[]): Nbt {
  const bags: Record<string, Nbt> = {};
  const front = frontKeepNbt(spec, zoneIds);
  if (front) bags[KEEP.front] = front;
  if (spec.orders.length) {
    bags[KEEP.orders] = C({ orders: LIST(TAG.compound, spec.orders.map(orderNbt)) });
  }
  if (spec.blueprints?.length || spec.buildOrders?.length) {
    bags[KEEP.build] = C({
      book: C({
        blueprints: LIST(TAG.compound, (spec.blueprints ?? []).map(blueprintNbt)),
        buildOrders: LIST(TAG.compound, (spec.buildOrders ?? []).map(buildOrderNbt)),
      }),
    });
  }
  if (spec.bedClaims?.length) {
    bags[KEEP.living] = C({
      claims: LIST(
        TAG.compound,
        spec.bedClaims.map((claim) => C({ resident: uuidIA(claim.resident), bed: worldPos(claim.bed) })),
      ),
    });
  }
  return C(bags);
}

export function buildColonyDatRoot(spec: ColonySpec): Record<string, Nbt> {
  const zoneIds = spec.zones.map(() => randomUUID());
  const colony: Record<string, Nbt> = {
    colonyId: uuidIA(spec.colonyId),
    holdings: C({
      held: LIST(TAG.compound, [
        ...spec.residents.map(residentHoldingNbt),
        ...spec.members.map(memberHoldingNbt),
        ...spec.zones.map((z, i) => zoneHoldingNbt(z, zoneIds[i])),
      ]),
    }),
    keeps: keepsNbt(spec, zoneIds),
  };
  return {
    data: C({
      schemaVersion: I(SCHEMA_VERSION),
      colonies: LIST(TAG.compound, [C(colony)]),
      razed: LIST(TAG.intArray, []),
    }),
    DataVersion: I(DATA_VERSION),
  };
}

export function colonyDatPath(repoRoot: string, serverInstance: string): string {
  return path.join(repoRoot, ".blockwright", "instances", serverInstance, "world", "data", "folkways_colonies.dat");
}

export function writeColonyDat(repoRoot: string, serverInstance: string, spec: ColonySpec) {
  const file = colonyDatPath(repoRoot, serverInstance);
  mkdirSync(path.dirname(file), { recursive: true });
  const bytes = encodeNbtFile(buildColonyDatRoot(spec));
  writeFileSync(file, bytes);
  return { file, bytes: bytes.length, existedDir: existsSync(path.dirname(file)) };
}
