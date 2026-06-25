package com.scivicslab.htmlsaurus;

/** Generates the inline JavaScript block embedded in every rendered HTML page. */
class PageScripts {

    private PageScripts() {}

    static String render() {
        return """
            <script src="https://cdn.jsdelivr.net/npm/mermaid@11.13.0/dist/mermaid.min.js"
              integrity="sha384-tI0sDqjGJcqrQ8e/XKiQGS+ee11v5knTNWx2goxMBxe4DO9U0uKlfxJtYB9ILZ4j"
              crossorigin="anonymous"></script>
            <script>mermaid.initialize({startOnLoad: true});</script>
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"
              integrity="sha384-7zkQWkzuo3B5mTepMUcHkMB5jZaolc2xDwL6VFqjFALcbeS9Ggm/Yr2r3Dy4lfFg"
              crossorigin="anonymous"></script>
            <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"
              integrity="sha384-43gviWU0YVjaDtb/GhzOouOXtZMP/7XUzwPTstBeZFe/+rCMvRwr4yROQP43s0Xk"
              crossorigin="anonymous"
              onload="renderMathInElement(document.body, {delimiters: [
                {left:'$$',right:'$$',display:true},{left:'$',right:'$',display:false}]});"></script>
            <script>
            (function() {
              var toggle = document.getElementById('menu-toggle');
              var sidebar = document.getElementById('sidebar');
              var overlay = document.getElementById('sidebar-overlay');
              if (!toggle || !sidebar || !overlay) return;
              function openSidebar() {
                sidebar.classList.add('open');
                overlay.classList.add('open');
                toggle.setAttribute('aria-expanded', 'true');
              }
              function closeSidebar() {
                sidebar.classList.remove('open');
                overlay.classList.remove('open');
                toggle.setAttribute('aria-expanded', 'false');
              }
              toggle.addEventListener('click', function() {
                sidebar.classList.contains('open') ? closeSidebar() : openSidebar();
              });
              overlay.addEventListener('click', closeSidebar);
              document.addEventListener('keydown', function(e) {
                if (e.key === 'Escape') closeSidebar();
              });
            })();
            document.querySelectorAll('nav.side a.cat-label').forEach(function(link) {
              link.addEventListener('click', function(e) { e.stopPropagation(); });
            });
            document.querySelectorAll('.cat-header').forEach(function(header) {
              header.addEventListener('click', function() {
                var children = this.nextElementSibling;
                var arrow = this.querySelector('.cat-arrow');
                children.classList.toggle('open');
                var isOpen = children.classList.contains('open');
                arrow.textContent = isOpen ? '▼' : '▶';
                var key = header.dataset.cat;
                if (key) {
                  if (isOpen) localStorage.setItem('hs-cat:' + key, '1');
                  else localStorage.removeItem('hs-cat:' + key);
                }
              });
            });
            document.querySelectorAll('.cat-header[data-cat]').forEach(function(header) {
              if (localStorage.getItem('hs-cat:' + header.dataset.cat) === '1') {
                var children = header.nextElementSibling;
                var arrow = header.querySelector('.cat-arrow');
                children.classList.add('open');
                arrow.textContent = '▼';
              }
            });
            (function() {
              var sel = document.getElementById('theme-sel');
              if (!sel) return;
              var t = localStorage.getItem('md2html-theme') || 'default';
              sel.value = t;
              sel.addEventListener('change', function() {
                var val = this.value;
                localStorage.setItem('md2html-theme', val);
                if (val === 'default') document.documentElement.removeAttribute('data-theme');
                else document.documentElement.setAttribute('data-theme', val);
              });
            })();
            (function() {
              var input = document.getElementById('search-input');
              var results = document.getElementById('search-results');
              var timer = null;
              input.addEventListener('input', function() {
                clearTimeout(timer);
                var q = this.value.trim();
                if (!q) { results.classList.remove('open'); return; }
                timer = setTimeout(function() { doSearch(q); }, 300);
              });
              input.addEventListener('keydown', function(e) {
                if (e.key === 'Escape') { results.classList.remove('open'); input.blur(); }
              });
              document.addEventListener('click', function(e) {
                if (!document.getElementById('search-wrap').contains(e.target))
                  results.classList.remove('open');
              });
              (function() {
                var btn = document.getElementById('rebuild-btn');
                if (!btn) return;
                btn.addEventListener('click', function() {
                  btn.disabled = true; btn.textContent = 'Building\\u2026';
                  fetch('YADOC_BUILD_URL', {method: 'POST'})
                    .then(function(r) { return r.json(); })
                    .then(function(j) {
                      btn.textContent = j.status === 'ok'
                        ? '\\u2713 Done (' + j.ms + 'ms)' : '\\u2717 Error';
                      setTimeout(function() {
                        btn.textContent = '\\u21BB Rebuild'; btn.disabled = false;
                      }, 3000);
                    })
                    .catch(function() {
                      btn.textContent = '\\u21BB Rebuild'; btn.disabled = false;
                    });
                });
              })();
              var SEARCH_URL = 'YADOC_SEARCH_URL';
              function doSearch(q) {
                var sep = SEARCH_URL.indexOf('?') >= 0 ? '&' : '?';
                fetch(SEARCH_URL + sep + 'q=' + encodeURIComponent(q))
                  .then(function(r) { return r.json(); })
                  .then(function(data) {
                    results.innerHTML = '';
                    if (!data.length) {
                      results.innerHTML = '<div class="sr-empty">No results.</div>';
                    } else {
                      data.forEach(function(item) {
                        var a = document.createElement('a');
                        a.className = 'sr-item'; a.href = item.path;
                        a.innerHTML = '<div class="sr-title">' + esc(item.title) + '</div>' +
                                      '<div class="sr-breadcrumb">' + esc(breadcrumb(item.pagePath || item.path)) + '</div>' +
                                      '<div class="sr-summary">' + esc(item.summary) + '</div>';
                        results.appendChild(a);
                      });
                    }
                    results.classList.add('open');
                  })
                  .catch(function() {
                    results.innerHTML = '<div class="sr-empty">Search requires --serve mode.</div>';
                    results.classList.add('open');
                  });
              }
              function esc(s) { return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
              function breadcrumb(path) {
                var segs = (path||'').replace(/^\\//, '').split('/');
                return segs.slice(0, -1).map(function(seg) {
                  return seg.replace(/^\\d+_/, '');
                }).filter(function(s) { return s.length > 0; }).join(' \\u203a ');
              }
            })();
            // Copy buttons
            (function() {
              if (!document.getElementById('copy-text-btn')) return;
              function flash(btn, label) {
                btn.classList.add('copied');
                var orig = btn.innerHTML;
                btn.innerHTML = '&#x2713; Copied';
                setTimeout(function() { btn.classList.remove('copied'); btn.innerHTML = orig; }, 1500);
              }
              function getContent() {
                var main = document.querySelector('main');
                var clone = main.cloneNode(true);
                // Remove h1 and copy-bar from clone
                var h1 = clone.querySelector('h1');
                if (h1) h1.remove();
                var bar = clone.querySelector('.copy-bar');
                if (bar) bar.remove();
                return clone;
              }
              // Plain text copy
              document.getElementById('copy-text-btn').addEventListener('click', function() {
                var btn = this;
                var clone = getContent();
                var text = clone.innerText || clone.textContent;
                navigator.clipboard.writeText(text.trim()).then(function() { flash(btn); });
              });
              // Markdown copy
              document.getElementById('copy-md-btn').addEventListener('click', function() {
                var btn = this;
                var clone = getContent();
                var md = htmlToMd(clone);
                navigator.clipboard.writeText(md.trim()).then(function() { flash(btn); });
              });
              // Path copy
              document.getElementById('copy-path-btn').addEventListener('click', function() {
                var btn = this;
                navigator.clipboard.writeText(btn.dataset.path).then(function() { flash(btn); });
              });
              function htmlToMd(el) {
                var out = '';
                var children = el.childNodes;
                for (var i = 0; i < children.length; i++) {
                  var n = children[i];
                  if (n.nodeType === 3) { out += n.textContent; continue; }
                  if (n.nodeType !== 1) continue;
                  var tag = n.tagName;
                  if (tag === 'H2') { out += '\\n## ' + n.textContent.trim() + '\\n\\n'; }
                  else if (tag === 'H3') { out += '\\n### ' + n.textContent.trim() + '\\n\\n'; }
                  else if (tag === 'H4') { out += '\\n#### ' + n.textContent.trim() + '\\n\\n'; }
                  else if (tag === 'P') { out += mdInline(n) + '\\n\\n'; }
                  else if (tag === 'PRE') {
                    var code = n.querySelector('code');
                    var lang = '';
                    if (code && code.className) {
                      var m = code.className.match(/language-(\\S+)/);
                      if (m) lang = m[1];
                    }
                    out += '```' + lang + '\\n' + (code || n).textContent + '```\\n\\n';
                  }
                  else if (tag === 'UL') { out += mdList(n, '- ', 0) + '\\n'; }
                  else if (tag === 'OL') { out += mdOList(n, 0) + '\\n'; }
                  else if (tag === 'BLOCKQUOTE') { out += n.textContent.trim().split('\\n').map(function(l) { return '> ' + l; }).join('\\n') + '\\n\\n'; }
                  else if (tag === 'TABLE') { out += mdTable(n) + '\\n'; }
                  else if (tag === 'DIV' && n.classList.contains('admonition')) {
                    var title = n.querySelector('.admonition-title');
                    var body = n.querySelector('.admonition-body');
                    var type = 'note';
                    n.classList.forEach(function(c) { if (c.startsWith('admonition-') && c !== 'admonition-title' && c !== 'admonition-body') type = c.replace('admonition-', ''); });
                    out += ':::' + type + (title ? '[' + title.textContent.trim() + ']' : '') + '\\n';
                    if (body) out += body.textContent.trim();
                    out += '\\n:::\\n\\n';
                  }
                  else { out += htmlToMd(n); }
                }
                return out;
              }
              function mdInline(el) {
                var r = '';
                el.childNodes.forEach(function(n) {
                  if (n.nodeType === 3) { r += n.textContent; }
                  else if (n.nodeType === 1) {
                    var t = n.tagName;
                    if (t === 'CODE') r += '`' + n.textContent + '`';
                    else if (t === 'STRONG' || t === 'B') r += '**' + n.textContent + '**';
                    else if (t === 'EM' || t === 'I') r += '*' + n.textContent + '*';
                    else if (t === 'A') r += '[' + n.textContent + '](' + n.getAttribute('href') + ')';
                    else if (t === 'IMG') r += '![' + (n.getAttribute('alt')||'') + '](' + n.getAttribute('src') + ')';
                    else r += n.textContent;
                  }
                });
                return r;
              }
              function mdList(ul, marker, depth) {
                var r = '';
                var items = ul.children;
                for (var i = 0; i < items.length; i++) {
                  if (items[i].tagName !== 'LI') continue;
                  var indent = '  '.repeat(depth);
                  var sub = items[i].querySelector('ul,ol');
                  var text = '';
                  items[i].childNodes.forEach(function(c) {
                    if (c === sub) return;
                    if (c.nodeType === 1 && (c.tagName === 'UL' || c.tagName === 'OL')) return;
                    text += c.textContent;
                  });
                  r += indent + marker + text.trim() + '\\n';
                  if (sub) {
                    if (sub.tagName === 'OL') r += mdOList(sub, depth + 1);
                    else r += mdList(sub, '- ', depth + 1);
                  }
                }
                return r;
              }
              function mdOList(ol, depth) {
                var r = '';
                var items = ol.children;
                var num = 1;
                for (var i = 0; i < items.length; i++) {
                  if (items[i].tagName !== 'LI') continue;
                  var indent = '  '.repeat(depth);
                  var sub = items[i].querySelector('ul,ol');
                  var text = '';
                  items[i].childNodes.forEach(function(c) {
                    if (c.nodeType === 1 && (c.tagName === 'UL' || c.tagName === 'OL')) return;
                    text += c.textContent;
                  });
                  r += indent + (num++) + '. ' + text.trim() + '\\n';
                  if (sub) {
                    if (sub.tagName === 'OL') r += mdOList(sub, depth + 1);
                    else r += mdList(sub, '- ', depth + 1);
                  }
                }
                return r;
              }
              function mdTable(table) {
                var rows = table.querySelectorAll('tr');
                if (!rows.length) return '';
                var r = '';
                rows.forEach(function(row, ri) {
                  var cells = row.querySelectorAll('th,td');
                  var line = '|';
                  cells.forEach(function(c) { line += ' ' + c.textContent.trim() + ' |'; });
                  r += line + '\\n';
                  if (ri === 0) {
                    var sep = '|';
                    cells.forEach(function() { sep += ' --- |'; });
                    r += sep + '\\n';
                  }
                });
                return r;
              }
            })();
            // Right-side TOC: scroll-spy highlight
            (function() {
              var tocLinks = document.querySelectorAll('aside.toc a');
              if (!tocLinks.length) return;
              var headings = [];
              tocLinks.forEach(function(a) {
                var id = a.getAttribute('href').substring(1);
                var el = document.getElementById(id);
                if (el) headings.push({el: el, link: a});
              });
              function updateToc() {
                var scrollTop = window.scrollY + 80;
                var current = null;
                for (var i = 0; i < headings.length; i++) {
                  if (headings[i].el.offsetTop <= scrollTop) current = headings[i];
                }
                tocLinks.forEach(function(a) { a.classList.remove('toc-active'); });
                if (current) current.link.classList.add('toc-active');
              }
              window.addEventListener('scroll', updateToc);
              updateToc();
            })();
            var active = document.querySelector('nav.side a.active');
            if (active) {
              var el = active.parentElement;
              while (el && !el.classList.contains('content-wrap')) {
                if (el.classList.contains('cat-children')) {
                  el.classList.add('open');
                  var arrow = el.previousElementSibling && el.previousElementSibling.querySelector('.cat-arrow');
                  if (arrow) arrow.textContent = '▼';
                  var header = el.previousElementSibling;
                  if (header && header.dataset.cat) {
                    localStorage.setItem('hs-cat:' + header.dataset.cat, '1');
                  }
                }
                el = el.parentElement;
              }
            }
            // Pre-block toolbar: copy + wrap-toggle buttons
            (function() {
              document.querySelectorAll('main pre').forEach(function(pre) {
                var container = document.createElement('div');
                container.className = 'pre-container';
                var toolbar = document.createElement('div');
                toolbar.className = 'pre-toolbar';
                // Copy button
                var copyBtn = document.createElement('button');
                copyBtn.innerHTML = '&#x1F4CB; Copy';
                copyBtn.title = 'Copy to clipboard';
                copyBtn.addEventListener('click', function() {
                  var text = pre.textContent;
                  navigator.clipboard.writeText(text.trim()).then(function() {
                    copyBtn.classList.add('copied');
                    copyBtn.innerHTML = '&#x2713; Copied';
                    setTimeout(function() { copyBtn.classList.remove('copied'); copyBtn.innerHTML = '&#x1F4CB; Copy'; }, 1500);
                  });
                });
                // Wrap toggle button (wrap is default)
                var wrapBtn = document.createElement('button');
                wrapBtn.innerHTML = '&#x21B5; Wrap';
                wrapBtn.title = 'Toggle line wrap';
                wrapBtn.classList.add('active');
                wrapBtn.addEventListener('click', function() {
                  if (container.classList.contains('pre-nowrap')) {
                    container.classList.remove('pre-nowrap');
                    wrapBtn.classList.add('active');
                    wrapBtn.innerHTML = '&#x21B5; Wrap';
                  } else {
                    container.classList.add('pre-nowrap');
                    wrapBtn.classList.remove('active');
                    wrapBtn.innerHTML = '&#x2194; NoWrap';
                  }
                });
                toolbar.appendChild(copyBtn);
                toolbar.appendChild(wrapBtn);
                pre.parentNode.insertBefore(container, pre);
                container.appendChild(toolbar);
                container.appendChild(pre);
              });
            })();
            // Language dropdown and "More" overflow dropdown toggle
            (function() {
              document.querySelectorAll('.lang-btn, .nav-more-btn').forEach(function(btn) {
                btn.addEventListener('click', function(e) {
                  e.stopPropagation();
                  btn.parentElement.classList.toggle('open');
                });
              });
              document.addEventListener('click', function() {
                document.querySelectorAll('.lang-dropdown.open, .nav-more-dropdown.open').forEach(function(d) {
                  d.classList.remove('open');
                });
              });
            })();
            </script>
            """;
    }
}
