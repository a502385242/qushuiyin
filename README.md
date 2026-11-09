# 去水印 · QuShuiYin

纯本地的抖音视频/图文下载工具。没有服务器、不需要账号、不申请存储权限。

**核心思路**：无水印不是图像处理，而是**请抖音给你那条已经去掉 logo 的视频流**。
同一个作品在 CDN 上同时挂着带水印和不带水印的版本：分享面板「保存本地」拿的是带水印那条
（`download_addr`，带 `logo_name` 参数）；`video.bit_rate[]` 里每一档的 `play_addr` 才是不带水印的。
本工具取 `bit_rate` 最高档的 `play_addr`，按字节原样写入相册。不做解码、不做转码，
因此画质等于平台能给的上限（实测这条作品最高档是 1080×1920 / 1.38 Mbps / 10.1 MB）。

## 直接装到手机

不用编译，点开下载即可（需要 Android 8.0 及以上）：

**https://github.com/a502385242/qushuiyin/releases/latest**

安装时系统会提示「未知来源应用」，允许浏览器/文件管理器安装就行。APK 用固定密钥签名，
以后发的新版本可以直接覆盖安装，不用先卸载。

自己发新版只要一条命令（自动升 versionCode、编译、验签、传 Release）：

```bash
bash tools/release.sh 0.3.0 "一句话说明这版改了什么"
```


## ⚠️ 2026-11 抖音改版：必须走 WebView

如果你看到别的教程说「GET 分享页，抠 `_ROUTER_DATA`」，那是**过时**的。实测结果：

| 取数方式 | 结果 |
|---|---|
| `GET https://www.iesdouyin.com/share/video/{id}/`（带浏览器 UA） | ❌ 200，但页面只剩 1.4 KB 空壳，`_ROUTER_DATA` 里没有任何作品数据 |
| 移动 UA（iPhone / Android WebView 默认）打开 | ❌ 跳 `m.douyin.com`，只给 **带水印的 720p**（`playwm` + `logo_name`） |
| `aweme/v1/web/aweme/detail` 直接请求 | ❌ 403 `Blocked by ArgusSecurityPlugin Uifid Not Found` |
| **桌面 UA 的真浏览器打开 `douyin.com/video/{id}`** | ✅ 页面自己带着 `a_bogus` 请求 `aweme/detail`，返回 **12 档清晰度** |

所以取数方式改成了：**离屏 WebView + 桌面 UA + 在网络层把 `aweme/detail` 请求转发一次拿回响应**。
让抖音自己的 JS 去算签名，我们只读结果——这样他们改签名算法时，跟着改的是他们，不是我们。

这一条决定了两个不能动的实现细节：

1. **UA 必须是桌面版**。移动 UA 下拿到的只有带水印的 720p，等于这个 App 白做。
2. **必须跑 WebView**，不能用 OkHttp 模拟。`a_bogus` / `uifid` / `x-secsdk-web-signature`
   三个参数都是页面 JS 现场算的，复刻等于长期做逆向。

代价是解析要 3~8 秒（有一个「正在通过网页加载作品」的进度提示）。

### 0.3.0 修的是「钩子从来没生效」

0.2.0 用 JS 钩子在 `onPageStarted` 里 `evaluateJavascript` 注入，看着能跑，
实际**一次都没生效**：那一刻新文档还没创建，脚本执行在旧文档的上下文里，页面一换就没了。
表现就是不管换哪条链接都提示「页面结构有变化」。

0.3.0 不再依赖注入时机，改成旁观网络请求：

```
页面 JS 算出 a_bogus → 发出 GET /aweme/v1/web/aweme/detail/...
        ↓
WebViewClient.shouldInterceptRequest 拿到完整 URL + 请求头（含 cookie/uifid）
        ↓
用 OkHttp 原样转发一次（不带 Accept-Encoding，由 OkHttp 解 gzip）
        ↓
响应体同时交给上层解析 + 原样返回给 WebView，页面无感
```

