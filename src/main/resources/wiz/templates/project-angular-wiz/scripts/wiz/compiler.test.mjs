import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import {
  compileWorkspace,
  normalizedComponentId,
  routePath,
  routingSource,
  splitLeadingImports,
  toComponentClass,
  toSelector
} from './compiler.mjs';
import { unexpectedAngularExitCode } from './watcher.mjs';

test('WIZ ids become stable Angular names', () => {
  assert.equal(toComponentClass('page.dashboard'), 'PageDashboardComponent');
  assert.equal(toSelector('page.dashboard'), 'wiz-page-dashboard');
  assert.equal(routePath('/posts/:id/'), 'posts/:id');
});

test('component path traversal cannot be derived from an id', () => {
  assert.equal(toSelector('../dashboard'), 'wiz-dashboard');
  assert.equal(toComponentClass('../dashboard'), 'DashboardComponent');
});

test('portal components receive their stable module-qualified WIZ id', () => {
  assert.equal(normalizedComponentId('modal', 'modal', 'portal/season'), 'portal.season.modal');
  assert.equal(normalizedComponentId('modal', 'portal.custom.modal', 'portal/season'), 'portal.custom.modal');
  assert.equal(normalizedComponentId('page.dashboard', 'page.dashboard', 'app'), 'page.dashboard');
});

test('routing groups pages below their declared layout', () => {
  const source = routingSource([
    { appId: 'layout.empty', className: 'LayoutEmptyComponent', mode: 'layout', route: '', layout: '' },
    { appId: 'page.dashboard', className: 'PageDashboardComponent', mode: 'page', route: 'dashboard', layout: 'layout.empty' }
  ]);
  assert.match(source, /component: LayoutEmptyComponent/);
  assert.match(source, /path: 'dashboard', component: PageDashboardComponent/);
  assert.match(source, /path: '\*\*', redirectTo: 'dashboard'/);
});

test('semicolon-less TypeScript imports do not consume the component class', () => {
  const source = `// Signals used by the component.
import { signal } from '@angular/core'
/* RxJS operators stay with the following import. */
import {
  map,
  tap
} from 'rxjs/operators'

export class Component {
  readonly ready = signal(true)
}`;
  const split = splitLeadingImports(source);
  assert.match(split.imports, /import \{ signal \} from '@angular\/core'/);
  assert.match(split.imports, /from 'rxjs\/operators'/);
  assert.match(split.imports, /Signals used by the component/);
  assert.match(split.imports, /RxJS operators stay/);
  assert.match(split.body, /export class Component/);
  assert.doesNotMatch(split.imports, /export class Component/);
});

test('an unexpectedly stopped Angular watcher always fails wizwatch', () => {
  assert.equal(unexpectedAngularExitCode(7), 7);
  assert.equal(unexpectedAngularExitCode(0), 1);
  assert.equal(unexpectedAngularExitCode(null), 1);
});

test('nested Angular build manifests are rejected instead of merged', async () => {
  const projectRoot = await mkdtemp(path.join(tmpdir(), 'wiz-angular-clean-break-'));
  try {
    await mkdir(path.join(projectRoot, 'src', 'angular'), { recursive: true });
    await writeFile(
      path.join(projectRoot, 'package.json'),
      JSON.stringify({ wiz: { frontend: 'angular-wiz', builderVersion: 1 } })
    );
    await writeFile(path.join(projectRoot, 'src', 'angular', 'angular.json'), '{}');

    await assert.rejects(
      () => compileWorkspace(projectRoot),
      /does not support src\/angular\/angular\.json/
    );
  } finally {
    await rm(projectRoot, { recursive: true, force: true });
  }
});
