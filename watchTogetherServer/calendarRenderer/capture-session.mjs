import fs from "node:fs";
import path from "node:path";
import readline from "node:readline/promises";
import { chromium } from "playwright";

const outputPath = path.resolve(process.argv[2] || "renderer-state/storage-state.json");
fs.mkdirSync(path.dirname(outputPath), { recursive: true });

const browser = await chromium.launch({ channel: "chrome", headless: false });
const context = await browser.newContext({ locale: "zh-CN", timezoneId: "Asia/Shanghai" });
const page = await context.newPage();
await page.goto("https://weibo.com/login.php", { waitUntil: "domcontentloaded" });

const prompt = readline.createInterface({ input: process.stdin, output: process.stdout });
await prompt.question("Log in to the official Weibo page in the opened browser, then press Enter here to save the session: ");
prompt.close();

await context.storageState({ path: outputPath });
await browser.close();
process.stdout.write(`Session saved to ${outputPath}\n`);
