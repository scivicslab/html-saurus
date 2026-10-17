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
              // Inside the portal's right-pane iframe the left pane owns the theme control,
              // so hide this duplicate selector when the page is framed.
              if (window.self !== window.top) { sel.style.display = 'none'; return; }
              var t = localStorage.getItem('portal-theme') || 'dark-catppuccin';
              sel.value = t;
              sel.addEventListener('change', function() {
                var val = this.value;
                localStorage.setItem('portal-theme', val);
                document.documentElement.setAttribute('data-theme', val);
              });
            })();
            (function() {
              var input = document.getElementById('search-input');
              var results = document.getElementById('search-results');
              // Production mode has no dropdown: the box is a form that submits to the results
              // page, so there is nothing here to bind.
              if (!input || !results) return;
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
                // The build runs on the server's own thread and this asks how it is going. A
                // whole-project build takes minutes, and waiting for the response left the button
                // on 'Building...' for all of it -- and for good, whenever anything in between
                // dropped the connection.
                function poll(jobId) {
                  fetch('/api/build-status?jobId=' + encodeURIComponent(jobId))
                    .then(function(r) { return r.json(); })
                    .then(function(j) {
                      if (j.state === 'running') {
                        btn.textContent = 'Building\\u2026 ' + Math.round(j.ms / 1000) + 's';
                        setTimeout(function() { poll(jobId); }, 1500);
                        return;
                      }
                      btn.textContent = j.state === 'done'
                        ? '\\u2713 Done (' + j.ms + 'ms)' : '\\u2717 Error';
                      setTimeout(function() {
                        btn.textContent = '\\u21BB Rebuild'; btn.disabled = false;
                      }, 3000);
                    })
                    .catch(function() {
                      btn.textContent = '\\u21BB Rebuild'; btn.disabled = false;
                    });
                }
                btn.addEventListener('click', function() {
                  var sel = document.getElementById('rebuild-stage');
                  var stage = sel ? sel.value : 'html';
                  btn.disabled = true; btn.textContent = 'Building\\u2026';
                  fetch('/api/build-async/' + stage + '/' + encodeURIComponent('YADOC_BUILD_SITE'), {method: 'POST'})
                    .then(function(r) { return r.json(); })
                    .then(function(j) {
                      if (!j.jobId) { btn.textContent = '\\u2717 Error'; btn.disabled = false; return; }
                      poll(j.jobId);
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
                                      '<div class="sr-summary">' + hl(item.summary) + '</div>';
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
              // The summary marks the matched words with <b>. Everything is escaped first, then
              // those two tags alone are put back, so the page's own markup can never come through.
              function hl(s) {
                return esc(s).replace(/&lt;b&gt;/g, '<b>').replace(/&lt;\\/b&gt;/g, '</b>');
              }
              function breadcrumb(path) {
                var segs = (path||'').replace(/^\\//, '').split('/');
                return segs.slice(0, -1).map(function(seg) {
                  return seg.replace(/^\\d+_/, '');
                }).filter(function(s) { return s.length > 0; }).join(' \\u203a ');
              }
            })();
            // Copy buttons
            (function() {
              if (!document.querySelector('.copy-bar')) return;
              function flash(btn, label) {
                btn.classList.add('copied');
                var orig = btn.innerHTML;
                btn.innerHTML = '&#x2713; Copied';
                setTimeout(function() { btn.classList.remove('copied'); btn.innerHTML = orig; }, 1500);
              }
              // One list: the full-text formats each open a new tab showing what the server returns
              // for /api/source (the server reads the source file, so the frontmatter and every inline
              // construct are present; rebuilding Markdown from the rendered DOM could not recover
              // them), and the two copy entries write the clipboard. The list springs back to its
              // first entry after each choice, so the same entry can be chosen again.
              var sel = document.getElementById('view-format');
              if (sel) {
                function flashSelect() {
                  var first = sel.options[0], orig = first.text;
                  sel.classList.add('copied'); first.text = '\u2713 Copied';
                  setTimeout(function() { sel.classList.remove('copied'); first.text = orig; }, 1500);
                }
                sel.addEventListener('change', function() {
                  var choice = sel.value;
                  sel.value = '';
                  if (!choice) return;
                  if (choice === 'copy-path') { navigator.clipboard.writeText(sel.dataset.path).then(flashSelect); return; }
                  if (choice === 'copy-id') { navigator.clipboard.writeText(sel.dataset.docId).then(flashSelect); return; }
                  var src = sel.dataset.path;
                  if (!src) return;
                  window.open('/api/source?path=' + encodeURIComponent(src) + '&format=' + choice, '_blank');
                });
              }
            })();
            // On-demand translation: paragraphs, headings, list items, and table rows. A list with no
            // nested list is translated as one block, not item by item.
            (function() {
              var btn = document.getElementById('translate-btn');
              if (!btn) return;
              // Some pages mix languages (e.g. an English review followed by its Japanese
              // translation), so the direction is decided per block from that block's own
              // text, not once for the whole page.
              function isJapaneseText(text) {
                var letters = (text.match(/\\p{L}/gu) || []).length;
                if (!letters) return false;
                var kana = (text.match(/[\\u3040-\\u30FF]/g) || []).length;
                return kana * 20 >= letters;
              }
              function setAllOpen(open) {
                document.querySelectorAll('main details.inline-translation').forEach(function(d) { d.open = open; });
              }
              // A list item's own text, excluding nested <ul>/<ol> (translated separately).
              function ownText(el) {
                if (el.tagName === 'TR') {
                  return Array.prototype.map.call(el.children, function(c) {
                    return c.textContent.trim();
                  }).join(' | ');
                }
                if (el.tagName === 'LI') {
                  var t = '';
                  el.childNodes.forEach(function(c) {
                    if (c.nodeType === 1 && (c.tagName === 'UL' || c.tagName === 'OL')) return;
                    t += c.textContent;
                  });
                  return t;
                }
                return el.textContent;
              }
              // A list none of whose items holds another list. Its items are short parts of one
              // thing, so the whole list is translated in one call and gets one accordion.
              function isSimpleList(el) {
                return (el.tagName === 'UL' || el.tagName === 'OL') && !el.querySelector('ul, ol');
              }
              // One line per item, numbered for <ol> and dashed for <ul>, so the translation comes
              // back in the same line structure.
              function simpleListText(list) {
                var n = 0;
                return Array.prototype.map.call(list.children, function(item) {
                  var line = item.textContent.replace(/\\s+/g, ' ').trim();
                  n++;
                  return line ? ((list.tagName === 'OL' ? n + '. ' : '- ') + line) : '';
                }).filter(Boolean).join('\\n');
              }
              function makeAccordion(translation, keepLines) {
                var details = document.createElement('details');
                details.className = 'inline-translation';
                var summary = document.createElement('summary');
                var label = document.createElement('span');
                label.textContent = 'Translation';
                summary.appendChild(label);
                // Expand-all/collapse-all only make sense once at least one accordion exists, so
                // they live inside each accordion's own summary instead of a permanently-visible
                // top button. They act on every accordion on the page, not just this one.
                var controls = document.createElement('span');
                controls.className = 'translation-controls';
                var expandBtn = document.createElement('button');
                expandBtn.type = 'button';
                expandBtn.textContent = 'Expand all';
                expandBtn.addEventListener('click', function(e) { e.preventDefault(); e.stopPropagation(); setAllOpen(true); });
                var collapseBtn = document.createElement('button');
                collapseBtn.type = 'button';
                collapseBtn.textContent = 'Collapse all';
                collapseBtn.addEventListener('click', function(e) { e.preventDefault(); e.stopPropagation(); setAllOpen(false); });
                controls.appendChild(expandBtn);
                controls.appendChild(collapseBtn);
                summary.appendChild(controls);
                details.appendChild(summary);
                var body = document.createElement('div');
                body.textContent = translation;
                if (keepLines) body.style.whiteSpace = 'pre-line';
                details.appendChild(body);
                return details;
              }
              function insertTranslation(el, translation) {
                var details = makeAccordion(translation, isSimpleList(el));
                if (el.tagName === 'LI') {
                  // Insert before any nested <ul>/<ol> so the translation stays next to this
                  // item's own text instead of trailing the whole nested sub-list.
                  var nestedList = null;
                  for (var i = 0; i < el.children.length; i++) {
                    var c = el.children[i];
                    if (c.tagName === 'UL' || c.tagName === 'OL') { nestedList = c; break; }
                  }
                  el.insertBefore(details, nestedList);
                } else if (el.tagName === 'TR') {
                  var colCount = 0;
                  Array.prototype.forEach.call(el.children, function(c) { colCount += (c.colSpan || 1); });
                  var tr = document.createElement('tr');
                  tr.className = 'translation-row';
                  tr.dataset.translated = '1';
                  var td = document.createElement('td');
                  td.colSpan = colCount || 1;
                  td.appendChild(details);
                  tr.appendChild(td);
                  el.parentNode.insertBefore(tr, el.nextSibling);
                } else {
                  el.parentNode.insertBefore(details, el.nextSibling);
                }
              }
              var defaultLabel = btn.innerHTML;
              var shownLabel = '&#x1F310; Hide translation';
              var translated = false;
              // Counts runs: Hide, or a newer run, makes an older run's polling stop at its next step.
              var runId = 0;
              var runningJobId = null;
              function postJson(url, obj) {
                return fetch(url, {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json; charset=UTF-8' },
                  body: JSON.stringify(obj)
                }).then(function(r) { return r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)); });
              }
              function stopJob(jobId) {
                postJson('/api/import/stop', { jobId: jobId }).catch(function() {});
              }
              // The page is translated as one Batch Job on the server, which queues the blocks on the
              // GPU broker; this side only hands the blocks over and shows what has come back.
              function runTranslation() {
                var main = document.querySelector('main');
                if (!main) return;
                var items = [];
                main.querySelectorAll('p, h1, h2, h3, h4, h5, h6, li, tr, ul, ol').forEach(function(el) {
                  if (el.dataset.translated) return;
                  // A list is a block only when it is simple; the items inside a simple list are
                  // part of that block, so they are not blocks of their own.
                  if ((el.tagName === 'UL' || el.tagName === 'OL') && !isSimpleList(el)) return;
                  if (el.tagName !== 'UL' && el.tagName !== 'OL') {
                    var list = el.closest('ul, ol');
                    if (list && isSimpleList(list) && main.contains(list)) return;
                  }
                  el.dataset.translated = '1';
                  var text = (isSimpleList(el) ? simpleListText(el) : ownText(el)).trim();
                  if (!text) return;
                  items.push({ el: el, text: text, lang: isJapaneseText(text) ? 'English' : 'Japanese', done: false });
                });
                translated = true;
                if (!items.length) { btn.innerHTML = shownLabel; return; }
                var myRun = ++runId;
                btn.innerHTML = '&#x1F310; Translating\\u2026';
                function payload(list) {
                  return list.map(function(it) { return { text: it.text, lang: it.lang }; });
                }
                function finish(failedCount) {
                  runningJobId = null;
                  btn.innerHTML = shownLabel + (failedCount ? ' (' + failedCount + ' failed)' : '');
                }
                function failRemaining() {
                  var failed = items.filter(function(it) { return !it.done; });
                  failed.forEach(function(it) { it.done = true; insertTranslation(it.el, '(translation failed)'); });
                  finish(failed.length);
                }
                function poll(jobId) {
                  function tick() {
                    if (myRun !== runId) return;
                    var open = items.filter(function(it) { return !it.done; });
                    // The job's state is read before the cache is, so a job seen finished has all its
                    // results in the lookup that follows.
                    fetch('/api/import/status?jobId=' + encodeURIComponent(jobId))
                      .then(function(r) { return r.ok ? r.json() : null; })
                      .catch(function() { return { state: 'running' }; })
                      .then(function(job) {
                        return postJson('/api/translate-lookup', { blocks: payload(open) }).then(function(found) {
                          return { job: job, found: found.translations };
                        });
                      })
                      .then(function(res) {
                        if (myRun !== runId) return;
                        open.forEach(function(it, i) {
                          if (res.found[i]) { it.done = true; insertTranslation(it.el, res.found[i]); }
                        });
                        var remaining = items.filter(function(it) { return !it.done; }).length;
                        var ended = !res.job || res.job.state !== 'running';
                        if (remaining === 0) { finish(0); return; }
                        if (ended) { failRemaining(); return; }
                        btn.innerHTML = '&#x1F310; Translating ' + (items.length - remaining)
                          + ' / ' + items.length + '\\u2026';
                        setTimeout(tick, 1500);
                      })
                      .catch(function() { if (myRun === runId) setTimeout(tick, 3000); });
                  }
                  tick();
                }
                postJson('/api/translate-page', { label: document.title, blocks: payload(items) })
                  .then(function(start) {
                    if (myRun !== runId) { stopJob(start.jobId); return; }
                    runningJobId = start.jobId;
                    poll(start.jobId);
                  })
                  .catch(function() { if (myRun === runId) failRemaining(); });
              }
              function clearTranslation() {
                runId++;
                if (runningJobId) { stopJob(runningJobId); runningJobId = null; }
                document.querySelectorAll('main tr.translation-row').forEach(function(tr) { tr.remove(); });
                document.querySelectorAll('main details.inline-translation').forEach(function(d) { d.remove(); });
                document.querySelectorAll('main [data-translated]').forEach(function(el) { delete el.dataset.translated; });
                translated = false;
                btn.innerHTML = defaultLabel;
              }
              btn.addEventListener('click', function() {
                if (translated) { clearTranslation(); } else { runTranslation(); }
              });
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
