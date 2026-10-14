import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import test from "node:test";
import { calls, patternWords } from "./patterns.ts";

const ROOT = new URL("../../", import.meta.url);
const LANG = new URL("src/main/resources/assets/folkways/lang/", ROOT);
const JAVA = new URL("src/main/java/", ROOT);
const PATTERNS = new URL("src/main/resources/data/folkways/folkways/pattern/", ROOT);
const LOCALES = ["en_us", "zh_cn", "zh_hk", "zh_tw"];

function read(url: URL): string {
  return new TextDecoder("utf-8", { fatal: true }).decode(readFileSync(url));
}

function catalog(source: string): Record<string, string> {
  const parsed = JSON.parse(source);
  assert.ok(parsed && typeof parsed === "object" && !Array.isArray(parsed), "language file must be an object");
  const tokens = source.match(/"(?:\\.|[^"\\])*"|[{}:,]/g)!;
  const keys = tokens.filter((_, index) => tokens[index + 1] === ":")
    .map(token => JSON.parse(token) as string);
  assert.equal(keys.length, new Set(keys).size, "duplicate translation key");
  for (const [key, value] of Object.entries(parsed)) {
    assert.ok(typeof value === "string" && value.trim().length > 0, `${key}: translation must be nonempty text`);
    assert.ok(!value.includes("\uFFFD"), `${key}: replacement character in translation`);
  }
  return parsed;
}

// Minecraft accepts %s, indexed %1$s, and escaped %%. Translations may reorder arguments.
function argumentsOf(text: string): number[] {
  const used: number[] = [];
  let next = 1;
  // Match Minecraft's format-token pattern; prose such as "60% of its health" is literal text.
  for (const token of text.matchAll(/%(?:(\d+)\$)?([A-Za-z%]|$)/g)) {
    assert.ok(token[0] === "%%" || (token[2] === "s" && (!token[1] || Number(token[1]) > 0)),
      `unsupported placeholder ${token[0]}`);
    if (token[0] !== "%%") used.push(token[1] ? Number(token[1]) : next++);
  }
  return [...new Set(used)].sort((a, b) => a - b);
}

const catalogs = new Map(readdirSync(LANG).filter(name => name.endsWith(".json"))
  .map(name => [name, catalog(read(new URL(name, LANG)))]));
const english = catalogs.get("en_us.json")!;
const sources = readdirSync(JAVA, { recursive: true, encoding: "utf8" }).filter(name => name.endsWith(".java"))
  .map(name => ({ name, text: read(new URL(name.replaceAll("\\", "/"), JAVA)) }));

test("language catalogs have matching keys and interpolation arguments", () => {
  assert.ok(english, "en_us is required for fallback");
  for (const locale of LOCALES) assert.ok(catalogs.has(locale + ".json"), `${locale} is required`);
  const keys = Object.keys(english).sort();
  for (const [locale, entries] of catalogs) {
    assert.deepEqual(Object.keys(entries).sort(), keys, `${locale}: missing or extra keys`);
    for (const key of keys) {
      assert.deepEqual(argumentsOf(entries[key]), argumentsOf(english[key]), `${locale}: ${key}`);
    }
  }
});

test("built-in pattern controls have complete translations in all supported locales", () => {
  let checked = 0;
  for (const file of readdirSync(PATTERNS).filter(name => name.endsWith(".star"))) {
    const source = read(new URL(file, PATTERNS));
    const words = patternWords(source);
    if (!words) continue;
    checked++;
    const keys = Object.keys(words.en_us).sort();
    const declarations = source.slice(0, source.indexOf("lang ="));
    for (const kind of ["count", "flag", "choice", "items"]) {
      for (const call of calls(declarations, kind)) {
        const key = JSON.parse(call.args[0]);
        assert.ok(Object.hasOwn(words.en_us, key), `${file}: unnamed ${kind} control ${key}`);
      }
    }
    for (const locale of LOCALES) {
      assert.ok(words[locale], `${file}: missing ${locale}`);
      assert.deepEqual(Object.keys(words[locale]).sort(), keys, `${file}: ${locale} keys`);
      assert.equal(words[locale].title, catalogs.get(locale + ".json")![`folkways.pattern.folkways.${file.slice(0, -5)}`],
        `${file}: ${locale} title must agree with its language catalog`);
      for (const key of keys) {
        assert.ok(typeof words[locale][key] === "string" && words[locale][key].trim(), `${file}: ${locale} ${key}`);
        assert.deepEqual(argumentsOf(words[locale][key]), argumentsOf(words.en_us[key]), `${file}: ${locale} ${key}`);
      }
    }
  }
  assert.ok(checked > 0, "no pattern dictionaries were checked");
});

test("Hong Kong and Taiwan wording preserves their Minecraft terminology", () => {
  for (const [locale, dataPack, track] of [["zh_hk", "數據包", "路軌"], ["zh_tw", "資料包", "鐵軌"]]) {
    const words = catalogs.get(locale + ".json")!;
    assert.ok(words["folkways.draft.grow.local"].includes(dataPack), `${locale}: data pack`);
    assert.ok(words["folkways.draft.grow.local"].includes("伺服器"), `${locale}: server`);
    assert.ok(words["folkways.draft.grow.local"].includes("資料夾"), `${locale}: folder`);
    assert.ok(words["folkways.draft.error.rail.track"].includes(track), `${locale}: rail`);
    assert.ok(words["folkways.ponder.workshop.text_1"].includes("工作台"), `${locale}: crafting table`);
    assert.ok(words["folkways.ponder.workshop.text_1"].includes("鍛造台"), `${locale}: smithing table`);
  }
});

