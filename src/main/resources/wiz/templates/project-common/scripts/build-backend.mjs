import { requestBackendRestart, runMaven } from './lib/project.mjs';

// Keep the independently watched frontend beneath target/ intact. Use the integrated
// build when both outputs must be cleaned and rebuilt together.
await runMaven(['package']);
await requestBackendRestart();
