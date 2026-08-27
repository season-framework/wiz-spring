import { access } from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';

export function angularCliPath(projectRoot) {
  return path.join(projectRoot, 'node_modules', '@angular', 'cli', 'bin', 'ng.js');
}

export async function assertAngularInstalled(projectRoot) {
  const cli = angularCliPath(projectRoot);
  try {
    await access(cli);
  } catch {
    throw new Error('Angular CLI is not installed. Run `npm ci` before building the frontend.');
  }
  return cli;
}

export async function runAngularBuild(projectRoot, args, options = {}) {
  const cli = await assertAngularInstalled(projectRoot);
  const { workspaceRoot = projectRoot, ...spawnOptions } = options;
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [cli, ...args], {
      cwd: workspaceRoot,
      stdio: 'inherit',
      env: { ...process.env, NG_CLI_ANALYTICS: 'false' },
      ...spawnOptions
    });
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (code === 0) {
        resolve(child);
        return;
      }
      reject(new Error(`Angular CLI exited with ${signal ? `signal ${signal}` : `code ${code}`}`));
    });
  });
}

export async function spawnAngularWatch(projectRoot, workspaceRoot = projectRoot) {
  const cli = await assertAngularInstalled(projectRoot);
  return spawn(process.execPath, [cli, 'build', '--watch', '--configuration', 'development'], {
    cwd: workspaceRoot,
    stdio: 'inherit',
    env: { ...process.env, NG_CLI_ANALYTICS: 'false' }
  });
}

export async function stopChild(child, timeoutMs = 5000) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;

  await new Promise((resolve) => {
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      resolve();
    };
    const timeout = setTimeout(() => {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
      finish();
    }, timeoutMs);

    child.once('exit', finish);
    child.kill('SIGTERM');
  });
}
