import { createHash } from 'node:crypto';
import { chmod, mkdir, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises';
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
await mkdir(path.join(stage, 'data'), { recursive: true });
await copyDirectory(frontendOutput, path.join(stage, 'public'));
await copyDirectory(path.join(projectRoot, 'deploy'), path.join(stage, 'deploy'));
await copyDirectory(path.join(projectRoot, 'docker-compose.yaml'), path.join(stage, 'docker-compose.yaml'));

const artifactName = `application.${artifactType}`;
await copyDirectory(path.join(targetRoot, candidates[0]), path.join(stage, 'app', artifactName));
await mkdir(path.join(stage, 'config'), { recursive: true });
await copyDirectory(path.join(projectRoot, 'deploy', 'application-bundle.yml'), path.join(stage, 'config', 'application-bundle.yml'));
await copyDirectory(path.join(projectRoot, 'deploy', 'README.md'), path.join(stage, 'README.md'));
await writeFile(path.join(stage, '.env'), [
    '# Runtime configuration for this bundle. Edit this file directly.',
    '# Exported variables and explicit service options take precedence.',
    'SERVER_PORT=8080',
    'SPRING_PROFILES_ACTIVE=prod,bundle',
    `APP_ARTIFACT=application.${artifactType}`,
    'APP_API_PREFIX=/api',
    'SPRINGDOC_API_DOCS_ENABLED=false',
    'SPRINGDOC_SWAGGER_UI_ENABLED=false',
    '',
    '# Docker Compose reverse-proxy settings (not used by ./run.sh).',
    'HTTP_PORT=80',
    'BUNDLE_DIR=.',
    '',
].join('\n'));

const launcherPath = path.join(stage, 'run.sh');
await writeFile(launcherPath, [
    '#!/bin/sh',
    'set -eu',
    '',
    'case "$0" in',
    '    /*) launcher_path=$0 ;;',
    '    *) launcher_path=$PWD/$0 ;;',
    'esac',
    'bundle_root=$(CDPATH= cd -- "${launcher_path%/*}" && pwd)',
    'cd "$bundle_root"',
    '',
    'load_env_file() {',
    '    env_path=$1',
    '    [ -f "$env_path" ] || return 0',
    "    carriage_return=$(printf '\\r')",
    '    while IFS= read -r env_line || [ -n "$env_line" ]; do',
    '        env_line=${env_line%"$carriage_return"}',
    '        case "$env_line" in',
    "            ''|'#'*) continue ;;",
    '        esac',
    '        env_key=${env_line%%=*}',
    '        if [ "$env_key" = "$env_line" ]; then',
    '            echo "Invalid .env entry (expected NAME=value): $env_line" >&2',
    '            exit 2',
    '        fi',
    '        case "$env_key" in',
    "            ''|[0-9]*|*[!A-Za-z0-9_]*)",
    '                echo "Invalid .env variable name: $env_key" >&2',
    '                exit 2',
    '                ;;',
    '        esac',
    '        eval "env_present=\\${$env_key+x}"',
    '        [ "$env_present" = x ] && continue',
    '        env_value=${env_line#*=}',
    '        case "$env_value" in',
    "            \\\"*\\\") env_value=${env_value#\\\"}; env_value=${env_value%\\\"} ;;",
    "            \\'*\\') env_value=${env_value#\\'}; env_value=${env_value%\\'} ;;",
    '        esac',
    '        export "$env_key=$env_value"',
    '    done < "$env_path"',
    '}',
    '',
    'load_env_file "$bundle_root/.env"',
    'SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-prod,bundle}',
    'export SPRING_PROFILES_ACTIVE',
    '',
    'if [ -n "${JAVA_BIN:-}" ]; then',
    '    java_bin=$JAVA_BIN',
    'elif [ -n "${JAVA_HOME:-}" ]; then',
    '    java_bin=$JAVA_HOME/bin/java',
    'else',
    '    java_bin=java',
    'fi',
    'if ! command -v "$java_bin" >/dev/null 2>&1; then',
    '    echo "Java was not found. Install JDK 25+ or set JAVA_BIN/JAVA_HOME." >&2',
    '    exit 127',
    'fi',
    `exec "$java_bin" -jar "$bundle_root/app/application.${artifactType}" "$@"`,
    '',
].join('\n'), { mode: 0o755 });
await chmod(launcherPath, 0o755);

const manifest = {
    schemaVersion: 1,
    project: descriptor.name,
    template: frontendType,
    createdAt: new Date().toISOString(),
    artifact: { path: `app/${artifactName}`, type: artifactType },
    frontend: { path: 'public' },
    launcher: { path: 'run.sh', requires: 'JDK 25+' },
    mutable: { files: ['.env'], directories: ['data'] },
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
    const relative = path.relative(stage, file).split(path.sep).join('/');
    if (relative === '.env') continue;
    const digest = createHash('sha256').update(await readFile(file)).digest('hex');
    sums.push(`${digest}  ${relative}`);
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
