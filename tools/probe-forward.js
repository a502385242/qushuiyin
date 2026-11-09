// 验证「原样转发」可行性：把页面自己发出的 detail 请求，用页面自己的 Cookie + UA
// 在页面之外重新发一次，看服务端是否照样返回 aweme_detail。
// 这一步等价于 App 里 shouldInterceptRequest 转发请求的做法。
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

const target = process.argv[2];

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
      if (u.indexOf('/aweme/detail') >= 0) {
        detail = { url: u, headers: m.params.request.headers };
      }
    }
  };
  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Network.enable');
  await send('Page.navigate', { url: target });
  await new Promise((r) => setTimeout(r, 18000));

  if (!detail) {
    console.log('NO_DETAIL_REQ');
    process.exit(1);
  }

  const ck = await send('Network.getAllCookies');
  const cookie = (ck.cookies || [])
    .filter((c) => /(^|\.)douyin\.com$/.test(c.domain))
    .map((c) => c.name + '=' + c.value)
    .join('; ');

  ws.close();

  const u = new URL(detail.url);
  const headers = {
    'User-Agent': detail.headers['User-Agent'],
    Accept: 'application/json, text/plain, */*',
    Referer: detail.headers['Referer'],
    'Accept-Language': 'zh-CN,zh;q=0.9',
    Cookie: cookie,
    uifid: detail.headers['uifid'],
  };

  await new Promise((resolve) => {
    const req = https.request(
      { hostname: u.hostname, path: u.pathname + u.search, method: 'GET', headers },
      (resp) => {
        let b = '';
        resp.setEncoding('utf8');
        resp.on('data', (d) => (b += d));
        resp.on('end', () => {
          let d = null;
          try {
            d = JSON.parse(b);
          } catch (e) {}
          console.log('HTTP=' + resp.statusCode + ' LEN=' + b.length);
          if (!d) {
            console.log('BODY_HEAD=' + b.slice(0, 200));
          } else {
            const v = d.aweme_detail && d.aweme_detail.video;
            console.log(
              JSON.stringify({
                status_code: d.status_code,
                hasDetail: !!d.aweme_detail,
                bitRateCount: v && v.bit_rate ? v.bit_rate.length : 0,
                desc: d.aweme_detail ? String(d.aweme_detail.desc || '').slice(0, 40) : null,
              })
            );
          }
          resolve();
        });
      }
    );
    req.on('error', (e) => {
      console.log('ERR ' + e.message);
      resolve();
    });
    req.end();
  });
  process.exit(0);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
