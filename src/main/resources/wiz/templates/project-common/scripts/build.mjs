import { runMaven, runNpmScript } from './lib/project.mjs';

// The artifacts stay independent: Maven never requires Node, and the frontend
// never requires wiz-spring. This command is only the convenience orchestrator.
await runMaven(['clean', 'package']);
await runNpmScript('frontend:build');
