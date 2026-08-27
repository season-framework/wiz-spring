import { readFile, readdir, unlink } from 'node:fs/promises';
import path from 'node:path';
import {
  childDirectories,
  copyTree,
  exists,
  readJson,
  remove,
  syncTree,
  writeText
} from './filesystem.mjs';

const VALID_FRONTEND = 'angular-wiz';

export function toComponentClass(appId) {
  const words = String(appId).split(/[^a-zA-Z0-9]+/).filter(Boolean);
  const base = words.map((word) => word[0].toUpperCase() + word.slice(1)).join('');
  if (!base) throw new Error(`Cannot derive an Angular component name from app id: ${appId}`);
  return `${/^\d/.test(base) ? `Wiz${base}` : base}Component`;
}

export function toSelector(appId) {
  const value = String(appId)
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/[^a-zA-Z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase();
  if (!value) throw new Error(`Cannot derive an Angular selector from app id: ${appId}`);
  return `wiz-${/^\d/.test(value) ? `app-${value}` : value}`;
}

export function routePath(value) {
  return String(value ?? '').trim().replace(/^\/+|\/+$/g, '');
}

function escapeTs(value) {
  return String(value).replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\r/g, '\\r').replace(/\n/g, '\\n');
}

function importDeclarationComplete(source) {
  let curly = 0;
  let square = 0;
  let round = 0;
  let quote = null;
  let escaped = false;
  let blockComment = false;
  let lineComment = false;
  for (let index = 0; index < source.length; index += 1) {
    const character = source[index];
    const next = source[index + 1];
    if (lineComment) {
      if (character === '\n') lineComment = false;
      continue;
    }
    if (blockComment) {
      if (character === '*' && next === '/') {
        blockComment = false;
        index += 1;
      }
      continue;
    }
    if (quote) {
      if (escaped) escaped = false;
      else if (character === '\\') escaped = true;
      else if (character === quote) quote = null;
      continue;
    }
    if (character === '/' && next === '/') {
      lineComment = true;
      index += 1;
    } else if (character === '/' && next === '*') {
      blockComment = true;
      index += 1;
    } else if (character === '\'' || character === '"' || character === '`') {
      quote = character;
    } else if (character === '{') curly += 1;
    else if (character === '}') curly -= 1;
    else if (character === '[') square += 1;
    else if (character === ']') square -= 1;
    else if (character === '(') round += 1;
    else if (character === ')') round -= 1;
  }
  if (quote || blockComment || curly > 0 || square > 0 || round > 0) return false;
  const statement = source.trim().replace(/;\s*$/, '');
  if (statement.endsWith(',')) return false;
  return /\bfrom\s*['"][^'"]+['"]/.test(statement)
    || /^import\s+(?:type\s+)?['"][^'"]+['"]/.test(statement)
    || /\brequire\s*\(\s*['"][^'"]+['"]\s*\)/.test(statement);
}

export function splitLeadingImports(source) {
  const lines = source.replace(/^\uFEFF/, '').split(/\r?\n/);
  const imports = [];
  let index = 0;
  while (index < lines.length) {
    const triviaStart = index;
    while (index < lines.length) {
      const trivia = lines[index].trim();
      if (!trivia || trivia.startsWith('//')) {
        index += 1;
        continue;
      }
      if (trivia.startsWith('/*')) {
        do {
          const closesComment = lines[index].includes('*/');
          index += 1;
          if (closesComment) break;
        } while (index < lines.length);
        continue;
      }
      break;
    }
    const trimmed = index < lines.length ? lines[index].trim() : '';
    if (!trimmed.startsWith('import ')) {
      index = triviaStart;
      break;
    }
    imports.push(...lines.slice(triviaStart, index));
    const declaration = [];
    do {
      declaration.push(lines[index++]);
    } while (!importDeclarationComplete(declaration.join('\n')) && index < lines.length);
    imports.push(...declaration);
  }
  return {
    imports: imports.join('\n').trim(),
    body: lines.slice(index).join('\n').trim()
  };
}

function metadataValue(metadata, key, fallback = '') {
  const value = metadata?.[key];
  return value === undefined || value === null || String(value).trim() === '' ? fallback : String(value);
}

function angularBuildMetadata(metadata) {
  if (metadata?.['ng.build'] && typeof metadata['ng.build'] === 'object') return metadata['ng.build'];
  if (metadata?.ng?.build && typeof metadata.ng.build === 'object') return metadata.ng.build;
  return {};
}

export function normalizedComponentId(directoryName, configuredId, origin) {
  const configured = String(configuredId ?? '').trim() || directoryName;
  if (!origin.startsWith('portal/') || configured.startsWith('portal.')) return configured;
  const portalName = origin.slice('portal/'.length).replace(/[^a-zA-Z0-9_-]+/g, '.');
  return `portal.${portalName}.${configured}`;
}

