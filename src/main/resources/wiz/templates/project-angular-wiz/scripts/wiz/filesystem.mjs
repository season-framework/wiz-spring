import { lstat, mkdir, readdir, readFile, rm, stat, writeFile, copyFile } from 'node:fs/promises';
import path from 'node:path';

export function inside(root, candidate) {
  const relative = path.relative(path.resolve(root), path.resolve(candidate));
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}

export async function exists(candidate) {
  try {
    await stat(candidate);
    return true;
  } catch (error) {
    if (error?.code === 'ENOENT') return false;
    throw error;
  }
}

export async function readJson(file, fallback = undefined) {
  if (!(await exists(file))) {
    if (fallback !== undefined) return fallback;
    throw new Error(`Required JSON file is missing: ${file}`);
  }
  try {
    return JSON.parse(await readFile(file, 'utf8'));
  } catch (error) {
    throw new Error(`Invalid JSON in ${file}: ${error.message}`, { cause: error });
  }
}

async function rejectSymlink(candidate, sourceRoot) {
  const info = await lstat(candidate);
  if (info.isSymbolicLink()) {
    throw new Error(`Symbolic links are not allowed in WIZ frontend sources: ${path.relative(sourceRoot, candidate)}`);
  }
  return info;
}

export async function copyTree(source, destination, sourceRoot = source) {
  if (!(await exists(source))) return;
  if (!inside(destination, destination)) throw new Error(`Invalid copy destination: ${destination}`);

  const info = await rejectSymlink(source, sourceRoot);
  if (info.isFile()) {
    await mkdir(path.dirname(destination), { recursive: true });
    await copyFile(source, destination);
    return;
  }
  if (!info.isDirectory()) return;

  await mkdir(destination, { recursive: true });
  const entries = await readdir(source, { withFileTypes: true });
  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    const from = path.join(source, entry.name);
    const to = path.join(destination, entry.name);
    if (!inside(destination, to)) throw new Error(`Copy escaped its destination: ${entry.name}`);
    await copyTree(from, to, sourceRoot);
  }
}

async function entries(root) {
  if (!(await exists(root))) return [];
  const result = [];
  async function visit(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const absolute = path.join(directory, entry.name);
      const relative = path.relative(root, absolute);
      const info = await rejectSymlink(absolute, root);
      result.push({ absolute, relative, directory: info.isDirectory() });
      if (info.isDirectory()) await visit(absolute);
    }
  }
  await visit(root);
  return result;
}

export async function syncTree(source, destination) {
  await mkdir(destination, { recursive: true });
  const sourceEntries = await entries(source);
  const sourceNames = new Set(sourceEntries.map((entry) => entry.relative));
  const destinationEntries = await entries(destination);

  for (const entry of destinationEntries.sort((left, right) => right.relative.length - left.relative.length)) {
    if (!sourceNames.has(entry.relative)) await rm(entry.absolute, { recursive: true, force: true });
  }
  for (const entry of sourceEntries.filter((entry) => entry.directory)) {
    await mkdir(path.join(destination, entry.relative), { recursive: true });
  }
  for (const entry of sourceEntries.filter((entry) => !entry.directory)) {
    const target = path.join(destination, entry.relative);
    await mkdir(path.dirname(target), { recursive: true });
    await copyFile(entry.absolute, target);
  }
}

export async function writeText(file, contents) {
  await mkdir(path.dirname(file), { recursive: true });
  await writeFile(file, contents.endsWith('\n') ? contents : `${contents}\n`, 'utf8');
}

export async function childDirectories(root) {
  if (!(await exists(root))) return [];
  const children = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const absolute = path.join(root, entry.name);
    const info = await rejectSymlink(absolute, root);
    if (info.isDirectory()) children.push(absolute);
  }
  return children.sort((left, right) => left.localeCompare(right));
}

export async function remove(candidate) {
  await rm(candidate, { recursive: true, force: true });
}
