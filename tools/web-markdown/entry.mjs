// OPhone AI's renderer for ChatGPT web answers in the local view, built from open-source packages only
// (unified, remark-parse, remark-gfm, remark-math, remark-rehype, rehype-katex, rehype-stringify; all MIT).
// Math rules follow what chatgpt.com accepts: $$…$$ and \(…\) / \[…\] are math, a single $…$ is plain text.
// The \(…\) / \[…\] support below is OPhone AI's own preprocessing, not code taken from the site.
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import remarkRehype from 'remark-rehype';
import rehypeKatex from 'rehype-katex';
import rehypeStringify from 'rehype-stringify';

const processor = unified()
  .use(remarkParse)
  .use(remarkGfm)
  .use(remarkMath, { singleDollarTextMath: false })
  .use(remarkRehype) // raw HTML in the text is dropped, never rendered
  .use(rehypeKatex, { strict: 'ignore', throwOnError: false, output: 'html' })
  .use(rehypeStringify);

// Rewrites \[…\] as display math and \(…\) as inline math ($$…$$ in a line), outside code spans and fences.
export function backslashMath(text) {
  const out = [];
  let fence = null;
  const lines = String(text).split('\n');
  let block = null;
  for (const line of lines) {
    const marker = line.match(/^\s*(`{3,}|~{3,})/);
    if (block === null && marker && (!fence || line.trim().startsWith(fence))) {
      fence = fence ? null : marker[1];
      out.push(line);
      continue;
    }
    if (fence) { out.push(line); continue; }
    if (block !== null) {
      const end = line.indexOf('\\]');
      if (end < 0) { block.push(line); continue; }
      block.push(line.slice(0, end));
      out.push('$$', ...block, '$$');
      block = null;
      if (line.slice(end + 2).trim()) out.push(inline(line.slice(end + 2)));
      continue;
    }
    const start = line.trim() === '\\[' || /^\s*\\\[/.test(line) && line.indexOf('\\]') < 0 ? line.indexOf('\\[') : -1;
    if (start >= 0) {
      if (line.slice(0, start).trim()) out.push(inline(line.slice(0, start)));
      block = [line.slice(start + 2)].filter(s => s.trim());
      continue;
    }
    out.push(inline(line));
  }
  if (block !== null) out.push('\\[', ...block);
  return out.join('\n');
}
function inline(line) {
  // Leave inline code alone; convert \(…\) and one-line \[…\] elsewhere.
  return line.split(/(`+[^`]*`+)/).map((part, i) => i % 2 ? part
    : part.replace(/\\\[(.+?)\\\]/g, (m, tex) => '$$' + tex + '$$').replace(/\\\((.+?)\\\)/g, (m, tex) => '$$' + tex + '$$')).join('');
}

export function render(text) {
  return String(processor.processSync(backslashMath(text)));
}
