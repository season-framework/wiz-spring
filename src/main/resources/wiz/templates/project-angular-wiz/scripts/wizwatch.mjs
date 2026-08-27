#!/usr/bin/env node

import { runWizWatch } from './wiz/watcher.mjs';

await runWizWatch(process.cwd());
