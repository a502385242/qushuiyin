// 对比页面内 fetch / XHR 请求同一 detail URL 的结果，判断第二次请求为什么变成 HTML。
const http = require('http');

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

const newTab = (url) =>
  new Promise((res, rej) => {
    const r = http.request(
      { host: '127.0.0.1', port: 9222, path: '/json/new?' + encodeURIComponent(url), method: 'PUT' },
      (resp) => {
        let b = '';
        resp.on('data', (d) => (b += d));
        resp.on('end', () => res(JSON.parse(b)));
      }
    );
    r.on('error', rej);
    r.end();
  });

const target = process.argv[2] || 'https://www.douyin.com/video/7699062672146337075';

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

  let detailUrl = null;
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m.result);
      pending.delete(m.id);
      return;
    }
    if (m.method === 'Network.requestWillBeSent') {
      const u = m.params.request.url;
      if (u.indexOf('/aweme/detail') >= 0) detailUrl = u;
    }
  };
  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Network.enable');
  await send('Page.navigate', { url: target });
  await new Promise((r) => setTimeout(r, 15000));

  if (!detailUrl) {
    console.log('NO_DETAIL_REQ');
    process.exit(1);
  }

  const sw = await send('Runtime.evaluate', {
    expression: `(async () => {
      const regs = 'serviceWorker' in navigator ? await navigator.serviceWorker.getRegistrations() : [];
      return JSON.stringify({ sw: regs.length, scope: regs.map(r => r.scope) });
    })()`,
    awaitPromise: true,
    returnByValue: true,
  });
  console.log('serviceWorker: ' + sw.result.value);

  const expr = `(async () => {
    const url = ${JSON.stringify(detailUrl)};
    const out = [];
    // 1) fetch 默认
    try {
      const r = await fetch(url, { credentials: 'include' });
      const t = await r.text();
      out.push({ m: 'fetch', s: r.status, redirect: r.redirected, u: r.url.slice(0, 60), len: t.length, detail: t.indexOf('aweme_detail') >= 0 });
    } catch (e) { out.push({ m: 'fetch', err: String(e) }); }
    // 2) fetch no-store
    try {
      const r = await fetch(url, { credentials: 'include', cache: 'no-store' });
      const t = await r.text();
      out.push({ m: 'fetch-nostore', s: r.status, redirect: r.redirected, len: t.length, detail: t.indexOf('aweme_detail') >= 0 });
    } catch (e) { out.push({ m: 'fetch-nostore', err: String(e) }); }
    // 3) XHR
    await new Promise((res) => {
      const x = new XMLHttpRequest();
      x.open('GET', url, true);
      x.withCredentials = true;
      x.setRequestHeader('Accept', 'application/json, text/plain, */*');
      x.onload = () => out.push({ m: 'xhr', s: x.status, len: x.responseText.length, detail: x.responseText.indexOf('aweme_detail') >= 0, type: x.getResponseHeader('content-type') });
      x.onerror = () => out.push({ m: 'xhr', err: 'network' });
      x.send();
    });
    return JSON.stringify(out);
  })()`;

  const r = await send('Runtime.evaluate', {
    expression: expr,
    awaitPromise: true,
    returnByValue: true,
  });
  console.log(r.result.value);
  ws.close();
  process.exit(0);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
