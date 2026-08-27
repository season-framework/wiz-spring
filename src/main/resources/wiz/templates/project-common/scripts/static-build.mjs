import path from 'node:path';
import { copyDirectory, projectRoot, resetDirectory } from './lib/project.mjs';

const packageType = process.argv[2] ?? 'html';
const source = packageType === 'jsp'
    ? path.join(projectRoot, 'src', 'main', 'webapp', 'assets')
    : path.join(projectRoot, 'frontend');
const output = await resetDirectory(path.join(projectRoot, 'target', 'generated-resources', 'frontend'));
await copyDirectory(source, output);
console.log(`Frontend staged: ${path.relative(projectRoot, output)}`);
