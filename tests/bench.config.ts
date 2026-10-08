import { defineConfig } from "@izakyl/blockwright-test";
import { BUDGET } from "./shared/budgets";

// Benches time the mod: coverage's probes would skew every number, so the driver this run spawns
// never measures, whatever BLOCKWRIGHT_COVERAGE says.
process.env.BLOCKWRIGHT_COVERAGE = "0";

export default defineConfig({
  globalSetup: "./bench/global-setup.ts",
  testDir: "./bench",
  testMatch: "**/*.bench.ts",
  timeout: BUDGET.bench,
  outputDir: "./out/bench/playwright",
  workers: 1,
  retries: 0,
});
