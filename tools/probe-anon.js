// 在「无 Cookie 的隔离浏览器上下文」里跑同一套拦截脚本。
// 目的：验证不带登录态（等价于全新 Android WebView）时，aweme/detail 还能不能截到。
// 如果隔离上下文也成功，说明 App 失败与登录态无关，问题只可能在注入时机。
const http = require('http');

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
const waitMs = parseInt(process.argv[3] || '20000', 10);

const getVersion = () =>
  new Promise((res, rej) => {
    http
      .get('http://127.0.0.1:9222/json/version', (r) => {
        let b = '';
        r.on('data', (d) => (b += d));
        r.on('end', () => res(JSON.parse(b)));
      })
      .on('error', rej);
  });

(async () => {
  const ver = await getVersion();
  const ws = new WebSocket(ver.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  const send = (method, params = {}, sessionId) =>
    new Promise((res) => {
      const mid = ++id;
      pending.set(mid, res);
      ws.send(JSON.stringify({ id: mid, method, params, sessionId }));
    });
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m.result);
      pending.delete(m.id);
    }
  };
  await new Promise((r) => (ws.onopen = r));

  const ctx = await send('Target.createBrowserContext', { disposeOnDetach: true });
  const browserContextId = ctx.browserContextId;
  const t = await send('Target.createTarget', { url: 'about:blank', browserContextId });
  const att = await send('Target.attachToTarget', { targetId: t.targetId, flatten: true });
  const s = att.sessionId;

  await send('Page.enable', {}, s);
  await send('Runtime.enable', {}, s);
  await send('Page.addScriptToEvaluateOnNewDocument', { source: HOOK }, s);
  await send('Page.navigate', { url: target }, s);
  await new Promise((r) => setTimeout(r, waitMs));

  const cookies = await send('Storage.getCookies', { browserContextId });
  const dyCookies = (cookies.cookies || []).filter((c) => /douyin/.test(c.domain));

  const r = await send(
    'Runtime.evaluate',
    {
      expression: `(() => {
        const t = window.__qsyCaptured;
        const out = { href: location.href, title: document.title, url: location.href };
        if (!t) { out.captured = false; out.hooked = !!window.__qsyHooked; return JSON.stringify(out); }
        let d = null; try { d = JSON.parse(t); } catch (e) {}
        const v = d && d.aweme_detail ? d.aweme_detail.video : null;
        out.captured = true; out.len = t.length; out.status = d ? d.status_code : null;
        out.hasDetail = !!(d && d.aweme_detail);
        out.bitRateCount = v && v.bit_rate ? v.bit_rate.length : 0;
        return JSON.stringify(out);
      })()`,
      returnByValue: true,
    },
    s
  );

  console.log('COOKIES_IN_CTX=' + dyCookies.length);
  console.log(r.result.value);
  await send('Target.disposeBrowserContext', { browserContextId });
  ws.close();
  process.exit(0);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
