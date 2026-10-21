// Bundles entry.mjs into app/src/main/assets/chat/vendor/web-markdown.min.js as window.OPhoneWebMarkdown.
// KaTeX is not bundled again: the chat page already loads vendor/katex/katex.min.js (window.katex).
// Run: npm install && npm run build   (versions are pinned in package.json / package-lock.json)
import { build } from 'esbuild';
import { copyFileSync, readdirSync, mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';

const vendor = new URL('../../app/src/main/assets/chat/vendor/', import.meta.url);
const katexGlobal = {
  name: 'katex-global',
  setup(b) {
    b.onResolve({ filter: /^katex$/ }, () => ({ path: 'katex', namespace: 'katex-global' }));
    b.onLoad({ filter: /.*/, namespace: 'katex-global' }, () => ({ contents: 'module.exports = window.katex;', loader: 'js' }));
  }
};
const result = await build({
  entryPoints: [new URL('./entry.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')],
  bundle: true, minify: true, format: 'iife', globalName: 'OPhoneWebMarkdown', target: ['chrome69'],
  legalComments: 'eof', plugins: [katexGlobal], metafile: true,
  outfile: new URL('web-markdown.min.js', vendor).pathname.replace(/^\/([A-Za-z]:)/, '$1')
});

// Every package that ended up in the bundle, with its version, license and full license text (MIT, ISC and
// BSD all require the notice to travel with copies).
const here = new URL('./', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const packages = new Map();
for (const input of Object.keys(result.metafile.inputs)) {
  const m = input.replace(/\\/g, '/').match(/node_modules\/((?:@[^/]+\/)?[^/]+)\//);
  if (m) packages.set(m[1], here + 'node_modules/' + m[1]);
}
let notices = 'Open-source packages bundled in web-markdown.min.js (built by tools/web-markdown):\n\n';
for (const [name, dir] of [...packages].sort()) {
  const pkg = JSON.parse(readFileSync(dir + '/package.json', 'utf8'));
  // remark-math and rehype-katex publish no license file in their npm package; their repository's license is kept here.
  const shared = /^(remark-math|rehype-katex)$/.test(name) ? here + 'remark-math.LICENSE' : '';
  const file = readdirSync(dir).find(f => /^(licen[cs]e|copying)(\.|$)/i.test(f));
  notices += '==== ' + name + ' ' + pkg.version + ' (' + pkg.license + ')\n'
    + (file && existsSync(dir + '/' + file) ? readFileSync(dir + '/' + file, 'utf8').trim() : shared ? readFileSync(shared, 'utf8').trim() : 'License: ' + pkg.license + ' (see ' + (pkg.repository && (pkg.repository.url || pkg.repository) || name) + ')') + '\n\n';
}
writeFileSync(new URL('web-markdown.LICENSES.txt', vendor).pathname.replace(/^\/([A-Za-z]:)/, '$1'), notices);
console.log('bundled packages: ' + packages.size);

// KaTeX itself (same version as package.json): JS, CSS and WOFF2 fonts.
const dist = new URL('./node_modules/katex/dist/', import.meta.url), out = new URL('katex/', vendor);
const p = u => u.pathname.replace(/^\/([A-Za-z]:)/, '$1');
mkdirSync(p(new URL('fonts/', out)), { recursive: true });
copyFileSync(p(new URL('katex.min.js', dist)), p(new URL('katex.min.js', out)));
copyFileSync(p(new URL('katex.min.css', dist)), p(new URL('katex.min.css', out)));
for (const f of readdirSync(p(new URL('fonts/', dist)))) if (f.endsWith('.woff2')) copyFileSync(p(new URL('fonts/' + f, dist)), p(new URL('fonts/' + f, out)));
console.log('built web-markdown.min.js and copied KaTeX');
