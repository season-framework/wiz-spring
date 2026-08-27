import { watch } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { projectRoot, runMaven } from './lib/project.mjs';

const roots = [path.join(projectRoot, 'src', 'main', 'java'), path.join(projectRoot, 'src', 'main', 'resources')];
let timer;
let compiling = false;
let dirty = false;

async function compile() {
    if (compiling) {
        dirty = true;
        return;
    }
    compiling = true;
    try {
        await runMaven(['compile', '-DskipTests']);
    } catch (error) {
        console.error(`Backend compile failed: ${error.message}`);
    } finally {
        compiling = false;
        if (dirty) {
            dirty = false;
            await compile();
        }
    }
}

const watchers = roots.map(root => watch(root, { recursive: true }, () => {
    clearTimeout(timer);
    timer = setTimeout(compile, 200);
}));
for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
        watchers.forEach(watcher => watcher.close());
        process.exit(0);
    });
}
console.log('Watching Spring sources; DevTools will restart after incremental compilation.');
