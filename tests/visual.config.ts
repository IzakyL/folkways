import { defineConfig } from "@izakyl/blockwright-test";
import { DEFAULT_BUDGET } from "./shared/budgets";

export default defineConfig({
  globalSetup: "./visual/global-setup.ts",
  globalTeardown: "./visual/visual-teardown.ts",
  testDir: "./visual",
  outputDir: "./out/visual/playwright",
  timeout: DEFAULT_BUDGET,
  workers: 4,
  retries: 0,
});
