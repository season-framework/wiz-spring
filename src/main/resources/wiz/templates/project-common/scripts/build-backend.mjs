import { runMaven } from './lib/project.mjs';

await runMaven(process.argv.includes('--no-clean') ? ['package'] : ['clean', 'package']);