这个做法还有个额外好处：以前只能靠「猜」判断失败原因，现在能拿到真实响应，
403 / 空体 / `status_code != 0` 都分得开。接口路径写进了规则文件的 `detailApiPaths`，
抖音换路径时推一份新规则就能救，不用发版。

### 0.3.3 修的是「域名换成了 www-hj，ttwid 跟不过去」

真机上详情接口实际走的是 `www-hj.douyin.com`，不是 `www.douyin.com`。
而 `ttwid` 是 **host-only cookie**，只对种它的域名有效；页面请求跨到 `www-hj` 时
浏览器不会带上它。抖音对缺 `ttwid` 的请求**不报错，只回 `200 + 0 字节`**。

这一点是用留一法测出来的：拿一份能成功的 35 项 cookie，逐项删掉重发同一个请求，
只有删掉 `ttwid` 会变成 0 字节，删掉其余 34 项都是正常的 74 KB
（`tools/probe-cookie.js`）。同一份 query 打 `www` 和打 `www-hj` 结果一致，
说明签名参数与域名无关（`tools/probe-host.js`）——所以修法是把 API 请求
canonical 化回 `www.douyin.com`，并且优先读该域名下的 cookie。
cookie 里彻底没有 `ttwid` 时，会先请求一次首页把 `Set-Cookie` 补回来。

这个坑有个很迷惑的表象：`HTTP 200`、`content-type: application/json`、状态码一切正常，
只是 body 长度为 0。看到「200 + 空体」先查 cookie，不要怀疑 UA 或签名。



---

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 工程骨架、分享入口、链接识别 | ✅ |
| P2 | 解析核心（WebView 取数 + 候选路径 + 错误分类） | ✅ 单测 + 真浏览器验证 |
| P3 | 下载器 + MediaStore 写入相册 | ⏳ 未开始 |
| P4 | 失败驱动的在线修复（拉取 → 验签 → 落盘 → 重试） | ✅ 代码完成 |
| P5 | 历史记录、Release 签名 | ⏳ |

**验证到什么程度**：
- 真实分享链接跑通了全链路（短链 → 作品 ID → 桌面页 → 详情 JSON → 12 档清晰度）
- 最高档 1080×1920 流下载后抽帧肉眼确认**无水印**，字节数与接口给的 `data_size` 完全一致
- 「转发请求」这条路在真 Chrome 里单独验过：把页面自己发出的 detail 请求连同 Cookie
  在页面之外重发一次，同样返回 200 + 12 档（`tools/probe-forward.js`）
- 还没在**真机**上跑过（本机没有 adb），WebView 环节是唯一剩下的不确定项

---

## 构建

工程**必须放在纯 ASCII 路径**下（例如 `D:/qushuiyin`）。中文路径会让 Gradle 的单元测试工作进程
拿到乱码 classpath，表现为所有测试 `ClassNotFoundException`，没有任何配置项能绕过。

```bash
export JAVA_HOME=/path/to/jdk17          # 需要 JDK 17，JDK 8 编不了 AGP 8.x
export ANDROID_HOME=/path/to/android-sdk  # 或写进 local.properties 的 sdk.dir

./gradlew :app:assembleDebug      # 产物：app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest  # 解析层 + 修复判定层单测
```

`local.properties`（不进版本库）：

```properties
sdk.dir=D:/devtools/android-sdk
# 可选覆盖项
# repair.mirrors=https://raw.githubusercontent.com/a502385242/qushuiyin/main/config/
# repair.pubkey=<Base64>
```

公钥在 `build.gradle.kts` 里有内置默认值，正常不需要在这里写。

---

## 目录