function componentDefinition(directory, metadata, origin) {
  const appId = normalizedComponentId(
    path.basename(directory), metadataValue(metadata, 'id', path.basename(directory)), origin);
  const build = angularBuildMetadata(metadata);
  const ng = metadata?.ng && typeof metadata.ng === 'object' ? metadata.ng : {};
  const definition = {
    appId,
    className: metadataValue(build, 'name', toComponentClass(appId)),
    selector: metadataValue(ng, 'selector', toSelector(appId)),
    mode: metadataValue(metadata, 'mode', 'app'),
    route: routePath(metadataValue(metadata, 'viewuri')),
    layout: metadataValue(metadata, 'layout', ''),
    namespace: metadataValue(metadata, 'namespace', appId.includes('.') ? appId.slice(appId.indexOf('.') + 1) : appId),
    directory,
    origin
  };
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(definition.appId)) {
    throw new Error(`Invalid WIZ app id '${definition.appId}' in ${origin}; use letters, digits, dot, underscore, and dash only`);
  }
  if (!/^[a-zA-Z_$][a-zA-Z0-9_$]*$/.test(definition.className)) {
    throw new Error(`Invalid Angular class name '${definition.className}' for ${definition.appId}`);
  }
  if (!/^[a-z][a-z0-9-]*$/.test(definition.selector)) {
    throw new Error(`Invalid Angular selector '${definition.selector}' for ${definition.appId}`);
  }
  return definition;
}

async function discoverComponents(sourceRoot) {
  const candidates = [];
  for (const directory of await childDirectories(path.join(sourceRoot, 'app'))) {
    candidates.push({ directory, origin: 'app' });
  }
  for (const portal of await childDirectories(path.join(sourceRoot, 'portal'))) {
    for (const directory of await childDirectories(path.join(portal, 'app'))) {
      candidates.push({ directory, origin: `portal/${path.basename(portal)}` });
    }
  }

  const components = [];
  for (const candidate of candidates) {
    const metadata = await readJson(path.join(candidate.directory, 'app.json'), {});
    const hasView = await Promise.all(['view.ts', 'view.pug', 'view.html'].map((name) => exists(path.join(candidate.directory, name))));
    if (!hasView.some(Boolean) && Object.keys(metadata).length === 0) continue;
    components.push(componentDefinition(candidate.directory, metadata, candidate.origin));
  }

  const seen = new Map();
  for (const component of components) {
    for (const [kind, value] of [['id', component.appId], ['class', component.className], ['selector', component.selector]]) {
      const key = `${kind}:${value}`;
      if (seen.has(key)) throw new Error(`Duplicate component ${kind} '${value}' in ${component.origin} and ${seen.get(key)}`);
      seen.set(key, component.origin);
    }
  }
  return components.sort((left, right) => left.appId.localeCompare(right.appId));
}

async function compilePug(source, destination, projectRoot) {
  let pug;
  try {
    pug = await import('pug');
  } catch {
    throw new Error('Pug is not installed. Run `npm ci` before running wizbuild.');
  }
  const html = pug.default.renderFile(source, {
    basedir: projectRoot,
    filename: source,
    pretty: true,
    doctype: 'html'
  });
  await writeText(destination, html);
}

async function compilePugFiles(root, projectRoot) {
  if (!(await exists(root))) return;
  const entries = await readdir(root, { withFileTypes: true });
  for (const entry of entries) {
    const candidate = path.join(root, entry.name);
    if (entry.isDirectory()) {
      await compilePugFiles(candidate, projectRoot);
    } else if (entry.isFile() && entry.name.endsWith('.pug')) {
      await compilePug(candidate, candidate.slice(0, -4) + '.html', projectRoot);
      await unlink(candidate);
    }
  }
}

function componentSource(component, source) {
  const split = splitLeadingImports(source);
  let body = split.body;
  if (!body) body = 'export class Component {}';
  const classPattern = /export\s+(?:default\s+)?class\s+Component\b/;
  if (!classPattern.test(body)) {
    throw new Error(`${component.origin}/${component.appId}/view.ts must export a class named Component`);
  }
  body = body.replace(classPattern, `export class ${component.className}`);
  return [
    '// Generated by scripts/wizbuild.mjs. Edit the matching WIZ view.ts instead.',
    '// @ts-nocheck',
    "import { Component as AngularComponent } from '@angular/core';",
    "import Wiz from '../../wiz';",
    split.imports,
    `const wiz = new Wiz({ appId: '${escapeTs(component.appId)}', namespace: '${escapeTs(component.namespace)}' });`,
    '@AngularComponent({',
    `  selector: '${escapeTs(component.selector)}',`,
    "  templateUrl: './view.html',",
    "  styleUrls: ['./view.scss'],",
    '  standalone: false',
    '})',
    body,
    '',
    `export default ${component.className};`,
    ''
  ].filter((line, index, lines) => line !== '' || lines[index - 1] !== '').join('\n');
}

