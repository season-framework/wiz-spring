#!/usr/bin/env node

import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptRoot = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptRoot, "..");
const options = parseArgs(process.argv.slice(2));
if (options.help) {
    process.stdout.write(`${usage()}\n`);
    process.exit(0);
}
const temporaryRoot = await fs.mkdtemp(path.join(os.tmpdir(), "wiz-build-benchmark-"));
const output = path.resolve(options.output || path.join(projectRoot, "target/build-performance-benchmark.json"));

try {
    const beforeJar = await requireFile(options.beforeJar, "--before-jar");
    const afterJar = await requireFile(options.afterJar, "--after-jar");
    const versions = [
        { name: "before", jar: beforeJar },
        { name: "after", jar: afterJar }
    ];
    const measurements = [];

    const scenarios = [
        {
            name: "maven-repeat-compile",
            phase: "compile",
            iterations: options.iterations,
            setup: setupMavenFixture,
            warmupClean: false,
            measuredClean: false
        },
        {
            name: "boot-classpath-repeat-compile",
            phase: "compile",
            iterations: options.iterations,
            setup: setupBootFixture,
            warmupClean: false,
            measuredClean: false
        }
    ];
    if (options.includeAngular) {
        scenarios.push({
                name: "angular-repeat-bundle",
                phase: "bundle",
                iterations: options.angularIterations,
                setup: setupAngularFixture,
                warmupClean: true,
                // Both implementations preserve staged node_modules on a
                // normal rebuild. The candidate additionally preserves the
                // Angular incremental cache and validates dependency metadata.
                measuredClean: false
        });
    }
    for (const scenario of scenarios) {
        measurements.push(...await benchmarkComparison(versions, scenario));
    }

    const result = {
        schemaVersion: 1,
        measuredAt: new Date().toISOString(),
        baseline: options.baseline || "git HEAD",
        candidate: options.candidate || "working tree",
        host: {
            platform: `${os.platform()} ${os.release()} ${os.arch()}`,
            cpus: os.cpus().length,
            totalMemoryBytes: os.totalmem(),
            node: process.version,
            java: firstLine(await commandOutput("java", ["-version"])),
            maven: firstLine(await commandOutput("mvn", ["-version"]))
        },
        iterations: options.iterations,
        angularIterations: options.includeAngular ? options.angularIterations : 0,
        jars: {
            before: await fileIdentity(beforeJar),
            after: await fileIdentity(afterJar)
        },
        methodology: {
            warmupsPerScenario: 1,
            statistic: "median and nearest-rank p95",
            maven: "No Java source; isolates app-dependencies. The candidate reuses its verified dependency cache after warmup.",
            bootClasspath: "No workspace pom.xml; java-compile includes Boot JAR classpath preparation and javac/package work.",
            angular: options.includeAngular
                ? "Both versions receive one clean warmup followed by normal builds. Both retain node_modules; the candidate additionally retains Angular's incremental cache and validates dependency metadata."
                : "not measured"
        },
        measurements,
        summary: summarize(measurements)
    };

    await fs.mkdir(path.dirname(output), { recursive: true });
    await fs.writeFile(output, `${JSON.stringify(result, null, 2)}\n`);
    process.stdout.write(`${markdownSummary(result.summary)}\n`);
    process.stdout.write(`\nRaw result: ${output}\n`);
    if (options.keep) {
        process.stdout.write(`Benchmark workspace: ${temporaryRoot}\n`);
    }
} finally {
    if (!options.keep) {
        await fs.rm(temporaryRoot, { recursive: true, force: true });
    }
}

