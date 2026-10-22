import fs from "node:fs";
import path from "node:path";
import { validateCookeryTake } from "./cookery-validation.ts";

const manifest = path.resolve(process.argv[2] ?? "tests/out/promo/takes/cookery/index.json");
const saved = JSON.parse(fs.readFileSync(manifest, "utf8"));
const c = saved.context;
const notes: Record<string, any> = {};
validateCookeryTake({ evidence: c.activity, preRoll: c.preRoll, visitors: c.visitors,
  visitorCrew: c.visitors.visitors, cooks: c.kitchenCrew ?? c.cast.cooks, marketCrew: c.extraKitchen.workers,
  diningAreas: c.dining.areas, TABLE_MENU: c.dining.areas[0].foods, SECONDS: 240,
}, (key, value) => { notes[key] = value; });
const report = { passed: true, reviewedAt: new Date().toISOString(), ...notes };
fs.writeFileSync(path.join(path.dirname(manifest), "validation.json"), JSON.stringify(report, null, 2) + "\n");
Object.assign(saved.context, notes);
fs.writeFileSync(manifest, JSON.stringify(saved, null, 2) + "\n");
console.log(JSON.stringify({ passed: true, samples: c.activity.length,
  completedVisits: notes.visits.filter((visit: any) => visit.complete).length,
  meanEating: notes.density.meanEating, meanCooking: notes.density.meanCooking,
  report: path.join(path.dirname(manifest), "validation.json") }, null, 2));
