import { mkdir, readdir, rename, rm, stat, writeFile } from 'node:fs/promises';
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
await mkdir(stage, { recursive: true });
await copyDirectory(frontendOutput, path.join(stage, 'public'));
await copyDirectory(path.join(projectRoot, 'docker-compose.yaml'), path.join(stage, 'docker-compose.yaml'));

const artifactName = `application.${artifactType}`;
await copyDirectory(path.join(targetRoot, candidates[0]), path.join(stage, artifactName));
await writeFile(path.join(stage, '.env'), [
    '# Runtime configuration for this bundle. Edit this file directly.',
    '# Runtime flags and explicit service options can override these defaults.',
    'SERVER_PORT=8080',
    'SPRING_PROFILES_ACTIVE=prod',
    `APP_ARTIFACT=application.${artifactType}`,
    'APP_API_PREFIX=/api',
    'SPRINGDOC_API_DOCS_ENABLED=false',
    'SPRINGDOC_SWAGGER_UI_ENABLED=false',
    '',
].join('\n'));

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

const artifact = path.join(output, artifactName);
console.log(`Bundle ready: ${output} (${(await stat(artifact)).size} byte backend artifact)`);
