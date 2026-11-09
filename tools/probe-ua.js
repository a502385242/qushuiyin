// 验证「UA 与 sec-ch-ua 版本不一致会不会被抖音返回空体」。
// 从真浏览器抓一个 detail 请求（含 a_bogus / msToken），再用不同的请求头组合重发，
// 对比响应长度。空体 = 200 但 0 字节，正是 App 上看到的现象。
const http = require('http');
const https = require('https');

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

const closeTab = (id) =>
  new Promise((res) => {
    const r = http.request({ host: '127.0.0.1', port: 9222, path: '/json/close/' + id, method: 'GET' }, () => res());
    r.on('error', () => res());
    r.end();
  });

async function grab(videoUrl) {
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
  let detail = null;
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m.result);
      pending.delete(m.id);
      return;
    }
    if (m.method === 'Network.requestWillBeSent') {
      const u = m.params.request.url;
      if (u.indexOf('/aweme/detail') >= 0) detail = { url: u, headers: m.params.request.headers };
    }
  };
  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Network.enable');
  await send('Page.navigate', { url: videoUrl });
  await new Promise((r) => setTimeout(r, 16000));
  const ck = await send('Network.getAllCookies');
  ws.close();
  await closeTab(tab.id);
  return { detail, cookies: ck.cookies || [] };
}

const call = (url, headers) =>
  new Promise((resolve) => {
    const u = new URL(url);
    const req = https.request(
      { hostname: u.hostname, path: u.pathname + u.search, method: 'GET', headers },
      (resp) => {
        let n = 0;
        let head = '';
        resp.on('data', (d) => {
          n += d.length;
          if (head.length < 160) head += d.toString('utf8', 0, Math.min(d.length, 160));
        });
        resp.on('end', () => {
          resolve({
            code: resp.statusCode,
            len: n,
            ct: resp.headers['content-type'],
            ce: resp.headers['content-encoding'],
            head: head.replace(/\s+/g, ' ').slice(0, 120),
          });
        });
      }
    );
    req.on('error', (e) => resolve({ code: -1, len: 0, head: e.message }));
    req.end();
  });

(async () => {
  const video = process.argv[2] || 'https://www.douyin.com/video/7699062672146337075';
  const { detail, cookies } = await grab(video);
  if (!detail) {
    console.log('NO_DETAIL_REQ');
    process.exit(1);
  }
  const cookie = cookies
    .filter((c) => /(^|\.)douyin\.com$/.test(c.domain))
    .map((c) => c.name + '=' + c.value)
    .join('; ');

  const base = {
    Accept: 'application/json, text/plain, */*',
    Referer: video,
    'Accept-Language': 'zh-CN,zh;q=0.9',
    Cookie: cookie,
    uifid: detail.headers['uifid'] || '',
  };

  const cases = [
    ['A 全量头(UA=156, sec-ch-ua=156)', { ...base, 'User-Agent': detail.headers['User-Agent'], 'sec-ch-ua': detail.headers['sec-ch-ua'], 'sec-ch-ua-platform': '"Windows"', 'sec-ch-ua-mobile': '?0' }],
    ['B UA=120 + sec-ch-ua=156（矛盾）', { ...base, 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36', 'sec-ch-ua': detail.headers['sec-ch-ua'], 'sec-ch-ua-platform': '"Windows"', 'sec-ch-ua-mobile': '?0' }],
    ['C UA=120 无 sec-ch-ua', { ...base, 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36' }],
    ['D C + 无 Cookie', { Accept: base.Accept, Referer: video, 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36' }],
  ];

  console.log('cookie_len=' + cookie.length + '  uifid_len=' + (base.uifid || '').length);
  for (const [name, h] of cases) {
    const r = await call(detail.url, h);
    console.log(`${name}  ->  HTTP ${r.code} ${r.len}B ct=${r.ct} ce=${r.ce}`);
    if (r.len < 500) console.log('   head: ' + r.head);
    await new Promise((res) => setTimeout(res, 1200));
  }
  process.exit(0);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
