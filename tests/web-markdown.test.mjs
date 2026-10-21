// Run with Node.js 18+: node --test tests/web-markdown.test.mjs
// The ChatGPT-web renderer bundle (tools/web-markdown) with chatgpt.com's math rules.
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

import { createRequire } from 'node:module';

// A real DOM (jsdom, a dev dependency of tools/web-markdown): the bundle uses the browser's parser like the WebView.
const require = createRequire(new URL('../tools/web-markdown/package.json', import.meta.url));
const { JSDOM } = require('jsdom');
const vendor = new URL('../app/src/main/assets/chat/vendor/', import.meta.url);
const dom = new JSDOM('<!doctype html><html><body></body></html>', { runScripts: 'outside-only' });
const context = dom.getInternalVMContext();
vm.runInContext(readFileSync(new URL('katex/katex.min.js', vendor), 'utf8'), context);
vm.runInContext(readFileSync(new URL('web-markdown.min.js', vendor), 'utf8'), context);
const OPhoneWebMarkdown = vm.runInContext('OPhoneWebMarkdown', context);
const render = text => OPhoneWebMarkdown.render(text);
const formulas = html => (html.match(/class="katex"/g) || []).length;

test('$$ and \\(…\\) / \\[…\\] are math; a single $ stays text, as on chatgpt.com', () => {
  assert.equal(formulas(render('价格是 $5 到 $10。')), 0);
  assert.equal(formulas(render('行内 \\(x^2\\) 与 $$y$$。')), 2);
  assert.equal(formulas(render('\\[\n\\int_0^1 x\\,dx\n\\]')), 1);
  assert.match(render('\\[\n\\int_0^1 x\\,dx\n\\]'), /katex-display/);
  assert.equal(formulas(render('$$\n\\begin{aligned}a&=b\\\\c&=d\\end{aligned}\n$$')), 1);
  assert.equal(formulas(render('一行 \\[a=b\\] 内')), 1);
});

test('code keeps its backslashes and dollars; tables and lists carry math', () => {
  const code = render('```latex\n\\(x\\) $$y$$\n```\n\n`\\(z\\)`');
  assert.equal(formulas(code), 0);
  assert.match(code, /\\\(x\\\)/);
  assert.equal(formulas(render('| a | b |\n| --- | --- |\n| \\(\\|x\\|\\) | 1 |')), 1);
  assert.equal(formulas(render('1. 第一步\n\n   \\[\n   E=mc^2\n   \\]\n2. 第二步')), 1);
});

test('raw HTML in an answer is never rendered', () => {
  const html = render('<script>alert(1)</script>\n\nText <img src=x onerror=alert(1)> **ok** <b onclick="x">b</b>');
  assert.doesNotMatch(html, /<script|<img|onerror|onclick/);
  assert.match(html, /<strong>ok<\/strong>/);
});
