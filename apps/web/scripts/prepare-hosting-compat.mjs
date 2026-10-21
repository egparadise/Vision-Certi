import { copyFile, readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(webRoot, 'dist');
const html = await readFile(join(dist, 'index.html'), 'utf8');

for (const extension of ['js', 'css']) {
  const match = html.match(new RegExp(`/assets/(index-[A-Za-z0-9_-]+\\.${extension})`));
  if (!match) throw new Error(`Built index.html has no ${extension} entrypoint`);
  await copyFile(join(dist, 'assets', match[1]), join(dist, 'assets', `compat.${extension}`));
}
