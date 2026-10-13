import { defineConfig } from "@izakyl/blockwright-test";
import { BUDGET } from "../shared/harness/budgets";

// Benches time the mod: coverage's probes would skew every number, so the driver this run spawns
// never measures, whatever BLOCKWRIGHT_COVERAGE says.
process.env.BLOCKWRIGHT_COVERAGE = "0";

export default defineConfig({
  globalSetup: "./global-setup.ts",
  testDir: ".",
  testMatch: "**/*.bench.ts",
  timeout: BUDGET.bench,
  outputDir: "../out/bench/playwright",
  workers: 1,
  retries: 0,
});
