/* Markdown rendering for the chat page: markdown-it (raw HTML disabled), KaTeX math,
   highlight.js code blocks and lazily loaded Mermaid diagrams. */
(function () {
  'use strict';

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function renderMath(tex, display) {
    try {
      return katex.renderToString(tex, { displayMode: display, throwOnError: false, strict: 'ignore', trust: false, output: 'html' });
    } catch (e) {
      return '<span class="math-error">' + escapeHtml(tex) + '</span>';
    }
  }

  // A formula the ChatGPT page rendered but this KaTeX cannot (an extension such as \ce, or no TeX source): the
  // page's own KaTeX markup, reduced to KaTeX's spans and SVG strokes with layout-only styles.
  var PAGE_TAGS = { SPAN: 1, SVG: 1, PATH: 1, LINE: 1, RECT: 1, G: 1 };
  var PAGE_ATTRS = { 'class': 1, style: 1, 'aria-hidden': 1, viewbox: 1, width: 1, height: 1, preserveaspectratio: 1, d: 1,
    x: 1, y: 1, x1: 1, x2: 1, y1: 1, y2: 1, 'stroke-width': 1, fill: 1, stroke: 1, xmlns: 1 };
  function cleanPageNode(node) {
    Array.prototype.slice.call(node.children || []).forEach(function (child) {
      if (!PAGE_TAGS[child.tagName.toUpperCase()]) { child.parentNode.removeChild(child); return; }
      Array.prototype.slice.call(child.attributes).forEach(function (a) {
        var name = a.name.toLowerCase(), value = a.value;
        var bad = !PAGE_ATTRS[name] || /url\s*\(|expression|javascript:|@import|[<>]/i.test(value)
          || (name === 'class' && !/^[\w\s-]*$/.test(value)) || (name === 'fill' || name === 'stroke') && !/^(none|currentColor|#[0-9a-f]{3,8})$/i.test(value);
        if (bad) child.removeAttribute(a.name);
      });
      cleanPageNode(child);
    });
  }
  function pageMath(html, display) {
    var box = document.createElement('div');
    box.innerHTML = String(html);
    var root = box.firstElementChild;
    if (!root || !/katex-html/.test(root.getAttribute('class') || '')) return '';
    box.innerHTML = '';
    box.appendChild(root);
    cleanPageNode(box);
    var inner = '<span class="katex page-math">' + box.innerHTML + '</span>';
    return display ? '<span class="katex-display">' + inner + '</span>' : inner;
  }
  function mathFor(tex, display, env) {
    var map = env && env.pageMath, key = String(tex).replace(/\s+/g, ' ').trim();
    if (map && Object.prototype.hasOwnProperty.call(map, key)) { var html = pageMath(map[key], display); if (html) return html; }
    return renderMath(tex, display);
  }

  // Inline math: $...$, \(...\), and display forms $$...$$ / \[...\] written inline.
  function mathInline(state, silent) {
    var src = state.src, pos = state.pos, max = state.posMax, open, close, display;
    if (src.charCodeAt(pos) === 0x24 /* $ */) {
      display = src.charCodeAt(pos + 1) === 0x24;
      open = display ? '$$' : '$';
      close = open;
    } else if (src.charCodeAt(pos) === 0x5C /* \ */ && (src[pos + 1] === '(' || src[pos + 1] === '[')) {
      display = src[pos + 1] === '[';
      open = src.substr(pos, 2);
      close = display ? '\\]' : '\\)';
    } else {
      return false;
    }
    var start = pos + open.length, end = start;
    while (true) {
      end = src.indexOf(close, end);
      if (end < 0 || end >= max) return false;
      if (open === '$') { var slashes = 0; while (src.charCodeAt(end - 1 - slashes) === 0x5C) slashes++; if (slashes % 2) { end++; continue; } }
      break;
    }
    var content = src.slice(start, end);
    if (!content.trim()) return false;
    if (open === '$') {
      // Pandoc rule: no space just inside the delimiters and no digit right after, so "$5 and $10" stays text.
      if (/^\s|\s$/.test(content) || content.indexOf('`') >= 0) return false;
      if (/[0-9]/.test(src.charAt(end + 1))) return false;
    }
    if (!silent) {
      var token = state.push('math_inline', 'math', 0);
      token.content = content;
      token.markup = open;
      token.meta = { display: display };
    }
    state.pos = end + close.length;
    return true;
  }

  // Block math: a paragraph starting with $$ or \[ and ending with $$ or \] (possibly on the same line).
  function mathBlock(state, startLine, endLine, silent) {
    var pos = state.bMarks[startLine] + state.tShift[startLine], max = state.eMarks[startLine];
    var first = state.src.slice(pos, max), open, close;
    if (first.indexOf('$$') === 0) { open = '$$'; close = '$$'; }
    else if (first.indexOf('\\[') === 0) { open = '\\['; close = '\\]'; }
    else return false;
    var rest = first.slice(open.length), content, line = startLine, found = false;
    var idx = rest.indexOf(close);
    if (idx >= 0) {
      if (rest.slice(idx + close.length).trim()) return false;
      content = rest.slice(0, idx);
      found = true;
    } else {
      var parts = [rest];
      for (line = startLine + 1; line < endLine; line++) {
        var text = state.src.slice(state.bMarks[line] + state.tShift[line], state.eMarks[line]);
        var at = text.indexOf(close);
        if (at >= 0) {
          parts.push(text.slice(0, at));
          found = true;
          break;
        }
        parts.push(text);
      }
      content = parts.join('\n');
    }
    if (!found) return false;
    if (silent) return true;
    var token = state.push('math_block', 'math', 0);
    token.block = true;
    token.content = content;
    token.map = [startLine, line + 1];
    state.line = line + 1;
    return true;
  }

  function webPost(html) {
    var box = document.createElement('div');
    box.innerHTML = html;
    Array.prototype.forEach.call(box.querySelectorAll('pre > code'), function (code) {
      var m = /language-([\w+#.-]+)/.exec(code.getAttribute('class') || ''), pre = code.parentNode, holder = document.createElement('div');
      holder.innerHTML = md.renderer.rules.fence([{ info: m ? m[1] : '', content: code.textContent }], 0);
      if (holder.firstElementChild) pre.parentNode.replaceChild(holder.firstElementChild, pre);
    });
    Array.prototype.forEach.call(box.querySelectorAll('table'), function (table) {
      if (table.parentNode.classList && table.parentNode.classList.contains('table-wrap')) return;
      var wrap = document.createElement('div'); wrap.className = 'table-wrap';
      table.parentNode.replaceChild(wrap, table); wrap.appendChild(table);
    });
    // Same link and image rules as the other renderer: web and mail links only; images from data: or the workspace.
    Array.prototype.forEach.call(box.querySelectorAll('a[href]'), function (a) {
      if (!/^(https?:|mailto:)/i.test(a.getAttribute('href'))) a.removeAttribute('href');
    });
    Array.prototype.forEach.call(box.querySelectorAll('img'), function (img) {
      var src = img.getAttribute('src') || '';
      if (/^workspace:/i.test(src)) src = '/workspace/' + src.replace(/^workspace:\/*/i, '').split('/').map(encodeURIComponent).join('/');
      if (!/^(data:image\/|https?:|\/workspace\/)/i.test(src)) { img.parentNode.removeChild(img); return; }
      img.setAttribute('src', src); img.setAttribute('loading', 'lazy');
    });
    return box.innerHTML;
  }

  var md = window.markdownit({ html: false, linkify: true, breaks: false, typographer: false });
  md.inline.ruler.before('escape', 'math_inline', mathInline);
  md.block.ruler.before('fence', 'math_block', mathBlock, { alt: ['paragraph', 'reference', 'blockquote', 'list'] });
  md.renderer.rules.math_inline = function (tokens, i, options, env) { return mathFor(tokens[i].content, tokens[i].meta.display, env); };
  md.renderer.rules.math_block = function (tokens, i, options, env) { return mathFor(tokens[i].content, true, env) + '\n'; };

  var COPY_ICON = '<svg viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>';
  md.renderer.rules.fence = function (tokens, i) {
    var token = tokens[i], lang = (token.info || '').trim().split(/\s+/)[0].toLowerCase(), code = token.content;
    if (lang === 'mermaid') {
      return '<div class="diagram" data-mermaid="' + escapeHtml(code) + '"><div class="spinner"></div></div>';
    }
    var body;
    if (lang && window.hljs && hljs.getLanguage(lang)) {
      try { body = hljs.highlight(code, { language: lang, ignoreIllegals: true }).value; } catch (e) { body = escapeHtml(code); }
    } else {
      body = escapeHtml(code);
    }
    return '<div class="codeblock"><div class="head"><span>' + escapeHtml(lang || 'text') + '</span><button class="copy-code">' + COPY_ICON +
      '<span data-i18n="copy"></span></button></div><pre><code>' + body + '</code></pre></div>';
  };
  md.renderer.rules.table_open = function () { return '<div class="table-wrap"><table>'; };
  md.renderer.rules.table_close = function () { return '</table></div>'; };

  // Images: http(s) is blocked by the page policy, so only data: URIs and workspace files render.
  var defaultImage = md.renderer.rules.image;
  md.renderer.rules.image = function (tokens, i, options, env, self) {
    var token = tokens[i], src = token.attrGet('src') || '';
    if (/^workspace:/i.test(src)) src = src.replace(/^workspace:\/*/i, '');
    if (!/^(data:image\/|https?:)/i.test(src)) {
      src = '/workspace/' + src.replace(/^\/+/, '').split('/').map(encodeURIComponent).join('/');
    }
    token.attrSet('src', src);
    token.attrSet('loading', 'lazy');
    return defaultImage(tokens, i, options, env, self);
  };
  var defaultValidate = md.validateLink;
  md.validateLink = function (url) { return /^workspace:/i.test(url) || defaultValidate(url); };

  // Task list checkboxes: "- [ ] item" / "- [x] item".
  md.core.ruler.push('task_lists', function (state) {
    var tokens = state.tokens;
    for (var i = 2; i < tokens.length; i++) {
      if (tokens[i].type !== 'inline' || tokens[i - 2].type !== 'list_item_open') continue;
      var m = /^\[([ xX])\]\s+/.exec(tokens[i].content);
      if (!m || !tokens[i].children.length) continue;
      var first = tokens[i].children[0];
      first.content = first.content.replace(/^\[[ xX]\]\s+/, '');
      var box = new state.Token('html_inline', '', 0);
      box.content = m[1] === ' ' ? '☐ ' : '☑ ';
      tokens[i].children.unshift(box);
      tokens[i - 2].attrJoin('class', 'task-item');
    }
  });
  md.renderer.rules.html_inline = function (tokens, i) { return escapeHtml(tokens[i].content); };

  var mermaidState = null;
  function loadMermaid() {
    if (mermaidState) return mermaidState;
    mermaidState = new Promise(function (resolve, reject) {
      var s = document.createElement('script');
      s.src = 'vendor/mermaid.min.js';
      s.onload = function () {
        window.mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: document.documentElement.dataset.theme === 'dark' ? 'dark' : 'default', fontFamily: 'inherit' });
        resolve(window.mermaid);
      };
      s.onerror = reject;
      document.head.appendChild(s);
    });
    return mermaidState;
  }
  var diagramSeq = 0;
  function renderDiagrams(root) {
    var nodes = root.querySelectorAll('.diagram[data-mermaid]');
    if (!nodes.length) return;
    loadMermaid().then(function (mermaid) {
      Array.prototype.forEach.call(nodes, function (node) {
        var code = node.getAttribute('data-mermaid');
        node.removeAttribute('data-mermaid');
        mermaid.render('mmd' + (++diagramSeq), code).then(function (out) {
          node.innerHTML = out.svg;
        }).catch(function (err) {
          node.classList.add('failed');
          node.innerHTML = '<div class="error"></div><div class="codeblock"><pre><code></code></pre></div>';
          node.querySelector('.error').textContent = 'Mermaid: ' + String(err && err.message || err).split('\n')[0];
          node.querySelector('code').textContent = code;
          var stray = document.getElementById('dmmd' + diagramSeq);
          if (stray) stray.remove();
        });
      });
    }).catch(function () {});
  }

  window.OPhoneMarkdown = {
    render: function (text, pageMath) { return md.render(String(text || ''), { pageMath: pageMath || null }); },
    // ChatGPT web answers in their original Markdown: the open-source unified/remark/rehype stack with
    // chatgpt.com's math rules (vendor/web-markdown.min.js), then this view's code blocks, tables and link rules.
    renderWeb: function (text) {
      if (!window.OPhoneWebMarkdown) return md.render(String(text || ''), {});
      var html;
      try { html = window.OPhoneWebMarkdown.render(String(text || '')); } catch (e) { return md.render(String(text || ''), {}); }
      return webPost(html);
    },
    enhance: renderDiagrams,
    escape: escapeHtml
  };
})();
