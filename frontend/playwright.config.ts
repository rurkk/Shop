import { defineConfig, devices } from "@playwright/test";

const baseURL = process.env.SHOP_TEST_URL ?? "http://127.0.0.1:18080";
const channel = process.env.SHOP_TEST_BROWSER === "chrome" ? "chrome" : undefined;

export default defineConfig({
  testDir: "./e2e",
  timeout: 90_000,
  expect: { timeout: 10_000 },
  workers: 1,
  reporter: "list",
  use: { baseURL, channel, screenshot: "only-on-failure", trace: "retain-on-failure" },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } } },
    { name: "mobile", use: { ...devices["Pixel 5"], viewport: { width: 390, height: 844 } } },
  ],
});
