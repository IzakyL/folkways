import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  NEVER_HERE, PASSING, UNASSIGNED_PASSING, UNASSIGNED_TRANSIENT, UNASSIGNED_DEFERRED,
} from "./judgements.ts";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const SRC = path.join(ROOT, "src/main/java");

function walk(dir: string, into: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) walk(full, into);
    else if (name.endsWith(".java")) into.push(full);
  }
  return into;
}

function withoutComments(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/\/\/[^\n]*/g, " ");
}

function constantsOf(text: string, enumName: string): string[] {
  const head = new RegExp(`\\benum\\s+${enumName}\\b[^{]*\\{`).exec(text);
  if (!head) return [];
  const body = text.slice(head.index + head[0].length);
  const at = (mark: string) => {
    const found = body.indexOf(mark);
    return found < 0 ? Number.POSITIVE_INFINITY : found;
  };
  const end = Math.min(at(";"), at("}"));
  const listed = Number.isFinite(end) ? body.slice(0, end) : body;
  return [...listed.matchAll(/\b([A-Z][A-Z0-9_]{2,})\b/g)].map((m) => m[1]);
}

const javaFiles = walk(SRC);

const DECLARES_REFUSAL = /\benum\s+(\w+)\s+implements\s+RefusalKind\b/g;

function refusalKinds(): Map<string, string> {
  const found = new Map<string, string>();
  for (const file of javaFiles) {
    const text = withoutComments(readFileSync(file, "utf8"));
    for (const declared of text.matchAll(DECLARES_REFUSAL)) {
      const inFile = path.basename(file, ".java");
      const owner = inFile === declared[1] ? inFile : `${inFile}.${declared[1]}`;
      for (const constant of constantsOf(text, declared[1])) {
        found.set(constant, owner);
      }
    }
  }
  return found;
}

function unassignedReasons(): string[] {
  const file = javaFiles.find((f) => path.basename(f) === "Unassigned.java");
  assert.ok(file, "Unassigned.java not found - the subject of this test is gone");
  return constantsOf(withoutComments(readFileSync(file, "utf8")), "Reason");
}

test("every RefusalKind constant belongs to a table", () => {
  const kinds = refusalKinds();
  assert.ok(kinds.size > 30, `only read ${kinds.size} refusal constants, the parse is probably off`);
  const loose = [...kinds]
    .filter(([kind]) => !NEVER_HERE.has(kind) && !PASSING.has(kind))
    .map(([kind, owner]) => `${owner}.${kind}`);
  assert.deepEqual(loose, [],
    "these refusal constants are in neither NEVER_HERE nor PASSING; the bench would flag them as false-red failures/unknown-kind");
});

test("every Unassigned.Reason belongs to a table", () => {
  const tables = [UNASSIGNED_PASSING, UNASSIGNED_TRANSIENT, UNASSIGNED_DEFERRED];
  const loose = unassignedReasons().filter((reason) => !tables.some((table) => table.has(reason)));
  assert.deepEqual(loose, [], "these Unassigned.Reason values have no table, so the judge has no verdict for them");
});

test("tables hold no constants the source has already removed", () => {
  const kinds = new Set(refusalKinds().keys());
  const reasons = new Set(unassignedReasons());
  const stale = [
    ...[...NEVER_HERE, ...PASSING].filter((kind) => !kinds.has(kind)),
    ...[...UNASSIGNED_PASSING, ...UNASSIGNED_TRANSIENT, ...UNASSIGNED_DEFERRED].filter((reason) => !reasons.has(reason)),
  ];
  assert.deepEqual(stale, [], "tables still list constants that no longer exist in the source");
});