async function generateComponent(component, stageRoot, projectRoot) {
  const target = path.join(stageRoot, 'src', 'app', component.appId);
  const pug = path.join(component.directory, 'view.pug');
  const html = path.join(component.directory, 'view.html');
  if (await exists(pug)) await compilePug(pug, path.join(target, 'view.html'), projectRoot);
  else if (await exists(html)) await copyTree(html, path.join(target, 'view.html'), component.directory);
  else await writeText(path.join(target, 'view.html'), '');

  const scss = path.join(component.directory, 'view.scss');
  if (await exists(scss)) await copyTree(scss, path.join(target, 'view.scss'), component.directory);
  else await writeText(path.join(target, 'view.scss'), '');

  const viewTs = path.join(component.directory, 'view.ts');
  const source = await exists(viewTs) ? await readFile(viewTs, 'utf8') : 'export class Component {}';
  await writeText(path.join(target, `${component.appId}.component.ts`), componentSource(component, source));
}

function importLine(component) {
  return `import { ${component.className} } from './${component.appId}/${component.appId}.component';`;
}

function appModuleSource(components) {
  const imports = components.map(importLine).join('\n');
  const declarations = ['AppComponent', ...components.map((component) => component.className)].join(',\n    ');
  return `// Generated by scripts/wizbuild.mjs.\nimport { NgModule, provideZonelessChangeDetection } from '@angular/core';\nimport { BrowserModule } from '@angular/platform-browser';\nimport { FormsModule } from '@angular/forms';\nimport { AppRoutingModule } from './app-routing.module';\nimport { AppComponent } from './app.component';\n${imports}\n\n@NgModule({\n  declarations: [\n    ${declarations}\n  ],\n  imports: [BrowserModule, FormsModule, AppRoutingModule],\n  providers: [provideZonelessChangeDetection()],\n  bootstrap: [AppComponent]\n})\nexport class AppModule {}\n`;
}

export function routingSource(components) {
  const pages = components.filter((component) => component.mode === 'page' && component.route);
  const layouts = new Map(components.filter((component) => component.mode === 'layout').map((component) => [component.appId, component]));
  const routed = new Map();
  for (const page of pages) routed.set(page.appId, page);
  for (const page of pages) {
    const layout = layouts.get(page.layout);
    if (layout) routed.set(layout.appId, layout);
  }
  const imports = [...routed.values()].map(importLine).join('\n');
  const routes = [];
  const index = pages[0]?.route ?? '';
  if (index) routes.push(`  { path: '', pathMatch: 'full', redirectTo: '${escapeTs(index)}' }`);

  for (const layout of layouts.values()) {
    const children = pages.filter((page) => page.layout === layout.appId);
    if (!children.length) continue;
    routes.push(`  {\n    path: '',\n    component: ${layout.className},\n    children: [\n${children.map((page) => `      { path: '${escapeTs(page.route)}', component: ${page.className} }`).join(',\n')}\n    ]\n  }`);
  }
  for (const page of pages.filter((candidate) => !layouts.has(candidate.layout))) {
    routes.push(`  { path: '${escapeTs(page.route)}', component: ${page.className} }`);
  }
  if (index) routes.push(`  { path: '**', redirectTo: '${escapeTs(index)}' }`);

  return `// Generated by scripts/wizbuild.mjs.\nimport { NgModule } from '@angular/core';\nimport { RouterModule, Routes } from '@angular/router';\n${imports}\n\nconst routes: Routes = [\n${routes.join(',\n')}\n];\n\n@NgModule({\n  imports: [RouterModule.forRoot(routes)],\n  exports: [RouterModule]\n})\nexport class AppRoutingModule {}\n`;
}

async function copyPortalResources(sourceRoot, stageRoot) {
  for (const portal of await childDirectories(path.join(sourceRoot, 'portal'))) {
    const portalName = path.basename(portal);
    await copyTree(path.join(portal, 'libs'), path.join(stageRoot, 'src', 'libs', 'portal', portalName), portal);
    await copyTree(path.join(portal, 'assets'), path.join(stageRoot, 'src', 'assets', 'portal', portalName), portal);
  }
}

