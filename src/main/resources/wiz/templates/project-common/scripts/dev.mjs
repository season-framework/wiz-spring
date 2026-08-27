import process from 'node:process';
import { command, exists, projectRoot, spawnManaged } from './lib/project.mjs';
import path from 'node:path';

const wrapper = path.join(projectRoot, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw');
const backendCommand = await exists(wrapper) ? wrapper : command('mvn');
const backend = spawnManaged(backendCommand, ['spring-boot:run']);
const frontend = spawnManaged(command('npm'), ['run', 'frontend:watch']);
const backendWatcher = spawnManaged(process.execPath, ['scripts/backend-watch.mjs']);
const children = [backend, frontend, backendWatcher];
let stopping = false;

function stop(signal = 'SIGTERM') {
    if (stopping) return;
    stopping = true;
    for (const child of children) {
        if (child.exitCode === null && !child.killed) child.kill(signal);
    }
}

for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => stop(signal));
}

const result = await new Promise((resolve, reject) => {
    for (const child of children) {
        child.once('error', reject);
        child.once('exit', (code, signal) => resolve({ child, code, signal }));
    }
});
stop();
await Promise.all(children.map(child => new Promise(resolve => {
    if (child.exitCode !== null) return resolve();
    child.once('exit', resolve);
    setTimeout(() => {
        if (child.exitCode === null) child.kill('SIGKILL');
    }, 5000).unref();
})));
process.exitCode = result.code ?? (result.signal ? 1 : 0);
