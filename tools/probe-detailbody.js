// 直接读页面自己那次 detail 请求的响应体，确认页面拿到的是 JSON 还是 HTML。
// 这一步决定「借用页面 URL 再 fetch 一次」是否有意义。
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

  const reqUrls = new Map(); // requestId -> url
  const hits = [];

  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m.result);
      pending.delete(m.id);
      return;
    }
    if (m.method === 'Network.requestWillBeSent') {
      const u = m.params.request.url;
      if (u.indexOf('/aweme/detail') >= 0) reqUrls.set(m.params.requestId, u);
    }
    if (m.method === 'Network.responseReceived') {
      const u = m.params.response.url;
      if (u.indexOf('/aweme/detail') >= 0) {
        hits.push({ requestId: m.params.requestId, url: u, status: m.params.response.status });
      }
    }
  };
  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Network.enable');
  await send('Page.navigate', { url: target });
  await new Promise((r) => setTimeout(r, 16000));

  console.log('detail 响应数=' + hits.length);
  for (const h of hits.slice(0, 4)) {
    const body = await send('Network.getResponseBody', { requestId: h.requestId });
    const text = body && body.body ? body.body : '';
    const len = text.length;
    const hasDetail = text.indexOf('aweme_detail') >= 0;
    console.log(
      `  HTTP ${h.status} len=${len} hasDetail=${hasDetail} ct=${h.url.slice(30, 70)}`
    );
    if (!hasDetail && len > 0) {
      const head = body && body.base64Encoded
        ? Buffer.from(text, 'base64').toString('utf8', 0, 200)
        : text.slice(0, 200);
      console.log('    head: ' + head.replace(/\s+/g, ' '));
    }
  }
  ws.close();
  process.exit(0);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