async function benchmarkComparison(versions, scenario) {
    const contexts = [];
    for (const version of versions) {
        const root = path.join(temporaryRoot, version.name, scenario.name);
        const workspace = path.join(root, "workspace");
        const state = path.join(root, "state");
        await scenario.setup(workspace);
        await fs.mkdir(state, { recursive: true });
        contexts.push({ version, workspace, state });
        process.stderr.write(`warmup ${version.name}/${scenario.name}\n`);
        await runBuild(version.jar, workspace, state, scenario.phase, scenario.warmupClean);
    }

    const measurements = [];
    for (let iteration = 1; iteration <= scenario.iterations; iteration += 1) {
        // Alternating AB/BA order reduces thermal and host-load bias while
        // keeping each version's workspace and caches isolated.
        const ordered = iteration % 2 === 1 ? contexts : [...contexts].reverse();
        for (const context of ordered) {
            const { version, workspace, state } = context;
            process.stderr.write(`measure ${version.name}/${scenario.name} ${iteration}/${scenario.iterations}\n`);
            const measured = await runBuild(
                version.jar,
                workspace,
                state,
                scenario.phase,
                scenario.measuredClean
            );
            const totalBuildMillis = durationFromLog(measured.log, "Total build time");
            const phases = phaseDurations(measured.log);
            const commands = commandDurations(measured.log);
            const signals = {
                mavenCacheHit: measured.log.includes("[app-dependencies] cache hit:"),
                frontendInstallSkipped: measured.log.includes("[frontend-install] skipped"),
                bootClasspathCacheHit: measured.log.includes("[java-classpath-cache] hit")
            };
            await validateMeasurement(
                scenario,
                version,
                workspace,
                totalBuildMillis,
                phases,
                commands,
                signals
            );
            measurements.push({
                scenario: scenario.name,
                version: version.name,
                iteration,
                command: `build --phase ${scenario.phase}${scenario.measuredClean ? " --clean" : ""}`,
                wallMillis: measured.wallMillis,
                totalBuildMillis,
                phases,
                commands,
                signals
            });
        }
    }
    return measurements;
}

async function validateMeasurement(scenario, version, workspace, totalBuildMillis, phases, commands, signals) {
    const requiredPhase = {
        "maven-repeat-compile": "app-dependencies",
        "boot-classpath-repeat-compile": "java-compile",
        "angular-repeat-bundle": "frontend"
    }[scenario.name];
    if (!Number.isFinite(totalBuildMillis) || !Number.isFinite(phases[requiredPhase])) {
        throw new Error(`Required duration is missing from ${version.name}/${scenario.name}`);
    }
    if (scenario.name === "maven-repeat-compile" && version.name === "after" && !signals.mavenCacheHit) {
        throw new Error("Candidate Maven dependency cache did not hit after warmup");
    }
    if (scenario.name === "boot-classpath-repeat-compile" && version.name === "after" && !signals.bootClasspathCacheHit) {
        throw new Error("Candidate Boot classpath cache did not hit after warmup");
    }
    if (scenario.name === "angular-repeat-bundle" && !signals.frontendInstallSkipped) {
        throw new Error(`${version.name} Angular dependencies were unexpectedly reinstalled`);
    }
    if (scenario.name === "angular-repeat-bundle" && !Number.isFinite(commands["frontend-build"])) {
        throw new Error(`${version.name} Angular CLI build duration is missing`);
    }
    const artifact = {
        "maven-repeat-compile": path.join(workspace, "build/target/dependency/sqlite-jdbc-3.49.1.0.jar"),
        "boot-classpath-repeat-compile": path.join(workspace, "build/target/app-api.jar"),
        "angular-repeat-bundle": path.join(workspace, "build/target/frontend/index.html")
    }[scenario.name];
    const stats = await fs.stat(artifact).catch(() => null);
    if (!stats?.isFile() || stats.size === 0) {
        throw new Error(`Expected artifact is missing from ${version.name}/${scenario.name}: ${artifact}`);
    }
}

async function runBuild(jar, workspace, stateRoot, phase, clean) {
    const argv = ["-jar", jar, "build", "--root", workspace, "--phase", phase];
    if (clean) {
        argv.push("--clean");
    }
    const environment = {
        ...process.env,
        WIZ_SPRING_RUNTIME_DIR: path.join(stateRoot, "runtime"),
        WIZ_SPRING_CACHE_DIR: path.join(stateRoot, "cache"),
        WIZ_SPRING_STATE_DIR: path.join(stateRoot, "state"),
        NO_COLOR: "1"
    };
    const started = process.hrtime.bigint();
    const completed = await run("java", argv, { cwd: projectRoot, env: environment });
    const wallMillis = Number(process.hrtime.bigint() - started) / 1_000_000;
    if (completed.code !== 0) {
        throw new Error(`Build failed (${completed.code}): java ${argv.join(" ")}\n${completed.output}`);
    }
    return { wallMillis, log: completed.output };
}

