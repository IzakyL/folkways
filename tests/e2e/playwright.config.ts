import { defineConfig } from "@izakyl/blockwright-test";
import { DEFAULT_BUDGET } from "../shared/harness/budgets";

export default defineConfig({
  globalSetup: "./global-setup.ts",
  testDir: ".",
  testMatch: "**/*.spec.ts",
  outputDir: "../out/e2e/playwright",
  timeout: DEFAULT_BUDGET,
  workers: 4,
  // Each spec is its own pair of games, and a twin spec's ground and ship tests are two: all of them run side by side.
  fullyParallel: true,
  retries: 0,
});
