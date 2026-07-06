#!/usr/bin/env node
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import fs from "node:fs/promises";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const runtimeRoot = path.resolve(__dirname, "..");
const repoRoot = path.resolve(runtimeRoot, "..");

const options = parseArgs(process.argv.slice(2));
const keepWorkspace = options.keep || process.env.WIZ_KEEP_SMOKE_WORKSPACE === "1";

let serverProcess;
let workspace;

try {
    const jar = await resolveRuntimeJar(options.jar);
    const port = options.port ? Number(options.port) : await freePort();
    workspace = options.workspace || path.join(os.tmpdir(), `wiz-angular-platform-browser-${Date.now()}`);

    await rm(workspace);
    await run("java", ["-jar", jar, "create", workspace, "--package", "com.reviewops.angularsmoke", "--skip-build"], { cwd: runtimeRoot });
    await injectRenderProbe(workspace);
    await run("java", ["-jar", jar, "build", "--root", workspace, "--clean"], { cwd: runtimeRoot });

    serverProcess = spawn("java", ["-jar", jar, "run", "--root", workspace, "--port", String(port)], {
        cwd: runtimeRoot,
        stdio: ["ignore", "pipe", "pipe"]
    });
    serverProcess.stdout.on("data", (chunk) => process.stdout.write(chunk));
    serverProcess.stderr.on("data", (chunk) => process.stderr.write(chunk));

    const baseUrl = `http://127.0.0.1:${port}`;
    await waitForHealth(baseUrl);
    await runBrowserAssertions(baseUrl);

    console.log(`Angular platformBrowser smoke passed: ${baseUrl}`);
} finally {
    if (serverProcess) {
        await stopProcess(serverProcess);
    }
    if (workspace && !keepWorkspace) {
        await rm(workspace);
    } else if (workspace) {
        console.log(`Kept smoke workspace: ${workspace}`);
    }
}

function parseArgs(args) {
    const parsed = {};
    for (let index = 0; index < args.length; index += 1) {
        const arg = args[index];
        if (arg === "--keep") {
            parsed.keep = true;
        } else if (arg.startsWith("--jar=")) {
            parsed.jar = arg.substring("--jar=".length);
        } else if (arg === "--jar") {
            parsed.jar = args[++index];
        } else if (arg.startsWith("--workspace=")) {
            parsed.workspace = arg.substring("--workspace=".length);
        } else if (arg === "--workspace") {
            parsed.workspace = args[++index];
        } else if (arg.startsWith("--port=")) {
            parsed.port = arg.substring("--port=".length);
        } else if (arg === "--port") {
            parsed.port = args[++index];
        } else {
            throw new Error(`Unknown argument: ${arg}`);
        }
    }
    return parsed;
}

async function resolveRuntimeJar(explicitJar) {
    if (explicitJar) {
        return path.resolve(explicitJar);
    }
    const target = path.join(runtimeRoot, "target");
    const jar = await latestRuntimeJar(target);
    if (jar) {
        return jar;
    }
    await run(path.join(runtimeRoot, "mvnw"), ["-DskipTests", "package"], { cwd: runtimeRoot });
    const built = await latestRuntimeJar(target);
    if (!built) {
        throw new Error("Unable to locate target/wiz-spring-*.jar after package");
    }
    return built;
}

async function latestRuntimeJar(target) {
    let entries = [];
    try {
        entries = await fs.readdir(target, { withFileTypes: true });
    } catch {
        return null;
    }
    const jars = [];
    for (const entry of entries) {
        if (!entry.isFile()) {
            continue;
        }
        const name = entry.name;
        if (!name.startsWith("wiz-spring-") || !name.endsWith(".jar") || name.endsWith(".jar.original")) {
            continue;
        }
        const fullPath = path.join(target, name);
        const stat = await fs.stat(fullPath);
        jars.push({ fullPath, mtimeMs: stat.mtimeMs });
    }
    jars.sort((left, right) => right.mtimeMs - left.mtimeMs);
    return jars[0]?.fullPath || null;
}

