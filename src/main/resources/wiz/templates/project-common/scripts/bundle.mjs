import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import {
    assertInsideProject,
    copyDirectory,
    detectFrontend,
    exists,
    projectRoot,
    readPackage,
    run,
} from './lib/project.mjs';

await run(process.execPath, ['scripts/build.mjs']);

const descriptor = await readPackage();
const frontendType = await detectFrontend(descriptor);
const artifactType = frontendType === 'jsp' ? 'war' : 'jar';
const targetRoot = path.join(projectRoot, 'target');
const candidates = (await readdir(targetRoot, { withFileTypes: true }))
    .filter(entry => entry.isFile())
    .map(entry => entry.name)
    .filter(name => name.endsWith(`.${artifactType}`))
    .filter(name => !name.startsWith('original-') && !name.includes('-sources') && !name.includes('-javadoc'))
    .sort();
if (candidates.length !== 1) {
    throw new Error(`Expected exactly one backend ${artifactType} in target; found: ${candidates.join(', ') || 'none'}`);
}

const stage = assertInsideProject(path.join(targetRoot, `bundle-stage-${process.pid}-${Date.now()}`), 'bundle stage');
const output = assertInsideProject(path.join(projectRoot, 'bundle'), 'bundle output');
const frontendOutput = path.join(projectRoot, 'target', 'generated-resources', 'frontend');
if (!(await exists(frontendOutput)) || !(await stat(frontendOutput)).isDirectory()) {
    throw new Error('Frontend build did not create target/generated-resources/frontend');
}
await rm(stage, { recursive: true, force: true });
await mkdir(path.join(stage, 'app'), { recursive: true });
await copyDirectory(frontendOutput, path.join(stage, 'public'));
await copyDirectory(path.join(projectRoot, 'deploy'), path.join(stage, 'deploy'));
await copyDirectory(path.join(projectRoot, 'docker-compose.yaml'), path.join(stage, 'docker-compose.yaml'));

const artifactName = `application.${artifactType}`;
await copyDirectory(path.join(targetRoot, candidates[0]), path.join(stage, 'app', artifactName));
await mkdir(path.join(stage, 'config'), { recursive: true });
await copyDirectory(path.join(projectRoot, 'deploy', 'application-bundle.yml'), path.join(stage, 'config', 'application-bundle.yml'));
await copyDirectory(path.join(projectRoot, 'deploy', 'README.md'), path.join(stage, 'README.md'));
await writeFile(path.join(stage, '.env.example'), [
    `APP_ARTIFACT=application.${artifactType}`,
    'APP_API_PREFIX=/api',
    'HTTP_PORT=80',
    'BUNDLE_DIR=.',
    '',
].join('\n'));

const manifest = {
    schemaVersion: 1,
    project: descriptor.name,
    template: frontendType,
    createdAt: new Date().toISOString(),
    artifact: { path: `app/${artifactName}`, type: artifactType },
    frontend: { path: 'public' },
};
await writeFile(path.join(stage, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);

async function filesBelow(directory) {
    const results = [];
    for (const entry of await readdir(directory, { withFileTypes: true })) {
        const item = path.join(directory, entry.name);
        if (entry.isDirectory()) results.push(...await filesBelow(item));
        else if (entry.isFile()) results.push(item);
    }
    return results;
}

const sums = [];
for (const file of (await filesBelow(stage)).sort()) {
    if (path.basename(file) === 'SHA256SUMS') continue;
    const digest = createHash('sha256').update(await readFile(file)).digest('hex');
    sums.push(`${digest}  ${path.relative(stage, file).split(path.sep).join('/')}`);
}
await writeFile(path.join(stage, 'SHA256SUMS'), `${sums.join('\n')}\n`);

const backup = assertInsideProject(path.join(targetRoot, `bundle-backup-${process.pid}`), 'bundle backup');
await rm(backup, { recursive: true, force: true });
if (await exists(output)) await rename(output, backup);
try {
    await rename(stage, output);
    await rm(backup, { recursive: true, force: true });
} catch (error) {
    if (await exists(backup) && !(await exists(output))) await rename(backup, output);
    throw error;
}

const artifact = path.join(output, 'app', artifactName);
console.log(`Bundle ready: ${output} (${(await stat(artifact)).size} byte backend artifact)`);
