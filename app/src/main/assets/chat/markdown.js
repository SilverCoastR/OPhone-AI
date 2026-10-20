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
      if (src.charCodeAt(end - 1) === 0x5C && open === '$') { end++; continue; }
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

  var md = window.markdownit({ html: false, linkify: true, breaks: false, typographer: false });
  md.inline.ruler.before('escape', 'math_inline', mathInline);
  md.block.ruler.before('fence', 'math_block', mathBlock, { alt: ['paragraph', 'reference', 'blockquote', 'list'] });
  md.renderer.rules.math_inline = function (tokens, i) { return renderMath(tokens[i].content, tokens[i].meta.display); };
  md.renderer.rules.math_block = function (tokens, i) { return renderMath(tokens[i].content, true) + '\n'; };

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
    render: function (text) { return md.render(String(text || '')); },
    enhance: renderDiagrams,
    escape: escapeHtml
  };
})();
