// 逐个剔除 Cookie，找出「缺了它就必然回 0 字节」的那一个。
// 思路：PC 上拿到一份能成功的 Cookie（2660B），手机 WebView 只有 1453B，
// 差异项就是嫌疑人。留一法（leave-one-out）能在 ~20 次请求内定位。
const http = require('http');
const https = require('https');

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
      if (u.indexOf('/aweme/detail') >= 0 && !detail) detail = { url: u, headers: m.params.request.headers };
    }
  };
  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Network.enable');
  await send('Page.navigate', { url: videoUrl });
  await new Promise((r) => setTimeout(r, 15000));
  const ck = await send('Network.getAllCookies');
  ws.close();
  await closeTab(tab.id);
  return { detail, cookies: ck.cookies || [] };
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
        resp.on('end', () => resolve({ code: resp.statusCode, len: n, hasDetail: head.indexOf('aweme_detail') >= 0 }));
      }
    );
    req.on('error', (e) => resolve({ code: -1, len: 0, hasDetail: false }));
    req.end();
  });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const video = process.argv[2] || 'https://www.douyin.com/video/7699062672146337075';
  const { detail, cookies } = await grab(video);
  if (!detail) {
    console.log('NO_DETAIL_REQ');
    process.exit(1);
  }
  const u = new URL(detail.url);
  const list = cookies.filter((c) => /(^|\.)douyin\.com$/.test(c.domain));
  const ua = detail.headers['User-Agent'] || detail.headers['user-agent'];
  const mk = (pairs) => ({
    Accept: 'application/json, text/plain, */*',
    Referer: video,
    'User-Agent': ua,
    Cookie: pairs.map((c) => c.name + '=' + c.value).join('; '),
  });

  console.log('Cookie 共 ' + list.length + ' 项, ' + mk(list).Cookie.length + ' 字节');
  console.log('名单: ' + list.map((c) => c.name + '(' + (c.name.length + c.value.length + 1) + 'B)').join(', '));

  const base = await call('https://www.douyin.com' + u.pathname + u.search, mk(list));
  console.log(`基线 全量 -> HTTP ${base.code} ${base.len}B hasDetail=${base.hasDetail}`);
  if (!base.hasDetail) process.exit(0);

  const dropped = [];
  for (const c of list) {
    const subset = list.filter((x) => x !== c);
    const r = await call('https://www.douyin.com' + u.pathname + u.search, mk(subset));
    const ok = r.hasDetail;
    if (!ok) dropped.push(c.name);
    console.log(`  去掉 ${c.name.padEnd(24)} -> ${r.code} ${String(r.len).padStart(6)}B ${ok ? 'ok' : '❌ 挂了'}`);
    await sleep(900);
  }
  console.log('\n必需项: ' + (dropped.length ? dropped.join(', ') : '（没有单项是必需的）'));
  process.exit(0);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
