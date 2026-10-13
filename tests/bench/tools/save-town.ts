// node tests/bench/tools/save-town.ts <name> [key] - saves the kept town under <name> (see town-saves.ts).
import { saveTown } from "../town/town-saves.ts";

const [name, key] = process.argv.slice(2);
if (!name) {
  console.error("usage: node tests/bench/tools/save-town.ts <name> [key]");
  process.exit(2);
}
console.log(`saved ${key ?? "the latest kept town"} as ${saveTown(name, key)}`);
