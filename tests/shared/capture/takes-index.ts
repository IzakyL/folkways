import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { gallery } from "./gallery";
import { outAbs, type Suite } from "../harness/out-paths";

/** Gathers a suite's takes (each set's index.json) into one gallery page and one INDEX.md beside them. */
export function writeTakesIndex(suite: Suite) {
  const dir = outAbs(suite, "takes");
  if (!existsSync(dir)) return;
  const docs: any[] = [];
  for (const name of readdirSync(dir)) {
    const file = path.join(dir, name, "index.json");
    if (!existsSync(file) || !statSync(path.join(dir, name)).isDirectory()) continue;
    try {
      docs.push(JSON.parse(readFileSync(file, "utf8")));
    } catch {
    }
  }
  docs.sort((a, b) => String(a.scope).localeCompare(String(b.scope)));

  writeFileSync(path.join(dir, "index.html"), gallery(docs));

  const L: string[] = [];
  L.push(`# ${suite[0].toUpperCase()}${suite.slice(1)} output index`);
  L.push("");
  L.push(`Generated ${new Date().toISOString()} · ${docs.length} sets`);
  L.push("");
  L.push("These outputs are judged by eye: panel layout, models, resident looks, what the ponder scenes show. The status columns only say");
  L.push("the pipeline worked (captured, not empty, not all black); they do not say whether what is on screen is right.");
  L.push("");
  for (const doc of docs) {
    L.push(`## \`${doc.scope}\``);
    L.push("");
    if (doc.context && Object.keys(doc.context).length > 0) {
      L.push("World state:");
      for (const [key, value] of Object.entries(doc.context)) {
        L.push(`- \`${key}\` = ${format(value)}`);
      }
      L.push("");
    }
    const clips = (doc.entries ?? []).filter((e: any) => e.kind === "clip");
    if (clips.length > 0) {
      L.push("| Clip | Look for | Duration | Bytes | Black ratio | World advance |");
      L.push("| --- | --- | --- | --- | --- | --- |");
      for (const e of clips) {
        const g = e.gates ?? {};
        L.push(
          `| \`${e.path}\` | ${e.subject} | ${g.duration_s == null ? "?" : `${Number(g.duration_s).toFixed(1)}s`} | ` +
            `${e.bytes} | ${g.black_check === "ok" ? `${(Number(g.black_fraction) * 100).toFixed(0)}%` : String(g.black_check)} | ` +
            `${g.ticks_advanced} tick |`,
        );
      }
      L.push("");
    }
    const shots = (doc.entries ?? []).filter((e: any) => e.kind === "shot");
    if (shots.length > 0) {
      L.push("| Screenshot | Look for | Status | Bytes | World state at capture |");
      L.push("| --- | --- | --- | --- | --- |");
      for (const e of shots) {
        L.push(`| \`${e.path}\` | ${e.subject} | ${e.gates?.status} | ${e.bytes} | ${format(e.worldState)} |`);
      }
      L.push("");
    }
  }
  writeFileSync(path.join(dir, "INDEX.md"), L.join("\n") + "\n");
}

function format(value: unknown): string {
  if (value === null || value === undefined) return "-";
  if (typeof value === "object") return "`" + JSON.stringify(value) + "`";
  return String(value);
}
