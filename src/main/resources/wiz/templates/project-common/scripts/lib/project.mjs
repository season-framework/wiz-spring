import { spawn } from 'node:child_process';
import { cp, mkdir, readFile, rm, stat } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
export const projectRoot = path.resolve(scriptDirectory, '..', '..');

export async function readPackage() {
    return JSON.parse(await readFile(path.join(projectRoot, 'package.json'), 'utf8'));
}

export async function detectFrontend(descriptor = null) {
    descriptor ??= await readPackage();
    const explicit = descriptor.wiz?.frontend;
    const dependencies = { ...descriptor.dependencies, ...descriptor.devDependencies };
    const hasAngular = Boolean(dependencies['@angular/core']) || await exists(path.join(projectRoot, 'angular.json'));
    const hasWizSources = await exists(path.join(projectRoot, 'src', 'app'))
        && await exists(path.join(projectRoot, 'src', 'angular'));
    let inferred = null;
    if (hasAngular && hasWizSources) inferred = 'angular-wiz';
    else if (hasAngular) inferred = 'angular';
    else if (dependencies.react || dependencies['react-dom']) inferred = 'react';
    else if (await exists(path.join(projectRoot, 'src', 'main', 'webapp', 'WEB-INF'))) inferred = 'jsp';
    else if (await exists(path.join(projectRoot, 'frontend'))) inferred = 'html';

    if (explicit !== undefined) {
        const supported = ['html', 'jsp', 'angular-wiz', 'angular', 'react'];
        if (!supported.includes(explicit)) {
            throw new Error(`Unsupported package.json wiz.frontend: ${explicit}`);
        }
        if (inferred !== explicit) {
            throw new Error(
                `package.json wiz.frontend '${explicit}' does not match project structure '${inferred ?? 'unknown'}'`);
        }
        return explicit;
    }
    if (inferred) return inferred;
    throw new Error('Unable to determine frontend type from package.json and standard project files.');
}

export async function exists(target) {
    try {
        await stat(target);
        return true;
    } catch (error) {
        if (error?.code === 'ENOENT') return false;
        throw error;
    }
}

export function command(name) {
    return process.platform === 'win32' ? `${name}.cmd` : name;
}

export async function run(executable, args, options = {}) {
    const child = spawn(executable, args, {
        cwd: options.cwd ?? projectRoot,
        env: { ...process.env, ...options.env },
        stdio: options.stdio ?? 'inherit',
        shell: false,
    });
    const exitCode = await new Promise((resolve, reject) => {
        child.once('error', reject);
        child.once('exit', (code, signal) => resolve(code ?? (signal ? 1 : 0)));
    });
    if (exitCode !== 0 && !options.allowFailure) {
        throw new Error(`${executable} ${args.join(' ')} exited with ${exitCode}`);
    }
    return exitCode;
}

export function spawnManaged(executable, args, options = {}) {
    return spawn(executable, args, {
        cwd: options.cwd ?? projectRoot,
        env: { ...process.env, ...options.env },
        stdio: 'inherit',
        shell: false,
    });
}

export async function runMaven(args) {
    const wrapper = path.join(projectRoot, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw');
    if (await exists(wrapper)) {
        return run(wrapper, args);
    }
    return run(command('mvn'), args);
}

export async function runNpmScript(name) {
    return run(command('npm'), ['run', name]);
}

export function assertInsideProject(target, label = 'path') {
    const resolved = path.resolve(target);
    const relative = path.relative(projectRoot, resolved);
    if (relative.startsWith('..') || path.isAbsolute(relative) || relative === '') {
        throw new Error(`${label} must be a child of the project: ${resolved}`);
    }
    return resolved;
}

export async function resetDirectory(target) {
    const safeTarget = assertInsideProject(target, 'generated directory');
    await rm(safeTarget, { recursive: true, force: true });
    await mkdir(safeTarget, { recursive: true });
    return safeTarget;
}

export async function copyDirectory(source, target) {
    if (!(await exists(source))) return;
    const sourceStat = await stat(source);
    if (sourceStat.isDirectory()) {
        await mkdir(target, { recursive: true });
        await cp(source, target, { recursive: true, force: true, errorOnExist: false });
        return;
    }
    if (!sourceStat.isFile()) {
        throw new Error(`Unsupported copy source: ${source}`);
    }
    await mkdir(path.dirname(target), { recursive: true });
    await cp(source, target, { force: true, errorOnExist: false });
}
