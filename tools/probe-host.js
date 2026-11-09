// 验证「同一份参数（a_bogus/msToken 全带）换个域名就被回空体」。
// 真机日志里详情接口走的是 www-hj.douyin.com，PC 上走 www.douyin.com。
// 这里把 PC 抓到的请求原样重放给两个域名，对比响应字节数。
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
  const hosts = new Set();
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m.result);
      pending.delete(m.id);
      return;
    }
    if (m.method === 'Network.requestWillBeSent') {
      const u = m.params.request.url;
      if (u.indexOf('douyin.com') > 0) hosts.add(new URL(u).hostname);
      if (u.indexOf('/aweme/detail') >= 0 && !detail) detail = { url: u, headers: m.params.request.headers };
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
  return { detail, cookies: ck.cookies || [], hosts: [...hosts] };
}

const call = (target, headers) =>
  new Promise((resolve) => {
    const u = new URL(target);
    const req = https.request(
      { hostname: u.hostname, path: u.pathname + u.search, method: 'GET', headers },
      (resp) => {
        let n = 0;
        let head = '';
        resp.on('data', (d) => {
          n += d.length;
          if (head.length < 200) head += d.toString('utf8', 0, Math.min(d.length, 200));
        });
        resp.on('end', () =>
          resolve({
            code: resp.statusCode,
            len: n,
            ct: resp.headers['content-type'],
            ce: resp.headers['content-encoding'],
            hasDetail: head.indexOf('aweme_detail') >= 0,
            head: head.replace(/\s+/g, ' ').slice(0, 150),
          })
        );
      }
    );
    req.on('error', (e) => resolve({ code: -1, len: 0, head: e.message, hasDetail: false }));
    req.end();
  });

(async () => {
  const video = process.argv[2] || 'https://www.douyin.com/video/7699062672146337075';
  const { detail, cookies, hosts } = await grab(video);
  if (!detail) {
    console.log('NO_DETAIL_REQ  hosts=' + hosts.join(','));
    process.exit(1);
  }
  console.log('PC 抓到的详情域名: ' + new URL(detail.url).hostname);
  console.log('页面访问过的 douyin 域名: ' + hosts.join(', '));

  const cookie = cookies
    .filter((c) => /(^|\.)douyin\.com$/.test(c.domain))
    .map((c) => c.name + '=' + c.value)
    .join('; ');

  const u = new URL(detail.url);
  const pathAndQuery = u.pathname + u.search;

  const full = {};
  for (const [k, v] of Object.entries(detail.headers)) {
    const lk = k.toLowerCase();
    if (['host', 'content-length', 'connection', 'accept-encoding'].indexOf(lk) >= 0) continue;
    full[k] = v;
  }
  full.Cookie = cookie;

  console.log('cookie_len=' + cookie.length);
  const cases = [
    ['1 www.douyin.com   全量头', 'https://www.douyin.com' + pathAndQuery, full],
    ['2 www-hj.douyin.com 全量头', 'https://www-hj.douyin.com' + pathAndQuery, full],
    ['3 www-hj.douyin.com 全量头+同源Referer', 'https://www-hj.douyin.com' + pathAndQuery, { ...full, Referer: 'https://www-hj.douyin.com/' }],
    ['4 www-hj.douyin.com 只带必备头', 'https://www-hj.douyin.com' + pathAndQuery, {
      Accept: 'application/json, text/plain, */*',
      Referer: video,
      'User-Agent': full['User-Agent'] || full['user-agent'],
      Cookie: cookie,
    }],
  ];
  for (const [name, target, h] of cases) {
    const r = await call(target, h);
    console.log(`${name}\n   -> HTTP ${r.code} ${r.len}B ct=${r.ct} ce=${r.ce} hasDetail=${r.hasDetail}`);
    if (r.len < 1000) console.log('      head: ' + r.head);
    await new Promise((res) => setTimeout(res, 1200));
  }
  process.exit(0);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
