// Gör om ett OpenDocument-dokument (.odt) till HTML, och delar in HTML i sidor.
// Används för .odt (läses här) och .doc (görs om till HTML i appen med Apache POI).
(function () {
  const NS = {
    office: 'urn:oasis:names:tc:opendocument:xmlns:office:1.0',
    style: 'urn:oasis:names:tc:opendocument:xmlns:style:1.0',
    text: 'urn:oasis:names:tc:opendocument:xmlns:text:1.0',
    table: 'urn:oasis:names:tc:opendocument:xmlns:table:1.0',
    draw: 'urn:oasis:names:tc:opendocument:xmlns:drawing:1.0',
    fo: 'urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0',
    svg: 'urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0',
    xlink: 'http://www.w3.org/1999/xlink',
  };

  // Längd i ODF (cm, mm, in, pt, px) till CSS-pixlar (96 dpi)
  function px(v, def) {
    if (!v) return def;
    const m = /^(-?[\d.]+)(cm|mm|in|pt|pc|px)?$/.exec(String(v).trim());
    if (!m) return def;
    const n = parseFloat(m[1]);
    switch (m[2]) {
      case 'cm': return n * 37.7953;
      case 'mm': return n * 3.77953;
      case 'in': return n * 96;
      case 'pt': return n * 96 / 72;
      case 'pc': return n * 16;
      default: return n;
    }
  }
  const a = (el, ns, name) => el.getAttributeNS(NS[ns], name) || el.getAttribute(ns + ':' + name) || '';

  // ---- Stilar ----
  function readStyles(docs) {
    const styles = {};
    for (const d of docs) {
      if (!d) continue;
      const all = d.getElementsByTagNameNS(NS.style, 'style');
      for (const s of all) {
        const name = a(s, 'style', 'name');
        const fam = a(s, 'style', 'family');
        const tp = s.getElementsByTagNameNS(NS.style, 'text-properties')[0];
        const pp = s.getElementsByTagNameNS(NS.style, 'paragraph-properties')[0];
        const cp = s.getElementsByTagNameNS(NS.style, 'table-cell-properties')[0];
        const css = {};
        if (tp) {
          if (a(tp, 'fo', 'font-weight') === 'bold') css['font-weight'] = 'bold';
          if (a(tp, 'fo', 'font-style') === 'italic') css['font-style'] = 'italic';
          const fs = a(tp, 'fo', 'font-size'); if (fs && !fs.endsWith('%')) css['font-size'] = px(fs, 14) + 'px';
          const col = a(tp, 'fo', 'color'); if (col) css['color'] = col;
          const ul = a(tp, 'style', 'text-underline-style'); if (ul && ul !== 'none') css['text-decoration'] = 'underline';
          const fn = a(tp, 'style', 'font-name') || a(tp, 'fo', 'font-family'); if (fn) css['font-family'] = '"' + fn.replace(/'/g, '') + '", serif';
          const bg = a(tp, 'fo', 'background-color'); if (bg && bg !== 'transparent') css['background-color'] = bg;
        }
        if (pp) {
          const al = a(pp, 'fo', 'text-align'); if (al) css['text-align'] = al === 'start' ? 'left' : al === 'end' ? 'right' : al;
          for (const k of ['margin-top', 'margin-bottom', 'margin-left', 'margin-right', 'text-indent']) {
            const v = a(pp, 'fo', k); if (v && !v.endsWith('%')) css[k] = px(v, 0) + 'px';
          }
          const lh = a(pp, 'fo', 'line-height'); if (lh) css['line-height'] = lh.endsWith('%') ? (parseFloat(lh) / 100) : px(lh, 16) + 'px';
          const bg = a(pp, 'fo', 'background-color'); if (bg && bg !== 'transparent') css['background-color'] = bg;
          if (a(pp, 'fo', 'break-before') === 'page') css['--break'] = 'before';
        }
        if (cp) {
          const b = a(cp, 'fo', 'border'); if (b) css['border'] = b.replace(/(\d*\.?\d+)(cm|mm|in|pt)/, (m) => Math.max(1, px(m, 1)) + 'px');
          for (const side of ['top', 'bottom', 'left', 'right']) {
            const v = a(cp, 'fo', 'border-' + side); if (v) css['border-' + side] = v.replace(/(\d*\.?\d+)(cm|mm|in|pt)/, (m) => Math.max(1, px(m, 1)) + 'px');
          }
          const pad = a(cp, 'fo', 'padding'); if (pad) css['padding'] = px(pad, 2) + 'px';
          const bg = a(cp, 'fo', 'background-color'); if (bg && bg !== 'transparent') css['background-color'] = bg;
        }
        styles[(fam || '') + ':' + name] = { css, parent: a(s, 'style', 'parent-style-name'), fam };
        if (!styles[name]) styles[name] = styles[(fam || '') + ':' + name];
      }
    }
    return styles;
  }

  function styleCss(styles, fam, name, depth = 0) {
    if (!name || depth > 12) return {};
    const s = styles[fam + ':' + name] || styles[name];
    if (!s) return {};
    return Object.assign({}, styleCss(styles, fam, s.parent, depth + 1), s.css);
  }
  const cssText = (o) => Object.entries(o).filter(([k]) => !k.startsWith('--')).map(([k, v]) => k + ':' + v).join(';');

  function pageLayout(stylesDoc) {
    const def = { w: 793.7, h: 1122.5, mt: 75.6, mb: 75.6, ml: 75.6, mr: 75.6 };
    if (!stylesDoc) return def;
    const pl = stylesDoc.getElementsByTagNameNS(NS.style, 'page-layout-properties')[0];
    if (!pl) return def;
    return {
      w: px(a(pl, 'fo', 'page-width'), def.w), h: px(a(pl, 'fo', 'page-height'), def.h),
      mt: px(a(pl, 'fo', 'margin-top'), def.mt), mb: px(a(pl, 'fo', 'margin-bottom'), def.mb),
      ml: px(a(pl, 'fo', 'margin-left'), def.ml), mr: px(a(pl, 'fo', 'margin-right'), def.mr),
    };
  }

  // ---- Innehåll ----
  async function convert(node, ctx) {
    let html = '';
    for (const n of node.childNodes) {
      if (n.nodeType === 3) { html += esc(n.nodeValue); continue; }
      if (n.nodeType !== 1) continue;
      const ns = n.namespaceURI, ln = n.localName;
      if (ns === NS.text) {
        if (ln === 'p' || ln === 'h') {
          const css = styleCss(ctx.styles, 'paragraph', a(n, 'text', 'style-name'));
          let tag = 'p';
          if (ln === 'h') {
            const lvl = Math.min(6, parseInt(a(n, 'text', 'outline-level') || '1', 10));
            tag = 'h' + lvl;
          }
          const brk = css['--break'] === 'before' ? ' data-break="1"' : '';
          const inner = await convert(n, ctx);
          html += `<${tag}${brk} style="${cssText(css)}">${inner || '&#8203;'}</${tag}>`;
        } else if (ln === 'span') {
          html += `<span style="${cssText(styleCss(ctx.styles, 'text', a(n, 'text', 'style-name')))}">${await convert(n, ctx)}</span>`;
        } else if (ln === 's') {
          html += '&nbsp;'.repeat(parseInt(a(n, 'text', 'c') || '1', 10));
        } else if (ln === 'tab') {
          html += '<span style="display:inline-block;width:48px"></span>';
        } else if (ln === 'line-break') {
          html += '<br>';
        } else if (ln === 'a') {
          html += `<span style="color:#1a55c4;text-decoration:underline">${await convert(n, ctx)}</span>`;
        } else if (ln === 'list') {
          html += `<ul style="margin:4px 0 4px 18px;padding-left:18px">${await convert(n, ctx)}</ul>`;
        } else if (ln === 'list-item' || ln === 'list-header') {
          html += `<li>${await convert(n, ctx)}</li>`;
        } else if (ln === 'section' || ln === 'span' || ln === 'meta') {
          html += await convert(n, ctx);
        } else if (ln === 'note') {
          const cit = n.getElementsByTagNameNS(NS.text, 'note-citation')[0];
          if (cit) html += `<sup>${esc(cit.textContent)}</sup>`;
        } else if (ln === 'soft-page-break' || ln.endsWith('-decls') || ln === 'bookmark' || ln === 'bookmark-start' || ln === 'bookmark-end') {
          // ignoreras
        } else {
          html += await convert(n, ctx);
        }
      } else if (ns === NS.table) {
        if (ln === 'table') {
          html += `<table style="border-collapse:collapse;width:100%;margin:6px 0">${await convert(n, ctx)}</table>`;
        } else if (ln === 'table-row') {
          html += `<tr>${await convert(n, ctx)}</tr>`;
        } else if (ln === 'table-cell') {
          const cs = a(n, 'table', 'number-columns-spanned');
          const rs = a(n, 'table', 'number-rows-spanned');
          const css = styleCss(ctx.styles, 'table-cell', a(n, 'table', 'style-name'));
          if (!css.border && !css['border-top']) css.border = '1px solid #999';
          css['vertical-align'] = 'top'; if (!css.padding) css.padding = '3px 5px';
          html += `<td${cs ? ` colspan="${cs}"` : ''}${rs ? ` rowspan="${rs}"` : ''} style="${cssText(css)}">${await convert(n, ctx)}</td>`;
        } else if (ln === 'covered-table-cell' || ln === 'table-columns' || ln === 'table-column') {
          // ignoreras
        } else {
          html += await convert(n, ctx);
        }
      } else if (ns === NS.draw) {
        if (ln === 'frame') {
          const w = px(a(n, 'svg', 'width'), 0), h = px(a(n, 'svg', 'height'), 0);
          const img = n.getElementsByTagNameNS(NS.draw, 'image')[0];
          const href = img ? a(img, 'xlink', 'href') : '';
          if (href && ctx.zip.file(href)) {
            const b64 = await ctx.zip.file(href).async('base64');
            const mime = /\.png$/i.test(href) ? 'image/png' : /\.svg$/i.test(href) ? 'image/svg+xml' : /\.gif$/i.test(href) ? 'image/gif' : 'image/jpeg';
            html += `<img src="data:${mime};base64,${b64}" style="${w ? 'width:' + w + 'px;' : 'max-width:100%;'}${h ? 'height:' + h + 'px;' : ''}">`;
          } else {
            html += await convert(n, ctx);
          }
        } else if (ln === 'text-box' || ln === 'g') {
          html += await convert(n, ctx);
        }
      } else {
        html += await convert(n, ctx);
      }
    }
    return html;
  }
  function esc(s) { return s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])); }

  async function odtToHtml(bytes) {
    const zip = await JSZip.loadAsync(bytes);
    const parser = new DOMParser();
    const content = parser.parseFromString(await zip.file('content.xml').async('string'), 'application/xml');
    const stylesXml = zip.file('styles.xml') ? parser.parseFromString(await zip.file('styles.xml').async('string'), 'application/xml') : null;
    const styles = readStyles([stylesXml, content]);
    const body = content.getElementsByTagNameNS(NS.office, 'text')[0];
    const html = await convert(body, { styles, zip });
    return { html, layout: pageLayout(stylesXml) };
  }

  // ---- Sidindelning ----
  // Lägger blocken (stycken, rubriker, tabeller, listor, bilder) i sidor med rätt storlek och marginaler.
  function paginate(html, L, host) {
    host.innerHTML = '';
    const src = document.createElement('div');
    src.style.cssText = `position:absolute;left:-99999px;width:${L.w - L.ml - L.mr}px`;
    src.innerHTML = html;
    document.body.appendChild(src);
    const contentH = L.h - L.mt - L.mb;
    let page, box;
    const pages = [];
    function newPage() {
      page = document.createElement('section');
      page.className = 'pg';
      page.style.cssText = `width:${L.w}px;height:${L.h}px;padding:${L.mt}px ${L.mr}px ${L.mb}px ${L.ml}px;box-sizing:border-box;background:#fff;overflow:hidden;position:relative;margin-bottom:20px`;
      box = document.createElement('div');
      box.style.cssText = `width:${L.w - L.ml - L.mr}px;height:${contentH}px;overflow:hidden`;
      page.appendChild(box);
      host.appendChild(page);
      pages.push(page);
    }
    newPage();
    // <style> flyttas till sidhuvudet; omslutande div:ar (t.ex. sektioner från .doc) packas upp
    for (const st of Array.from(src.querySelectorAll('style'))) document.head.appendChild(st);
    function flatten(el) {
      const out = [];
      for (const c of Array.from(el.childNodes)) {
        const isWrap = c.nodeType === 1 && c.tagName === 'DIV' && c.children.length > 1 &&
          Array.from(c.childNodes).every(x => x.nodeType === 1 || !x.nodeValue.trim());
        if (isWrap) out.push(...flatten(c)); else out.push(c);
      }
      return out;
    }
    const blocks = flatten(src);
    for (const b of blocks) {
      if (b.nodeType === 3 && !b.nodeValue.trim()) continue;
      if (b.nodeType === 1 && box.childNodes.length) {
        // Sidbrytning: från .odt (data-break) eller CSS (page-break-before/break-before)
        const st = b.style || {};
        const pb = b.getAttribute('data-break') === '1' || st.pageBreakBefore === 'always' || st.breakBefore === 'page' ||
          (b.querySelector && b.querySelector(':scope > br[style*="page-break-before"], :scope > span > br[style*="page-break-before"]'));
        if (pb) newPage();
      }
      box.appendChild(b);
      if (box.scrollHeight <= contentH + 1) continue;
      // Får inte plats: tabeller delas per rad, annat flyttas till nästa sida
      if (b.tagName === 'TABLE' && b.rows.length > 1) {
        box.removeChild(b);
        let part = b.cloneNode(false); box.appendChild(part);
        for (const row of Array.from(b.rows)) {
          part.appendChild(row);
          if (box.scrollHeight > contentH + 1 && part.rows.length > 1) {
            part.removeChild(row);
            newPage();
            part = b.cloneNode(false); box.appendChild(part); part.appendChild(row);
          }
        }
      } else if (box.childNodes.length > 1) {
        box.removeChild(b);
        newPage();
        box.appendChild(b);
      }
    }
    src.remove();
    return pages;
  }

  window.SncOdt = { odtToHtml, paginate, px };
})();
