import { defineConfig } from "@izakyl/blockwright-test";
import { DEFAULT_BUDGET } from "../shared/harness/budgets";
import { ABOARD } from "../shared/harness/aboard";

export default defineConfig({
  globalSetup: "./global-setup.ts",
  testDir: ".",
  testMatch: "**/*.spec.ts",
  // A twin spec's aboard test runs in the sable suite.
  grepInvert: ABOARD,
  outputDir: "../out/e2e/playwright",
  timeout: DEFAULT_BUDGET,
  workers: 4,
  // Each spec is its own pair of games: all of them run side by side.
  fullyParallel: true,
  retries: 0,
});
