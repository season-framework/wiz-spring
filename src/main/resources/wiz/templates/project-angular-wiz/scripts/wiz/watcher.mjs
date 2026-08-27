import { watch } from 'node:fs';
import { readdir } from 'node:fs/promises';
import path from 'node:path';
import { compileWorkspace } from './compiler.mjs';
import { exists } from './filesystem.mjs';
import { spawnAngularWatch, stopChild } from './angular.mjs';

const DEBOUNCE_MS = 180;

export function unexpectedAngularExitCode(code) {
  return Number.isInteger(code) && code > 0 ? code : 1;
}

async function directories(root) {
  if (!(await exists(root))) return [];
  const result = [root];
  async function visit(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      if (!entry.isDirectory() || entry.isSymbolicLink()) continue;
      const child = path.join(directory, entry.name);
      result.push(child);
      await visit(child);
    }
  }
  await visit(root);
  return result;
}

async function createSourceWatchers(projectRoot, onChange) {
  const roots = ['app', 'portal', 'route', 'angular', 'assets', 'libs'].map((name) => path.join(projectRoot, 'src', name));
  const watchers = [];
  for (const root of roots) {
    for (const directory of await directories(root)) {
      const watcher = watch(directory, { persistent: true }, (_event, filename) => {
        onChange({
          restart: root.endsWith(`${path.sep}angular`),
          file: filename ? path.join(directory, filename.toString()) : directory
        });
      });
      watcher.on('error', (error) => console.error(`[wizwatch] watcher failed for ${directory}: ${error.message}`));
      watchers.push(watcher);
    }
  }
  return watchers;
}

export async function runWizWatch(projectRoot) {
  projectRoot = path.resolve(projectRoot);
  let angular = null;
  let watchers = [];
  let timer = null;
  let compiling = false;
  let pending = false;
  let restartRequested = false;
  let stopping = false;
  const expectedExits = new WeakSet();

  const refreshWatchers = async () => {
    for (const watcher of watchers) watcher.close();
    watchers = await createSourceWatchers(projectRoot, schedule);
  };

  const startAngular = async () => {
    angular = await spawnAngularWatch(projectRoot, path.join(projectRoot, 'target', 'wiz-angular'));
    const started = angular;
    started.once('exit', (code, signal) => {
      if (!stopping && !expectedExits.has(started)) {
        console.error(`[wizwatch] Angular watcher stopped (${signal ?? code}).`);
        process.exitCode = unexpectedAngularExitCode(code);
        void shutdown();
      }
    });
  };

  const rebuild = async () => {
    if (compiling || stopping) {
      pending = true;
      return;
    }
    compiling = true;
    try {
      const result = await compileWorkspace(projectRoot);
      console.log(`[wizwatch] staged ${result.components} component(s) at ${new Date().toLocaleTimeString()}`);
      if (restartRequested) {
        restartRequested = false;
        console.log('[wizwatch] Angular shell changed; restarting Angular watcher.');
        if (angular) expectedExits.add(angular);
        await stopChild(angular);
        await startAngular();
      }
    } catch (error) {
      console.error(`[wizwatch] ${error.stack ?? error.message}`);
    } finally {
      try {
        await refreshWatchers();
      } catch (error) {
        console.error(`[wizwatch] failed to refresh source watchers: ${error.message}`);
      }
      compiling = false;
      if (pending) {
        pending = false;
        schedule({ restart: restartRequested });
      }
    }
  };

  function schedule(change = {}) {
    restartRequested ||= Boolean(change.restart);
    clearTimeout(timer);
    timer = setTimeout(rebuild, DEBOUNCE_MS);
  }

  const shutdown = async (signal) => {
    if (stopping) return;
    stopping = true;
    clearTimeout(timer);
    for (const watcher of watchers) watcher.close();
    await stopChild(angular);
    if (signal) process.exitCode = 0;
  };

  process.once('SIGINT', () => void shutdown('SIGINT'));
  process.once('SIGTERM', () => void shutdown('SIGTERM'));

  const initial = await compileWorkspace(projectRoot);
  console.log(`[wizwatch] staged ${initial.components} component(s); watching WIZ sources.`);
  await refreshWatchers();
  await startAngular();

  await new Promise((resolve) => {
    process.once('beforeExit', resolve);
    process.once('SIGINT', resolve);
    process.once('SIGTERM', resolve);
  });
  await shutdown();
}
