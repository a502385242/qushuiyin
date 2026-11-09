// 抓取 aweme/detail 请求的完整 URL 与请求头，用于验证「原样转发」是否可行。
// 输出一段可直接喂给 curl 的命令，便于在服务端侧独立复现。
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

const target = process.argv[2];
const waitMs = parseInt(process.argv[3] || '20000', 10);

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
      if (u.indexOf('/aweme/') >= 0) {
        hits.push({
          url: u,
          method: m.params.request.method,
          headers: m.params.request.headers,
          postData: m.params.request.postData || null,
        });
      }
    }
  };
  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Network.enable');
  await send('Page.navigate', { url: target });
  await new Promise((r) => setTimeout(r, waitMs));

  const detail = hits.filter((h) => h.url.indexOf('/aweme/detail') >= 0).pop();
  if (!detail) {
    console.log('NO_DETAIL_REQ capture_count=' + hits.length);
    console.log(JSON.stringify(hits.map((h) => h.url.slice(0, 120)).slice(0, 10), null, 2));
  } else {
    console.log('URL=' + detail.url);
    console.log('METHOD=' + detail.method);
    console.log('HDRS=' + JSON.stringify(detail.headers, null, 2));
  }
  ws.close();
  process.exit(0);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