async function injectRenderProbe(root) {
    const componentRoot = path.join(root, "src/app/component.render.probe");
    const pageRoot = path.join(root, "src/app/page.rendercheck");
    await fs.mkdir(componentRoot, { recursive: true });
    await fs.mkdir(pageRoot, { recursive: true });

    await fs.writeFile(path.join(componentRoot, "app.json"), `${JSON.stringify({
        mode: "component",
        id: "component.render.probe",
        title: "render.probe",
        namespace: "render.probe",
        viewuri: "",
        category: "",
        controller: "base",
        template: "wiz-component-render-probe()"
    }, null, 4)}\n`);
    await fs.writeFile(path.join(componentRoot, "view.ts"), `import { Input, Output, EventEmitter, OnChanges } from '@angular/core';

export class Component implements OnChanges {
    @Input() value: any = 'unset';
    @Input() flag: any = false;
    @Output() changed = new EventEmitter<any>();

    public seen: string = '';
    public changes: number = 0;

    public ngOnChanges() {
        this.changes += 1;
        this.seen = \`\${this.value}:\${this.flag ? 'on' : 'off'}:\${this.changes}\`;
    }

    public emitOutput() {
        this.changed.emit({ value: \`\${this.value}:output\`, changes: this.changes });
    }
}
`);
    await fs.writeFile(path.join(componentRoot, "view.pug"), `section(data-testid="probe-child")
    div(data-testid="child-input") {{value}}
    div(data-testid="child-flag") {{flag ? 'on' : 'off'}}
    div(data-testid="child-seen") {{seen}}
    button(type="button", data-testid="child-output", (click)="emitOutput()") emit output
`);

    await fs.writeFile(path.join(pageRoot, "app.json"), `${JSON.stringify({
        mode: "page",
        id: "page.rendercheck",
        title: "/render-check",
        namespace: "rendercheck",
        viewuri: "/render-check",
        category: "",
        controller: "user",
        layout: "layout.sidebar",
        template: "wiz-page-rendercheck()"
    }, null, 4)}\n`);
    await fs.writeFile(path.join(pageRoot, "view.ts"), `import { OnInit } from '@angular/core';
import { Service } from '@wiz/libs/portal/season/service';

export class Component implements OnInit {
    constructor(public service: Service) { }

    public counter: number = 0;
    public childInput: string = 'seed';
    public directValue: string = 'initial';
    public flag: boolean = false;
    public outputValue: string = 'none';

    public async ngOnInit() {
        await this.service.init();
        await this.service.auth.allow('/access');
        await this.service.render();
    }

    public async mutateAndRender() {
        this.counter += 1;
        this.childInput = \`input-\${this.counter}\`;
        this.directValue = \`direct-\${this.counter}\`;
        this.flag = !this.flag;
        await this.service.render();
    }

    public async onChildChanged(event: any) {
        this.outputValue = event.value;
        this.directValue = \`output-\${event.changes}\`;
        await this.service.render();
    }
}
`);
    await fs.writeFile(path.join(pageRoot, "view.pug"), `nav(class="sticky top-0 z-20 bg-white shadow")
    div(class="max-w-3xl px-8 py-3")
        h1(data-testid="render-title", class="text-lg font-semibold text-zinc-950") Render Check

div(class="max-w-3xl p-8 space-y-4")
    div(class="flex gap-3")
        a(data-testid="render-to-dashboard", routerLink="/dashboard") Dashboard Link
        a(data-testid="render-to-posts", routerLink="/posts") Posts Link
    div(data-testid="parent-counter") {{counter}}
    div(data-testid="parent-direct") {{directValue}}
    div(data-testid="parent-output") {{outputValue}}
    wiz-component-render-probe([value]="childInput", [flag]="flag", (changed)="onChildChanged($event)")
    button(type="button", data-testid="mutate-render", (click)="mutateAndRender()") mutate render
`);
}

async function runBrowserAssertions(baseUrl) {
    const { chromium } = await ensurePlaywright();
    const browser = await chromium.launch({ headless: true });
    const pageErrors = [];
    const context = await browser.newContext({ baseURL: baseUrl });
    const page = await context.newPage();
    page.on("pageerror", (error) => pageErrors.push(error.message));

    try {
        await page.goto("/access", { waitUntil: "domcontentloaded" });
        await page.locator('input[type="email"]').fill("admin@example.com");
        await page.locator('input[type="password"]').fill("admin1234");
        await Promise.all([
            page.waitForURL("**/dashboard", { timeout: 20000 }),
            page.getByRole("button", { name: "로그인" }).click()
        ]);
        await expectText(page, "h1", "Dashboard");

        await clickHrefAndExpect(page, "/posts", /\/posts$/, "h1", "게시물");
        await clickHrefAndExpect(page, "/members", /\/members$/, "h1", "멤버");
        await clickHrefAndExpect(page, "/mypage", /\/mypage$/, "h1", "내 프로필");
        await clickHrefAndExpect(page, "/dashboard", /\/dashboard$/, "h1", "Dashboard");

        await page.goto("/render-check", { waitUntil: "domcontentloaded" });
        await expectTestId(page, "render-title", "Render Check");
        await expectTestId(page, "parent-counter", "0");
        await expectTestId(page, "parent-direct", "initial");
        await expectTestId(page, "parent-output", "none");
        await expectTestId(page, "child-input", "seed");
        await expectTestId(page, "child-flag", "off");
        await expectTestId(page, "child-seen", "seed:off:1");

        await page.locator('[data-testid="mutate-render"]').click();
        await expectTestId(page, "parent-counter", "1");
        await expectTestId(page, "parent-direct", "direct-1");
        await expectTestId(page, "child-input", "input-1");
        await expectTestId(page, "child-flag", "on");
        await expectTestId(page, "child-seen", "input-1:on:2");

        await page.locator('[data-testid="child-output"]').click();
        await expectTestId(page, "parent-output", "input-1:output");
        await expectTestId(page, "parent-direct", "output-2");

        await page.locator('[data-testid="render-to-posts"]').click();
        await page.waitForURL("**/posts", { timeout: 10000 });
        await expectText(page, "h1", "게시물");
        await page.goto("/render-check", { waitUntil: "domcontentloaded" });
        await page.locator('[data-testid="render-to-dashboard"]').click();
        await page.waitForURL("**/dashboard", { timeout: 10000 });
        await expectText(page, "h1", "Dashboard");

        if (pageErrors.length > 0) {
            throw new Error(`Browser page errors:\n${pageErrors.join("\n")}`);
        }
    } finally {
        await context.close();
        await browser.close();
    }
}

