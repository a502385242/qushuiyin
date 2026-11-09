// 在「安卓 UA + 安卓客户端提示」这个自洽环境里，看清页面到底从哪拿到数据、
// 拿到的播放地址带不带水印（playwm / logo_name 是水印版的标志）。
const http = require('http');

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

const MOBILE_UA =
  'Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/143.0.7499.192 Mobile Safari/537.36';

const ANDROID_META = {
  brands: [
    { brand: 'Not)A;Brand', version: '24' },
    { brand: 'Chromium', version: '143' },
    { brand: 'Google Chrome', version: '143' },
  ],
  fullVersionList: [
    { brand: 'Not)A;Brand', version: '24.0.0.0' },
    { brand: 'Chromium', version: '143.0.7499.192' },
    { brand: 'Google Chrome', version: '143.0.7499.192' },
  ],
  fullVersion: '143.0.7499.192',
  platform: 'Android',
  platformVersion: '13.0.0',
  architecture: '',
  model: 'Pixel 7',
  mobile: true,
};

(async () => {
  const video = process.argv[2] || 'https://www.douyin.com/video/7699062672146337075';
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

  const reqs = [];
  const detail = [];
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m.result);
      pending.delete(m.id);
      return;
    }
    if (m.method === 'Network.requestWillBeSent') {
      const u = m.params.request.url;
      if (u.indexOf('douyin.com') > 0 && u.indexOf('.js') < 0 && u.indexOf('.css') < 0) reqs.push(u.split('?')[0]);
      if (u.indexOf('/aweme/detail') >= 0) detail.push({ id: m.params.requestId, url: u });
    }
  };
  await new Promise((r) => (ws.onopen = r));
  await send('Page.enable');
  await send('Network.enable');
  await send('Emulation.setUserAgentOverride', {
    userAgent: MOBILE_UA,
    acceptLanguage: 'zh-CN,zh;q=0.9',
    platform: 'Linux armv8l',
    userAgentMetadata: ANDROID_META,
  });
  await send('Emulation.setDeviceMetricsOverride', { width: 360, height: 640, deviceScaleFactor: 2, mobile: true });
  await send('Page.navigate', { url: video });
  await new Promise((r) => setTimeout(r, 14000));

  const probe = await send('Runtime.evaluate', {
    expression: `(function(){
      var out = { title: document.title || '', url: location.href,
                  router: typeof window._ROUTER_DATA, v: document.querySelectorAll('video').length };
      var v = document.querySelector('video');
      out.src = v ? (v.currentSrc || v.src || '') : '';
      var html = document.documentElement ? document.documentElement.outerHTML : '';
      out.html = html.length;
      out.hasPlayAddr = html.indexOf('play_addr') >= 0;
      out.hasPlaywm = html.indexOf('playwm') >= 0;
      out.hasLogoName = html.indexOf('logo_name') >= 0;
      out.hasRouterData = html.indexOf('_ROUTER_DATA') >= 0;
      return JSON.stringify(out);
    })()`,
    returnByValue: true,
  });
  console.log('页面: ' + probe.result.value);
  console.log('\n发出的 douyin 请求:');
  [...new Set(reqs)].slice(0, 25).forEach((u) => console.log('  ' + u));

  for (const d of detail.slice(0, 3)) {
    const body = await send('Network.getResponseBody', { requestId: d.id });
    if (!body || !body.body) {
      console.log('\ndetail 响应体读不到');
      continue;
    }
    const s = body.base64Encoded ? Buffer.from(body.body, 'base64').toString('utf8') : body.body;
    console.log(`\ndetail ${s.length}B hasDetail=${s.indexOf('aweme_detail') >= 0}`);
    const urls = s.match(/https:\/\/[^"']*?(play|video)[^"']*?(\.mp4|\/video\/tos)[^"']*/g) || [];
    console.log('   播放地址样本:');
    [...new Set(urls)].slice(0, 6).forEach((u) => console.log('     ' + u.slice(0, 150)));
    console.log('   含 playwm=' + (s.indexOf('playwm') >= 0) + ' 含 logo_name=' + (s.indexOf('logo_name') >= 0));
  }
  ws.close();
  await closeTab(tab.id);
  process.exit(0);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