async function setupMavenFixture(root) {
    await setupWorkspace(root);
    await fs.writeFile(path.join(root, "pom.xml"), `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.wiz.benchmark</groupId>
  <artifactId>maven-cache-benchmark</artifactId>
  <version>1.0.0</version>
  <dependencies>
    <dependency>
      <groupId>org.xerial</groupId>
      <artifactId>sqlite-jdbc</artifactId>
      <version>3.49.1.0</version>
    </dependency>
  </dependencies>
</project>
`);
}

async function setupBootFixture(root) {
    await setupWorkspace(root);
    const app = path.join(root, "src/app/page.benchmark");
    await fs.mkdir(app, { recursive: true });
    await fs.writeFile(path.join(app, "api.java"), `public final class PageBenchmarkApi {
    public String value() { return "benchmark"; }
}
`);
}

async function setupAngularFixture(root) {
    const template = path.resolve(options.angularTemplate
        || path.join(projectRoot, "src/main/resources/wiz/templates/default-project-java"));
    await fs.cp(template, root, { recursive: true });
    await fs.rm(path.join(root, "pom.xml"), { force: true });
    await removeJavaFiles(path.join(root, "src"));
    await rewriteTemplateTokens(root);
    await fs.mkdir(path.join(root, "config"), { recursive: true });
    await fs.writeFile(path.join(root, "config/wiz.yml"), "workspace: java\n");
}

async function setupWorkspace(root) {
    await fs.mkdir(path.join(root, "src/app"), { recursive: true });
    await fs.mkdir(path.join(root, "config"), { recursive: true });
    await fs.writeFile(path.join(root, "config/application.yml"), `wiz:
  java:
    package-root: com.wiz.benchmark
`);
    await fs.writeFile(path.join(root, "config/wiz.yml"), "workspace: java\n");
}

async function removeJavaFiles(root) {
    let entries = [];
    try {
        entries = await fs.readdir(root, { withFileTypes: true });
    } catch (error) {
        if (error.code === "ENOENT") {
            return;
        }
        throw error;
    }
    for (const entry of entries) {
        const target = path.join(root, entry.name);
        if (entry.isDirectory()) {
            await removeJavaFiles(target);
        } else if (entry.isFile() && entry.name.endsWith(".java")) {
            await fs.rm(target);
        }
    }
}

async function rewriteTemplateTokens(root) {
    const rewritable = new Set([".java", ".yml", ".yaml", ".json"]);
    async function visit(directory) {
        for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
            const target = path.join(directory, entry.name);
            if (entry.isDirectory()) {
                await visit(target);
            } else if (entry.isFile() && rewritable.has(path.extname(entry.name))) {
                const source = await fs.readFile(target, "utf8");
                const rewritten = source.replaceAll("__WIZ_PACKAGE_ROOT__", "com.wiz.benchmark");
                if (rewritten !== source) {
                    await fs.writeFile(target, rewritten);
                }
            }
        }
    }
    await visit(root);
}

function phaseDurations(log) {
    const result = {};
    const pattern = /^\[([^\]]+)] done in ([0-9.]+(?:ms|s))$/gm;
    for (const match of log.matchAll(pattern)) {
        result[match[1]] = durationMillis(match[2]);
    }
    return result;
}

function commandDurations(log) {
    const result = {};
    const pattern = /^\[([^\]]+)] exitCode=.* duration=([0-9.]+(?:ms|s))/gm;
    for (const match of log.matchAll(pattern)) {
        result[match[1]] = durationMillis(match[2]);
    }
    return result;
}

function durationFromLog(log, label) {
    const pattern = new RegExp(`^${escapeRegExp(label)}: ([0-9.]+(?:ms|s))$`, "m");
    const match = log.match(pattern);
    return match ? durationMillis(match[1]) : null;
}

function durationMillis(value) {
    if (value.endsWith("ms")) {
        return Number(value.substring(0, value.length - 2));
    }
    return Number(value.substring(0, value.length - 1)) * 1000;
}

