#!/usr/bin/env node

import { compileWorkspace } from './wiz/compiler.mjs';
import { runAngularBuild } from './wiz/angular.mjs';

const projectRoot = process.cwd();
const result = await compileWorkspace(projectRoot);

console.log(`[wizbuild] staged ${result.components} component(s) in ${result.stageRoot}`);
await runAngularBuild(projectRoot, ['build', '--configuration', 'production'], { workspaceRoot: result.stageRoot });
console.log(`[wizbuild] frontend ready: ${result.outputRoot}`);
