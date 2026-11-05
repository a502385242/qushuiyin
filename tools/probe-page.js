// 用 CDP 驱动无头 Chrome：抓页面真实发起的接口请求 + 渲染后的 DOM
// 目的：确认「真浏览器能否拿到视频信息」，以及拿到的是哪个字段
const http = require('http');

function get(path) {
  return new Promise((res, rej) => {
    http.get({ host: '127.0.0.1', port: 9222, path }, (r) => {
      let b = '';
      r.on('data', (d) => (b += d));
      r.on('end', () => res(b));
    }).on('error', rej);
  });
}

async function newTab(url) {
  // 新版 Chrome 要求 PUT
  return new Promise((res, rej) => {
    const req = http.request(
      { host: '127.0.0.1', port: 9222, path: '/json/new?' + encodeURIComponent(url), method: 'PUT' },
      (r) => {
        let b = '';
        r.on('data', (d) => (b += d));
        r.on('end', () => res(JSON.parse(b)));
      }
    );
    req.on('error', rej);
    req.end();
  });
}

const target = process.argv[2] || 'https://www.iesdouyin.com/share/video/7699062672146337075/';
const WAIT_MS = parseInt(process.argv[3] || '15000', 10);

(async () => {
  const tab = await newTab('about:blank');
  const ws = new WebSocket(tab.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  const requests = [];
  const responses = [];

  const send = (method, params = {}) =>
    new Promise((res) => {
      const msgId = ++id;
      pending.set(msgId, res);
      ws.send(JSON.stringify({ id: msgId, method, params }));
    });

  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m.result);
      pending.delete(m.id);
      return;
    }
    if (m.method === 'Network.requestWillBeSent') {
      const u = m.params.request.url;
      if (/aweme|iteminfo|detail|share\/video|web\/api|reflow/i.test(u)) requests.push(u);
    }
    if (m.method === 'Network.responseReceived') {
      const u = m.params.response.url;
      const ct = m.params.response.mimeType || '';
      if (/json/i.test(ct) && /douyin|byteimg|iesdouyin/i.test(u)) {
        responses.push({ url: u, status: m.params.response.status, type: m.params.response.mimeType, reqId: m.params.requestId });
      }
    }
  };

  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Network.enable');
  await send('Runtime.enable');
  await send('Page.navigate', { url: target });
  await new Promise((r) => setTimeout(r, WAIT_MS));

  const evalJs = async (expr) => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    return r && r.result ? r.result.value : null;
  };

  const info = await evalJs(`(() => {
    const vids = [...document.querySelectorAll('video')].map(v => ({src: v.src || v.currentSrc, poster: v.poster}));
    const html = document.documentElement.outerHTML;
    const keys = ['play_addr','videoInfoRes','item_list','playwm','bit_rate','_ROUTER_DATA','RENDER_DATA'];
    const hits = {};
    keys.forEach(k => hits[k] = (html.split(k).length - 1));
    return {
      title: document.title,
      url: location.href,
      htmlLen: html.length,
      videos: vids,
      hits,
      bodyText: (document.body ? document.body.innerText : '').slice(0, 300)
    };
  })()`);

  const resources = await evalJs(`JSON.stringify(performance.getEntriesByType('resource').map(e=>e.name).filter(n=>/aweme|iteminfo|detail|web\\/api|reflow/i.test(n)).slice(0,20))`);

  // 把匹配到的 JSON 响应体取回来
  const bodies = [];
  for (const r of responses.slice(0, 8)) {
    const b = await send('Network.getResponseBody', { requestId: r.reqId });
    if (b && b.body) bodies.push({ url: r.url, status: r.status, len: b.body.length, head: b.body.slice(0, 400) });
  }

  console.log(JSON.stringify({ info, requests: [...new Set(requests)].slice(0, 20), resources: JSON.parse(resources || '[]'), bodies }, null, 1));
  ws.close();
  process.exit(0);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
