# 去水印 · QuShuiYin

纯本地的抖音视频/图文下载工具。没有服务器、不需要账号、不申请存储权限。

**核心思路**：无水印不是图像处理，而是**请抖音给你那条已经去掉 logo 的视频流**。
同一个作品在 CDN 上同时挂着带水印和不带水印的版本，分享面板「保存本地」拿的是带水印那条；
本工具请求分享页，从页面内嵌的 `_ROUTER_DATA` 里取出不带水印的地址，按字节原样写入相册。
不做解码、不做转码，因此画质等于平台能给的上限。

---

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 工程骨架、分享入口、链接识别 | ✅ |
| P2 | 解析核心（候选路径 / HTML 提取 / 错误分类） | ✅ 单测覆盖 |
| P3 | 下载器 + MediaStore 写入相册 | ⏳ 未开始 |
| P4 | 失败驱动的在线修复（拉取 → 验签 → 落盘 → 重试） | ✅ 代码完成 |
| P5 | 历史记录、Release 签名 | ⏳ |

**未验证的部分**：解析链路还没对着真实线上链接跑过（默认规则是按已知结构写的）。
第一次联调需要用真实分享文案校准 `config/manifest.json` 里的 `itemPaths`。

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
├── parser/     LinkParser 链接抽取 · JsonPath 候选路径 · HtmlExtractor 抠 JSON
│               DouyinParser 主流程 · ParseRules 规则结构 · ParseError 错误分类
├── repair/     MirrorClient 多镜像竞速 · RulesGate 采用判定（纯 JVM，可单测）
│               RulesStore 持久化 · RepairManager 编排 · Verifier ECDSA 验签
├── ui/         HomeScreen · HomeViewModel · Theme
├── model/      Aweme
└── core/       Http
config/         manifest.json + manifest.json.sig  ← 推到仓库的就是这两份
keys/           私钥（gitignore，只在本机）与公钥
tools/          manifest_tool.py 签名工具 · setup-toolchain.sh 环境安装
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

jsDelivr 对 `@main` 有约 12 小时的边缘缓存，所以客户端每次请求都会带上**按分钟分桶**的
`?v=<bucket>`（见 `MirrorClient.freshUrl`）。同一分钟内的用户共享同一份缓存，
既保证你推完规则几分钟内就生效，又不会让每个请求都回源。

推完规则想确认线上可用了：

```bash
curl -sL "https://fastly.jsdelivr.net/gh/a502385242/qushuiyin@main/config/manifest.json" | head -3
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
