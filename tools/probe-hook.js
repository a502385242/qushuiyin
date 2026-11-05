// 在真浏览器里验证 App 即将使用的 XHR/fetch 拦截脚本（HOOK_JS）能否截到 aweme/detail
// 用 Page.addScriptToEvaluateOnNewDocument 保证钩子在页面脚本之前执行，与 Android WebView 的注入时机等价
const http = require('http');

const req = (opts) =>
  new Promise((res, rej) => {
    const r = http.request(opts, (resp) => {
      let b = '';
      resp.on('data', (d) => (b += d));
      resp.on('end', () => res(b));
    });
    r.on('error', rej);
    r.end();
  });

const newTab = (url) =>
  req({ host: '127.0.0.1', port: 9222, path: '/json/new?' + encodeURIComponent(url), method: 'PUT' }).then(JSON.parse);

const HOOK = `(function () {
  if (window.__qsyHooked) return;
  window.__qsyHooked = true;
  var isDetail = function (u) { return String(u || '').indexOf('/aweme/detail') >= 0; };
  var post = function (t) {
    try { if (t && t.indexOf('aweme_detail') >= 0) window.__qsyCaptured = t; } catch (e) {}
  };
  var open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) { this.__qsyUrl = u; return open.apply(this, arguments); };
  var send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function () {
    var self = this;
    self.addEventListener('load', function () {
      try { if (isDetail(self.__qsyUrl)) post(self.responseText); } catch (e) {}
    });
    return send.apply(this, arguments);
  };
  if (window.fetch) {
    var f = window.fetch;
    window.fetch = function () {
      return f.apply(this, arguments).then(function (r) {
        try {
          if (isDetail(r && r.url)) r.clone().text().then(post, function () {});
        } catch (e) {}
        return r;
      });
    };
  }
})();`;

const target = process.argv[2];
const waitMs = parseInt(process.argv[3] || '16000', 10);

(async () => {
  const tab = await newTab('about:blank');
  const ws = new WebSocket(tab.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  const send = (m, p = {}) =>
    new Promise((res) => {
      const mid = ++id;
      pending.set(mid, res);
      ws.send(JSON.stringify({ id: mid, method: m, params: p }));
    });
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); }
  };
  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Page.addScriptToEvaluateOnNewDocument', { source: HOOK });
  await send('Page.navigate', { url: target });
  await new Promise((r) => setTimeout(r, waitMs));

  const r = await send('Runtime.evaluate', {
    expression: `(() => {
      const t = window.__qsyCaptured;
      if (!t) return JSON.stringify({ captured: false, hooked: !!window.__qsyHooked });
      let d = null; try { d = JSON.parse(t); } catch (e) {}
      const v = d && d.aweme_detail ? d.aweme_detail.video : null;
      return JSON.stringify({
        captured: true, hooked: true, len: t.length,
        status: d ? d.status_code : null,
        hasDetail: !!(d && d.aweme_detail),
        bitRateCount: v && v.bit_rate ? v.bit_rate.length : 0,
        topGear: v && v.bit_rate ? v.bit_rate.map(b=>b.gear_name).slice(0,3) : []
      });
    })()`,
    returnByValue: true,
  });
  console.log(r.result.value);
  ws.close();
  process.exit(0);
})().catch((e) => { console.error('ERR', e.message); process.exit(1); });