```
app/src/main/java/com/qushuiyin/app/
├── parser/     WebDetailFetcher 离屏 WebView 取数（shouldInterceptRequest 转发请求）
│               DetailApiMatcher 接口匹配（纯 JVM，可单测）
│               DetailSource 取数接口 · DouyinParser 解析主流程
│               LinkParser 链接抽取 · JsonPath 候选路径 · HtmlExtractor 抠 JSON
│               ParseRules 规则结构 · ParseError 错误分类
├── repair/     MirrorClient 多镜像竞速 · RulesGate 采用判定（纯 JVM，可单测）
│               RulesStore 持久化 · RepairManager 编排 · Verifier ECDSA 验签
├── ui/         HomeScreen · HomeViewModel · Theme
├── model/      Aweme
└── core/       Http
config/         manifest.json + manifest.json.sig  ← 推到仓库的就是这两份
keys/           私钥（gitignore，只在本机）与公钥
tools/          manifest_tool.py 签名工具 · setup-toolchain.sh 环境安装
                probe-*.js 真浏览器探测脚本（验证取数链路用，不参与打包）
```

---

## 在线修复

抖音改版导致解析失败时，App 会明确报「页面结构有变化」，并提供「在线修复」按钮。
点击后：并发拉取三个镜像 → 取最先返回的一份 → **验签** → 校验 `minAppVersion` 与规则版本
→ 原子写入 DataStore → 用新规则重试当前链接。

只有这一种失败会显示修复按钮。断网、链接无效、作品已删都不会，避免把修复按钮变成摆设。

### 发一份新规则的完整流程

```bash
# 1. 改规则：把 schemaVersion 加 1（必须大于上一次，否则客户端判定为「无更新」）
vim config/manifest.json

# 2. 重新签名（签名对象是文件原始字节，改一个空格都要重签）
python tools/manifest_tool.py sign config/manifest.json

# 3. 本地自检
python tools/manifest_tool.py verify config/manifest.json

# 4. 提交并推送
git add config/manifest.json config/manifest.json.sig
git commit -m "rules: v3 适配新版字段"
git push
```

用户下次点修复即可恢复；如果之前解析成功过，App 会静默预取（6 小时内最多一次），
真正遇到失效时规则往往已经是新的。

**镜像顺序是按国内实测可达性排的**，不是按「谁的官方」排：

| 顺序 | 地址 | 实测 |
|---|---|---|
| 1 | `fastly.jsdelivr.net` | 0.7~1.4s，稳定 |
| 2 | `cdn.jsdelivr.net` | 同源备用 |
| 3 | `raw.githubusercontent.com` | 内容最权威、更新最即时，但国内常卡在 DNS/TLS 阶段，实测偶发连不上 |

### 缓存陷阱：为什么不能「谁先回来用谁」

jsDelivr 对 `@main` 有约 12 小时边缘缓存，而且**不认 query 参数**——加 `?v=123` 一样返回旧内容
（purge API 报告成功也未必立刻生效，实测 purge 后仍然拿到旧版）。

于是出现过一个真实的 bug：镜像竞速时 CDN 比 raw 先返回，**旧规则永远赢过新规则**，
你推了 v3，用户点修复拿到的还是 v2，还提示「已是最新」。

现在的做法：`MirrorClient.fetchAll()` 把窗口内所有响应都收上来，
`RulesGate.choose()` **取版本号最大的那份**。旧缓存于是变成无害噪声，而不是决策依据。
回归测试用一份真发过的 v2（签名有效、内容过时）验证这个行为。

推完规则想确认线上生效（**直接看 raw，别信 CDN 的即时性**）：

```bash
curl -sL "https://raw.githubusercontent.com/a502385242/qushuiyin/main/config/manifest.json" | head -3
```

### 密钥

`keys/private.pem` 是这份方案唯一的信任根。**它丢了，老版本 App 就再也无法信任新规则**，
只能发版换公钥。当前它只在开发机上，`.gitignore` 已排除。

将来接 CI 时用 Secret 注入，绝不要提交。

---

## 边界

- 热修复只能救「字段位移、换包装键、域名变化」这类变更。如果抖音改成异步加密接口，
  或者加了新的签名校验，规则再多也修不了，只能发版。App 里要留「检查新版本」的出口。
- 远程规则能改的只有 UA、域名、取值路径。**绝不允许规则指定「去哪下载脚本并执行」**，
  那等于给自己装一个远程代码执行后门。
- 用途限定在个人收藏与学习研究。不要做批量爬取、不要二次分发。这类工具也上不了国内应用商店。
