import assert from "node:assert/strict";
import test from "node:test";
import { readdirSync } from "node:fs";
import { PLUGIN_SCOPES, coverageOf } from "./coverage.ts";

test("coverage registry includes every plugin directory", () => {
  const plugins = readdirSync(new URL("../../src/main/java/io/github/izakyl/folkways/plugins/", import.meta.url), { withFileTypes: true })
    .filter(d => d.isDirectory()).map(d => d.name);
  assert.deepEqual([...PLUGIN_SCOPES].sort(), plugins.sort());
});

test("placed fixtures and active-node counts do not prove completed plugin work", () => {
  const report = { setup: { population: 500 }, scale: { residents: 500 }, samples: [],
    analysis: { claimed_kinds: { beats: [{ phases: { WORKING: 400 }, completed: {} }] } } };
  const coverage = coverageOf(report);
  assert.equal(coverage.plugins.find(p => p.plugin === "wares")?.status, "not_observed");
  assert.equal(coverage.plugins.find(p => p.plugin === "golem")?.status, "not_observed");
  assert.equal(coverage.plugins.find(p => p.plugin === "person")?.status, "observed");
});

test("completion counts accumulate events and retain unobserved plugins", () => {
  const coverage = coverageOf({ setup: {}, scale: { residents: 500 }, samples: [],
    analysis: { claimed_kinds: { beats: [
      { completed: { "folkways:pasture/MilkNode": 2, "folkways:golem/MendAction": 1 } },
      { completed: { "folkways:pasture/MilkNode": 3 } },
    ] } } });
  assert.equal(coverage.completed_nodes["folkways:pasture/MilkNode"], 5);
  assert.equal(coverage.plugins.find(p => p.plugin === "pasture")?.status, "observed");
  assert.equal(coverage.plugins.find(p => p.plugin === "golem")?.status, "observed");
  assert.equal(coverage.plugins.find(p => p.plugin === "dispatch")?.status, "not_observed");
});


test("haul coverage uses completed transfers, not abstract intents", () => {
  const coverage = coverageOf({ setup: {}, scale: { residents: 500 }, samples: [],
    analysis: { claimed_kinds: { beats: [{ completed: { "folkways:haul/TransferNode": 3 } }] } } });
  const haul = coverage.plugins.find(p => p.plugin === "haul");
  assert.equal(haul?.status, "observed");
  assert.equal(haul?.completed["folkways:haul/TransferNode"], 3);
  assert.deepEqual(haul?.feature_checks, { transfer: true });
});
