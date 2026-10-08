import { defineConfig } from "@izakyl/blockwright-test";
import { DEFAULT_BUDGET } from "./shared/budgets";

export default defineConfig({
  globalSetup: "./e2e/global-setup.ts",
  testDir: "./e2e",
  outputDir: "./out/e2e/playwright",
  timeout: DEFAULT_BUDGET,
  workers: 4,
  retries: 0,
});
