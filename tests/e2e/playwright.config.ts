import { defineConfig } from "@izakyl/blockwright-test";
import { DEFAULT_BUDGET } from "../shared/harness/budgets";

export default defineConfig({
  globalSetup: "./global-setup.ts",
  testDir: ".",
  testMatch: "**/*.spec.ts",
  outputDir: "../out/e2e/playwright",
  timeout: DEFAULT_BUDGET,
  workers: 4,
  retries: 0,
});
