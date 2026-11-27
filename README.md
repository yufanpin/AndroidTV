# MIGU TV（migu 分支）

**一个 APK，打开就能看咪咕直播。** 不需要订阅地址、不需要电脑、不需要 Docker。

本分支把咪咕直播协议内核编进 App，启动时在本机 `127.0.0.1:8767` 起一个轻量网关，
播放器从它取流；同时提供 **Web 控制台**（端口 5221）。播放器为 **IJKPlayer**，
内核为纯 Java 实现的咪咕 develop202 协议（移植自 `migu_lnss.php`）。

- 应用名：**MIGU TV**
- 包名：`com.tivimatelite.migu`
- 频道：**84 路**（央视 26 / 卫视 21 / 地方 31 / 轮播 6）
- 最低系统：Android 8.0（minSdk 26），目标 Android 9 机顶盒 / MuMu 模拟器

---

## 内核如何运行

内核（`MiguGateway`）由前台 Service（`YspEngineService`）持有，**App 启动后常驻运行**：

```
App 启动
  └─ SplashActivity → YspEngineService（前台服务，常驻）
       └─ YspEngine → MiguGateway（咪咕内核）
            ├─ ServerSocket 监听 127.0.0.1:8767
            ├─ /all.m3u        84 路频道清单（播放行自指本机 /?id=N）
            ├─ /?id=N          咪咕签名 → playurl → 302 跟踪 → m3u8
            └─ 流地址缓存 900s（命中不再打上游）
```

**关键行为**：
1. 内核随 App 启动常驻，直到 App 退出或用户在 Web 后台点「停止引擎」；
2. 84 路频道 m3u8 地址按 **900 秒**缓存，播放器 6 秒级清单轮询只打上游一次；
3. 分片 **直连咪咕 CDN**（实测免 UA / Referer），不经本机代理；
4. 换台时内核只解析新频道 playurl 签名并跟踪 302，命中缓存则直接返回。

---

## 功能

| 能力 | 说明 |
|---|---|
| 本机直播内核 | 纯 Java `:yspcore/migu`，咪咕 develop202 签名算法，无外部服务 |
| 84 路频道 | 央视、卫视、地方、轮播 |
| 播放器 | IJKPlayer（k0.8.9 预编译 AAR，ARM 硬解 / x86 软解） |
| 快速换台 | 错误抑制 + 流地址缓存 + 播放器复用；不误报「信号中断」 |
| 按键操作 | 上下键换台、左右键重连、数字键选台、MENU/INFO 诊断浮层 |
| Web 控制台 | 端口 **5221**，四页签：状态 / 流程 / 设置 / 日志 + JSON API |
| 开机自启 / 局域网共享 | 系统开关自启；可选把 8767 暴露给外部播放器 |

---

## 安装

CI 推送 `migu` 分支会构建 debug APK，从 GitHub Actions 的 `YSPTV-debug-apk` artifact 下载：

```bash
adb install -r app-debug.apk
```

桌面图标为「MIGU TV」。包名 `com.tivimatelite.migu`，可与 main / ysp 分支共存。

---

## 使用

| 按键 | 动作 |
|---|---|
| 上 / 下 | 换台（显示台名与序号） |
| 左 / 右 | 重连当前频道 |
| 数字键 | 数字选台（1–84，900ms 确认） |
| MENU / INFO | 诊断浮层 |

## Web 控制台（5221）

```
http://<设备IP>:5221/
```

四页签：状态 / 流程 / 设置 / 日志。主要 API：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 内核与当前频道状态 |
| GET | `/api/channels` | 84 路频道状态 |
| GET | `/api/events?after=<seq>` | 流程事件增量 |
| GET | `/api/logs?after=<seq>&level=` | 日志增量 |
| GET | `/all.m3u` `/diag` | 引擎订阅 / 诊断 |
| POST | `/config` `/engine/restart` `/engine/stop` `/engine/start` | 配置与内核控制 |

---

## 架构

```
MIGU TV (com.tivimatelite.migu)
├─ SplashActivity ──启动──> YspEngineService（前台服务）
│                              └─ MiguGateway（:yspcore/migu 纯 Java）
│                                   ├─ MiguChannel  84 路频道表
│                                   ├─ MiguApi      develop202 签名 + ddCalcu + 302 跟踪
│                                   └─ ServerSocket :8767  /all.m3u  /?id=N  /health  /diag
├─ MainActivity（IJKPlayer + SurfaceView）
│    └─ ChannelLoader ← http://127.0.0.1:8767/all.m3u（唯一频道来源）
│       └─ ChannelSwitcher（换台防抖 / 单源重试 / 缓冲故障转移）
└─ LocalAdminServer :5221（NanoHTTPD，Web 控制台）
```

### 内核协议实现

`MiguApi` 逐行移植自 `migu_lnss.php`（develop202 算法）：
- **签名**：「毫秒时间戳 + contId + 版本前缀」双 md5 + 固定盐 + 随机盐前 4 位；
- **ddCalcu**：对 `puData` 逐字符「尾 + 头」交叉拼接，按日期与频道 ID 插字；
- **302 跟踪**：最多 6 跳跟随至最终 CDN 清单地址；
- **清单改写**：分片与 KEY/MAP 的 URI 转绝对地址（分片直连 CDN）；
- **风控容错**：playurl 偶发返回 HTML 风控页时降级读取 + 2s 节流 + 失败重试。

---

## 换台速度与优化

真机实测（MuMu / Android 12 / x86_64）：换台 **5–8 秒**出画，
无「信号中断」误报、无 5 秒重试。

**为什么不是「0 等待」**：换台必须向咪咕 API 请求新频道签名地址（签名 + 302），
并为播放器重新初始化解码器，这两步有固有网络与解码开销，无法真正零延迟。

**已做优化**：
1. 流地址 900s 缓存 —— 回访频道不重复解析；
2. 换台错误抑制窗口 —— IJK `reset()` 旧源的伪 `onError` 不再误报「信号中断」，
   避免 5 秒延迟重试（此前换台卡顿主因）；
3. playurl 全局 2s 节流 + 失败重试 —— 避免密集换台触发上游风控 HTML；
4. 播放器复用同一 IJK 实例（reset + setDataSource），不重建 native 层。

**进一步优化方向**（如需接近秒开）：启动后后台预取相邻频道 m3u8 到缓存，
换台时直接命中缓存省去签名与 302 步骤；代价是启动时增加上游请求。

---

## 构建

推送到 `migu` 分支触发 `.github/workflows/android.yml`，构建 debug APK + 单测 + 上传 artifact。

本地构建需 JDK 17+ 与 Android SDK（`platforms;android-35`）：

```bash
gradle assembleDebug --no-daemon
```

---

## 已知限制

1. 依赖咪咕上游接口，签名算法或风控变化会导致解析失败；内核有节流与重试兜底。
2. x86 模拟器走软解，性能低于 ARM 真机。
3. Web 后台 5221 绑定 `0.0.0.0`，仅建议在可信局域网使用。
4. 本分支基于 IJKPlayer；ExoPlayer 方案见 `ysp` 分支（历史版本）。

---

## 来源与声明

- 咪咕协议算法参考 `migu_lnss.php`（LNSS 自写源契约 v1），移植为 Java 实现。
- 项目框架继承 TiviMateLite，内核接口 `Gateway` 保持与上游 core 兼容。
- 本项目与咪咕、央视无关联，不存储、不分发视频内容，仅在本机转发公开接口。
- 仅供技术学习与个人研究，请勿用于商业用途或公开传播。