async function clickHrefAndExpect(page, href, urlPattern, selector, expectedText) {
    await page.locator(`a[href="${href}"]:visible`).first().click();
    await page.waitForURL(urlPattern, { timeout: 10000 });
    await expectText(page, selector, expectedText);
}

async function expectTestId(page, testId, expectedText) {
    await page.waitForFunction(
        ({ testId, expectedText }) => document.querySelector(`[data-testid="${testId}"]`)?.textContent?.trim() === expectedText,
        { testId, expectedText },
        { timeout: 10000 }
    );
}

async function expectText(page, selector, expectedText) {
    await page.waitForFunction(
        ({ selector, expectedText }) => document.querySelector(selector)?.textContent?.trim() === expectedText,
        { selector, expectedText },
        { timeout: 10000 }
    );
}

async function ensurePlaywright() {
    if (process.env.WIZ_PLAYWRIGHT_NODE_PATH) {
        const require = createRequire(path.join(process.env.WIZ_PLAYWRIGHT_NODE_PATH, "package.json"));
        return require("playwright");
    }

    const toolsDir = process.env.WIZ_PLAYWRIGHT_TOOLS_DIR || path.join(os.tmpdir(), "wiz-spring-playwright-tools");
    await fs.mkdir(toolsDir, { recursive: true });
    try {
        const require = createRequire(path.join(toolsDir, "package.json"));
        return require("playwright");
    } catch {
        await fs.writeFile(path.join(toolsDir, "package.json"), JSON.stringify({ private: true, type: "commonjs" }, null, 2));
        const pkg = process.env.WIZ_PLAYWRIGHT_PACKAGE || "playwright@latest";
        await run(npmCommand(), ["install", "--no-audit", "--no-fund", pkg], { cwd: toolsDir });
        await run(npxCommand(), ["playwright", "install", "chromium"], { cwd: toolsDir });
        const require = createRequire(path.join(toolsDir, "package.json"));
        return require("playwright");
    }
}

async function waitForHealth(baseUrl) {
    const deadline = Date.now() + 60000;
    while (Date.now() < deadline) {
        try {
            const response = await fetch(`${baseUrl}/actuator/health`);
            if (response.ok) {
                const body = await response.json();
                if (body.status === "UP") {
                    return;
                }
            }
        } catch {
            // Server is still starting.
        }
        await sleep(500);
    }
    throw new Error(`Server did not become healthy: ${baseUrl}`);
}

function run(command, args, options = {}) {
    return new Promise((resolve, reject) => {
        console.log(`$ ${[command, ...args].join(" ")}`);
        const child = spawn(command, args, {
            cwd: options.cwd || repoRoot,
            env: { ...process.env, ...(options.env || {}) },
            stdio: ["ignore", "pipe", "pipe"]
        });
        let output = "";
        child.stdout.on("data", (chunk) => {
            output += chunk;
            process.stdout.write(chunk);
        });
        child.stderr.on("data", (chunk) => {
            output += chunk;
            process.stderr.write(chunk);
        });
        child.on("error", reject);
        child.on("close", (code) => {
            if (code === 0) {
                resolve(output);
            } else {
                reject(new Error(`Command failed (${code}): ${command} ${args.join(" ")}`));
            }
        });
    });
}

function freePort() {
    return new Promise((resolve, reject) => {
        const server = net.createServer();
        server.on("error", reject);
        server.listen(0, "127.0.0.1", () => {
            const address = server.address();
            server.close(() => resolve(address.port));
        });
    });
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

async function stopProcess(child) {
    if (child.exitCode !== null || child.signalCode !== null) {
        return;
    }
    child.kill("SIGINT");
    await Promise.race([
        new Promise((resolve) => child.once("close", resolve)),
        sleep(10000).then(() => {
            if (child.exitCode === null && child.signalCode === null) {
                child.kill("SIGKILL");
            }
        })
    ]);
}

async function rm(target) {
    await fs.rm(target, { recursive: true, force: true });
}

function npmCommand() {
    return process.platform === "win32" ? "npm.cmd" : "npm";
}

function npxCommand() {
    return process.platform === "win32" ? "npx.cmd" : "npx";
}