async function copyAngularShell(sourceRoot, stageRoot) {
  const angularRoot = path.join(sourceRoot, 'angular');
  if (!(await exists(angularRoot))) throw new Error(`Angular shell is missing: ${angularRoot}`);
  const rootFiles = new Set(['.postcssrc.json', 'tailwind.config.js']);
  const unsupportedBuildFiles = new Set([
    'angular.json',
    'angular.build.options.json',
    'package.json',
    'package-lock.json',
    'npm-shrinkwrap.json'
  ]);
  for (const entry of await readdir(angularRoot, { withFileTypes: true })) {
    if (unsupportedBuildFiles.has(entry.name)) {
      throw new Error(
        `Angular WIZ 1.0 does not support src/angular/${entry.name}; configure the root package.json and angular.json instead`);
    }
    if (entry.name === 'node_modules') continue;
    const source = path.join(angularRoot, entry.name);
    const destination = rootFiles.has(entry.name)
      ? path.join(stageRoot, entry.name)
      : entry.isFile() && entry.name.startsWith('tsconfig')
      ? path.join(stageRoot, entry.name)
      : path.join(stageRoot, 'src', entry.name);
    await copyTree(source, destination, angularRoot);
  }
}

function firstAngularProject(metadata, label) {
  const projects = metadata?.projects;
  if (!projects || typeof projects !== 'object' || Array.isArray(projects)) {
    throw new Error(`${label} must contain an Angular projects object`);
  }
  const entry = Object.entries(projects)[0];
  if (!entry || !entry[1] || typeof entry[1] !== 'object') {
    throw new Error(`${label} must contain an Angular project`);
  }
  return entry[1];
}

function forwardSlashes(value) {
  return value.split(path.sep).join('/');
}

async function writeAngularWorkspace(projectRoot, stageRoot, outputRoot) {
  const workspacePath = path.join(projectRoot, 'angular.json');
  const workspace = structuredClone(await readJson(workspacePath));
  const project = firstAngularProject(workspace, workspacePath);
  const build = project.architect?.build ?? project.targets?.build;
  if (!build?.options) throw new Error(`${workspacePath} is missing build options`);

  project.root = '';
  project.sourceRoot = 'src';
  const options = build.options;
  options.outputPath = { base: forwardSlashes(path.relative(stageRoot, outputRoot)), browser: '' };
  options.index = 'src/index.html';
  options.browser = 'src/main.ts';
  delete options.main;
  options.tsConfig = 'tsconfig.app.json';
  options.assets = [{ glob: '**/*', input: 'src/assets', output: 'assets' }];
  options.styles = ['src/styles.scss'];
  delete options.scripts;
  await writeText(path.join(stageRoot, 'angular.json'), JSON.stringify(workspace, null, 2));
}

async function validateProject(projectRoot) {
  const manifest = await readJson(path.join(projectRoot, 'package.json'));
  if (manifest?.wiz?.frontend !== VALID_FRONTEND) {
    throw new Error(`package.json wiz.frontend must be '${VALID_FRONTEND}', received '${manifest?.wiz?.frontend ?? ''}'`);
  }
  if (manifest?.wiz?.builderVersion !== 1) {
    throw new Error(`Unsupported WIZ frontend builderVersion: ${manifest?.wiz?.builderVersion ?? ''}`);
  }
}

export async function compileWorkspace(projectRoot = process.cwd()) {
  projectRoot = path.resolve(projectRoot);
  await validateProject(projectRoot);

  const sourceRoot = path.join(projectRoot, 'src');
  const targetRoot = path.join(projectRoot, 'target');
  const stageRoot = path.join(targetRoot, 'wiz-angular');
  const temporaryRoot = path.join(targetRoot, `wiz-angular-tmp-${process.pid}-${Date.now()}`);
  const outputRoot = path.join(targetRoot, 'generated-resources', 'frontend');

  try {
    await copyAngularShell(sourceRoot, temporaryRoot);
    await copyTree(path.join(sourceRoot, 'assets'), path.join(temporaryRoot, 'src', 'assets'), sourceRoot);
    await copyTree(path.join(sourceRoot, 'libs'), path.join(temporaryRoot, 'src', 'libs'), sourceRoot);
    await copyPortalResources(sourceRoot, temporaryRoot);
    await compilePugFiles(temporaryRoot, projectRoot);

    const components = await discoverComponents(sourceRoot);
    for (const component of components) await generateComponent(component, temporaryRoot, projectRoot);
    const appModule = path.join(temporaryRoot, 'src', 'app', 'app.module.ts');
    await writeText(appModule, appModuleSource(components));
    const routingModule = path.join(temporaryRoot, 'src', 'app', 'app-routing.module.ts');
    await writeText(routingModule, routingSource(components));
    await writeAngularWorkspace(projectRoot, temporaryRoot, outputRoot);
    await writeText(path.join(temporaryRoot, 'generated-files.json'), JSON.stringify({ builderVersion: 1, components: components.map(({ appId, origin }) => ({ appId, origin })) }, null, 2));
    await syncTree(temporaryRoot, stageRoot);

    return { components: components.length, stageRoot, outputRoot };
  } finally {
    await remove(temporaryRoot);
  }
}
