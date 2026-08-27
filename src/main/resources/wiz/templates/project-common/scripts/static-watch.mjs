import { watch } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { projectRoot, run } from './lib/project.mjs';

const packageType = process.argv[2] ?? 'html';
const source = packageType === 'jsp'
    ? path.join(projectRoot, 'src', 'main', 'webapp')
    : path.join(projectRoot, 'frontend');
let timer;
let building = false;
let dirty = false;

async function build() {
    if (building) {
        dirty = true;
        return;
    }
    building = true;
    try {
        await run(process.execPath, ['scripts/static-build.mjs', packageType]);
    } catch (error) {
        console.error(error.message);
    } finally {
        building = false;
        if (dirty) {
            dirty = false;
            await build();
        }
    }
}

await build();
const watcher = watch(source, { recursive: true }, () => {
    clearTimeout(timer);
    timer = setTimeout(build, 120);
});
for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
        watcher.close();
        process.exit(0);
    });
}
console.log(`Watching ${path.relative(projectRoot, source)}`);
