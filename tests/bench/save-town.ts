// node tests/bench/save-town.ts <name> [key] - saves the kept town under <name> (see town-saves.ts).
import { saveTown } from "./town-saves.ts";

const [name, key] = process.argv.slice(2);
if (!name) {
  console.error("usage: node tests/bench/save-town.ts <name> [key]");
  process.exit(2);
}
console.log(`saved ${key ?? "the latest kept town"} as ${saveTown(name, key)}`);