function summarize(measurements) {
    const scenarios = [...new Set(measurements.map(item => item.scenario))];
    const primaryPhase = {
        "maven-repeat-compile": "app-dependencies",
        "boot-classpath-repeat-compile": "java-compile",
        "angular-repeat-bundle": "frontend"
    };
    const result = [];
    for (const scenario of scenarios) {
        const values = {};
        for (const version of ["before", "after"]) {
            const selected = measurements.filter(item => item.scenario === scenario && item.version === version);
            values[version] = {
                samples: selected.length,
                wallMedianMillis: median(selected.map(item => item.wallMillis)),
                wallP95Millis: percentile(selected.map(item => item.wallMillis), 0.95),
                buildMedianMillis: median(selected.map(item => item.totalBuildMillis)),
                buildP95Millis: percentile(selected.map(item => item.totalBuildMillis), 0.95),
                primaryPhase: primaryPhase[scenario],
                primaryPhaseMedianMillis: median(selected.map(item => item.phases[primaryPhase[scenario]])),
                primaryPhaseP95Millis: percentile(
                    selected.map(item => item.phases[primaryPhase[scenario]]),
                    0.95
                ),
                cacheHits: selected.filter(item => Object.values(item.signals).some(Boolean)).length
            };
        }
        const phaseNames = [...new Set(measurements
            .filter(item => item.scenario === scenario)
            .flatMap(item => Object.keys(item.phases)))];
        const phases = {};
        for (const phase of phaseNames) {
            const beforeValues = measurements
                .filter(item => item.scenario === scenario && item.version === "before")
                .map(item => item.phases[phase]);
            const afterValues = measurements
                .filter(item => item.scenario === scenario && item.version === "after")
                .map(item => item.phases[phase]);
            phases[phase] = {
                beforeMedianMillis: median(beforeValues),
                beforeP95Millis: percentile(beforeValues, 0.95),
                afterMedianMillis: median(afterValues),
                afterP95Millis: percentile(afterValues, 0.95),
                reductionPercent: reduction(median(beforeValues), median(afterValues))
            };
        }
        result.push({
            scenario,
            ...values,
            phases,
            wallReductionPercent: reduction(values.before.wallMedianMillis, values.after.wallMedianMillis),
            buildReductionPercent: reduction(values.before.buildMedianMillis, values.after.buildMedianMillis),
            primaryPhaseReductionPercent: reduction(
                values.before.primaryPhaseMedianMillis,
                values.after.primaryPhaseMedianMillis
            )
        });
    }
    return result;
}

function markdownSummary(summary) {
    const rows = [
        "| Scenario | Before wall median / p95 | After wall median / p95 | Wall reduction |",
        "| --- | ---: | ---: | ---: |"
    ];
    for (const item of summary) {
        rows.push(`| ${item.scenario} | ${formatMillis(item.before.wallMedianMillis)} / ${formatMillis(item.before.wallP95Millis)} | ${formatMillis(item.after.wallMedianMillis)} / ${formatMillis(item.after.wallP95Millis)} | ${formatPercent(item.wallReductionPercent)} |`);
    }
    rows.push("", "| Scenario / phase | Before median / p95 | After median / p95 | Reduction |", "| --- | ---: | ---: | ---: |");
    for (const item of summary) {
        for (const phase of reportedPhases(item)) {
            const values = item.phases[phase];
            rows.push(`| ${item.scenario} / ${phase} | ${formatMillis(values.beforeMedianMillis)} / ${formatMillis(values.beforeP95Millis)} | ${formatMillis(values.afterMedianMillis)} / ${formatMillis(values.afterP95Millis)} | ${formatPercent(values.reductionPercent)} |`);
        }
    }
    return rows.join("\n");
}

function reportedPhases(item) {
    return item.scenario === "angular-repeat-bundle"
        ? ["reconstruct", "frontend"]
        : [item.before.primaryPhase];
}

function median(values) {
    const selected = values.filter(value => Number.isFinite(value)).sort((left, right) => left - right);
    if (selected.length === 0) {
        return null;
    }
    const middle = Math.floor(selected.length / 2);
    return selected.length % 2 === 0
        ? (selected[middle - 1] + selected[middle]) / 2
        : selected[middle];
}

function percentile(values, quantile) {
    const selected = values.filter(value => Number.isFinite(value)).sort((left, right) => left - right);
    if (selected.length === 0) {
        return null;
    }
    return selected[Math.ceil(selected.length * quantile) - 1];
}

function reduction(before, after) {
    return Number.isFinite(before) && before > 0 && Number.isFinite(after)
        ? ((before - after) / before) * 100
        : null;
}

