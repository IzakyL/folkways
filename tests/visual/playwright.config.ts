import { defineConfig } from "@izakyl/blockwright-test";
import { DEFAULT_BUDGET } from "../shared/harness/budgets";

export default defineConfig({
  globalSetup: "./global-setup.ts",
  globalTeardown: "./visual-teardown.ts",
  testDir: ".",
  testMatch: "**/*.spec.ts",
  outputDir: "../out/visual/playwright",
  timeout: DEFAULT_BUDGET,
  workers: 4,
  retries: 0,
});