test("built-in pattern errors keep translation keys and the correct number of arguments", () => {
  let checked = 0;
  for (const file of readdirSync(PATTERNS).filter(name => name.endsWith(".star"))) {
    const source = read(new URL(file, PATTERNS));
    assert.equal(calls(source, "fail").length, 0, `${file}: player-facing errors must use reject`);
    for (const call of calls(source, "reject")) {
      const key = JSON.parse(call.args[0]);
      assert.ok(Object.hasOwn(english, key), `${file}: ${key}`);
      assert.equal(call.args.length - 1, Math.max(0, ...argumentsOf(english[key])), `${file}: ${key} argument count`);
      assert.ok(call.args.length - 1 <= 4, `${file}: too many notice arguments`);
      checked++;
      if (call.args.includes('translated("folkways.draft.knob." + key)')) {
        const loops = [...source.slice(0, call.index).matchAll(/for key in \[([^\]]+)\]/g)];
        assert.ok(loops.length, `${file}: dynamic material key needs a check`);
        for (const material of loops.at(-1)![1].matchAll(/"([^"]+)"/g)) {
          assert.ok(Object.hasOwn(english, "folkways.draft.knob." + material[1]), `${file}: ${material[1]}`);
        }
      }
    }
  }
  assert.ok(checked > 0, "no pattern errors were checked");
});

test("catalog validation rejects duplicates, empty values, and malformed placeholders", () => {
  assert.throws(() => catalog('{\n"key": "first",\n"key": "last"\n}'), /duplicate/);
  assert.throws(() => catalog('{"key": "first", "key": "last"}'), /duplicate/);
  assert.throws(() => catalog('{"key": ""}'), /nonempty/);
  assert.throws(() => argumentsOf("%d"), /unsupported/);
  assert.throws(() => argumentsOf("%0$s"), /unsupported/);
  assert.throws(() => argumentsOf("%"), /unsupported/);
  assert.deepEqual(argumentsOf("%2$s then %1$s (100%%)"), argumentsOf("%s then %s (100%%)"));
  assert.deepEqual(argumentsOf("60% of its health"), []);
  assert.deepEqual(argumentsOf("%1$s (%1$s)"), argumentsOf("%s"));
  assert.notDeepEqual(argumentsOf("%1$s %1$s"), argumentsOf("%s %s"));
});

test("literal translation calls and file-local string constants exist in English", () => {
  let checked = 0;
  for (const { name, text } of sources) {
    const constants = new Map([...text.matchAll(/\b(?:static\s+)?final\s+String\s+(\w+)\s*=\s*("(?:\\.|[^"\\])*")\s*;/g)]
      .map(match => [match[1], JSON.parse(match[2]) as string]));
    const calls = /\b(?:Component\.translatable(?:WithFallback)?|Cue\.of)\(\s*((?:"(?:\\.|[^"\\])*"|[A-Z][A-Z_]*)(?:\s*\+\s*"(?:\\.|[^"\\])*")*)\s*(?=[,)])/g;
    for (const match of text.matchAll(calls)) {
      const parts = match[1].match(/"(?:\\.|[^"\\])*"|[A-Z][A-Z_]*/g)!;
      const values = parts.map(part => part.startsWith('"') ? JSON.parse(part) : constants.get(part));
      if (values.some(value => value === undefined)) continue;
      const key = values.join("");
      if (!key.startsWith("folkways.")) continue;
      checked++;
      assert.ok(Object.hasOwn(english, key), `${name}: ${key}`);
    }
  }
  assert.ok(checked > 0, "no translation calls were checked");
});

test("notice and refusal enums have translations for every declared value", () => {
  let checked = 0;
  for (const { name, text } of sources) {
    const declarations = [...text.matchAll(/\benum\s+(\w+)\s+implements\s+(RefusalKind(?:\.Named)?|NoticeKind)\s*\{([^;]+);/g)];
    for (let index = 0; index < declarations.length; index++) {
      const declaration = declarations[index];
      const body = text.slice(declaration.index! + declaration[0].length, declarations[index + 1]?.index);
      const prefixes = declaration[2] === "RefusalKind.Named"
        ? [`folkways.refusal.${declaration[1].replace(/Refusal$/, "").toLowerCase()}.`]
        : [...body.matchAll(/return\s+"([\w.]+)"\s*\+\s*name\(\)\.toLowerCase\(Locale\.ROOT\)/g)]
          .map(match => match[1]);
      assert.ok(prefixes.length > 0, `${name}: translation-key rule needs a check`);
      for (const constant of declaration[3].matchAll(/^\s*([A-Z][A-Z_0-9]*)\b/gm)) {
        for (const prefix of prefixes) {
          const key = prefix + constant[1].toLowerCase();
          checked++;
          assert.ok(Object.hasOwn(english, key), `${name}: ${key}`);
        }
      }
    }
  }
  assert.ok(checked > 0, "no enum translations were checked");
});