function formatMillis(value) {
    if (!Number.isFinite(value)) {
        return "n/a";
    }
    return value >= 1000 ? `${(value / 1000).toFixed(2)}s` : `${value.toFixed(1)}ms`;
}

function formatPercent(value) {
    return Number.isFinite(value) ? `${value.toFixed(1)}%` : "n/a";
}

async function requireFile(value, option) {
    if (!value) {
        throw new Error(`${option} is required`);
    }
    const resolved = path.resolve(value);
    const stats = await fs.stat(resolved).catch(() => null);
    if (!stats?.isFile()) {
        throw new Error(`${option} is not a file: ${resolved}`);
    }
    return resolved;
}

async function fileIdentity(file) {
    const bytes = await fs.readFile(file);
    return {
        path: file,
        sizeBytes: bytes.length,
        sha256: createHash("sha256").update(bytes).digest("hex")
    };
}

async function commandOutput(command, argv) {
    const completed = await run(command, argv, { cwd: projectRoot, env: process.env });
    return completed.output.trim();
}

function firstLine(value) {
    return value.replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, "").split(/\r?\n/, 1)[0] || "unknown";
}

function run(command, argv, options) {
    return new Promise((resolve, reject) => {
        const child = spawn(command, argv, {
            cwd: options.cwd,
            env: options.env,
            stdio: ["ignore", "pipe", "pipe"]
        });
        let output = "";
        child.stdout.on("data", chunk => { output += chunk; });
        child.stderr.on("data", chunk => { output += chunk; });
        child.on("error", reject);
        child.on("close", code => resolve({ code, output }));
    });
}

function parseArgs(args) {
    const parsed = {
        iterations: 5,
        angularIterations: 3,
        includeAngular: false,
        keep: false
    };
    for (let index = 0; index < args.length; index += 1) {
        const argument = args[index];
        const next = () => {
            const value = args[++index];
            if (!value) {
                throw new Error(`Missing value for ${argument}`);
            }
            return value;
        };
        if (argument === "--before-jar") {
            parsed.beforeJar = next();
        } else if (argument.startsWith("--before-jar=")) {
            parsed.beforeJar = argument.substring("--before-jar=".length);
        } else if (argument === "--after-jar") {
            parsed.afterJar = next();
        } else if (argument.startsWith("--after-jar=")) {
            parsed.afterJar = argument.substring("--after-jar=".length);
        } else if (argument === "--iterations") {
            parsed.iterations = positiveInteger(next(), argument);
        } else if (argument.startsWith("--iterations=")) {
            parsed.iterations = positiveInteger(argument.substring("--iterations=".length), "--iterations");
        } else if (argument === "--angular-iterations") {
            parsed.angularIterations = positiveInteger(next(), argument);
        } else if (argument.startsWith("--angular-iterations=")) {
            parsed.angularIterations = positiveInteger(
                argument.substring("--angular-iterations=".length),
                "--angular-iterations"
            );
        } else if (argument === "--include-angular") {
            parsed.includeAngular = true;
        } else if (argument === "--angular-template") {
            parsed.angularTemplate = next();
        } else if (argument === "--output") {
            parsed.output = next();
        } else if (argument === "--baseline") {
            parsed.baseline = next();
        } else if (argument === "--candidate") {
            parsed.candidate = next();
        } else if (argument === "--keep") {
            parsed.keep = true;
        } else if (argument === "--help" || argument === "-h") {
            parsed.help = true;
        } else {
            throw new Error(`Unknown argument: ${argument}`);
        }
    }
    return parsed;
}

function usage() {
    return `Usage: node scripts/benchmark-build-performance.mjs \\
  --before-jar <baseline.jar> --after-jar <candidate.jar> [options]

Options:
  --iterations <n>          Maven/Boot samples per version (default: 5)
  --include-angular         Benchmark repeat Angular bundle builds
  --angular-iterations <n>  Angular samples per version (default: 3)
  --angular-template <dir>  Angular fixture template
  --output <file>           Raw JSON result path
  --baseline <label>        Baseline revision label
  --candidate <label>       Candidate revision label
  --keep                    Retain temporary benchmark workspaces
  --help                    Show this help`;
}

function positiveInteger(value, option) {
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < 1 || parsed > 100) {
        throw new Error(`${option} must be an integer from 1 to 100`);
    }
    return parsed;
}

function escapeRegExp(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
