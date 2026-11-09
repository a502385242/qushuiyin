// 验证「在页面上下文里 fetch 同一个 detail URL」能否拿到数据。
// 这是 App 0.3.2 的主路径：cookie / 同源身份由 WebView 网络栈自动带上。
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

  const r = await send('Runtime.evaluate', {
    expression: `(async () => {
      try {
        const resp = await fetch(${JSON.stringify(detailUrl)}, {
          credentials: 'include',
          headers: { 'Accept': 'application/json, text/plain, */*' }
        });
        const t = await resp.text();
        let d = null; try { d = JSON.parse(t); } catch (e) {}
        const v = d && d.aweme_detail ? d.aweme_detail.video : null;
        return JSON.stringify({
          status: resp.status, len: t.length,
          hasDetail: !!(d && d.aweme_detail),
          status_code: d ? d.status_code : null,
          bitRate: v && v.bit_rate ? v.bit_rate.length : 0
        });
      } catch (e) { return 'ERR ' + e; }
    })()`,
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
