# YSP TV（ysp 分支）

**一个 APK，打开就能看央视频直播。** 不需要订阅地址、不需要电脑、不需要 Docker。

本分支是 [TiviMateLite](../..) 的 **YSP 专用版**：把央视频直播引擎直接编进 App，
启动时在本机 `127.0.0.1:8767` 起一个轻量网关，播放器从它取流。
`main` 分支仍是原来的 M3U 播放器；两者包名不同，可同时安装。

- 应用名：**YSP TV**
- 包名：`com.tivimatelite.ysp`
- 频道：**64 路**（央视 30 / 卫视 34），1080p 起播，可选高码率 / 4K
- 最低系统：Android 8.0（minSdk 26），目标 Android 9 机顶盒

---

## 功能

| 能力 | 说明 |
|---|---|
| 本机直播引擎 | 纯 Java 内核（`:yspcore`），启动时自动拉起，无需外部服务 |
| 64 路频道 | 央视、CGTN、卫视、CETV、国学等 |
| 1080p / 高码率 | 默认 1080p；Web 后台可开高码率，4K/8K 频道按需升级 |
| 快速换台 | 上下键换台，换台时左下角显示 **台名 + 序号** |
| 左右键重连 | 单线路场景下，左右键立即重连当前频道（不再切换线路） |
| 音频增益 | 1.00–3.00 倍，`volume + alimiter` 防削波，保存后自动重播生效 |
| Web 控制台 | 端口 **5221**，四个页签：状态 / 流程 / 设置 / 日志 |
| 处理流程可视化 | 请求 → 选源 → 上游取流 → 列表改写 → 输出 → 播放器，实时事件流 |
| 日志 | App 日志与引擎日志合并，支持级别筛选、增量刷新、下载 |
| 开机自启 | 沿用系统开关，自启时同时拉起引擎 |
| 局域网共享 | 可选：把 `8767` 暴露给 TiviMate / PotPlayer 等外部播放器 |

## 安装

从 GitHub Actions 的 `YSPTV-debug-apk` artifact 下载 `app-debug.apk`，
或在本仓库 Releases 中获取（如已发布）。

```bash
adb install -r app-debug.apk
```

桌面图标为「YSP TV」，深青色电视 + 播放键。

## 使用

| 按键 | 动作 |
|---|---|
| 上 / 下 | 换台（显示台名与序号） |
| 左 / 右 | 重连当前频道 |
| 数字键 | 数字选台（最长 4 位，900ms 确认） |
| MENU / INFO | 诊断浮层（引擎端口、控制台地址、当前线路） |

启动页会等待引擎就绪（通常 <2s，最长 12s）；失败时显示原因与「重试」，
并提示用浏览器打开 `设备IP:5221` 查看日志。

## Web 控制台（5221）

```
http://<设备IP>:5221/
```

| 页签 | 内容 |
|---|---|
| 状态 | 引擎状态、端口、频道数、运行时长、内存、缓存、当前频道、设备会话 |
| 流程 | 六段处理链路 + 最近事件流 + 当前频道 + 线路分布 |
| 设置 | 高码率开关、仅 4K、缓存、并发、换台防抖、端口、局域网共享、日志级别、音频增益、开机自启 |
| 日志 | App + 引擎合并日志，增量刷新，可筛选/清空/下载 |

> 标 `*` 的设置保存后会自动重启引擎。5221 绑定 `0.0.0.0`，仅建议在可信局域网使用。

### HTTP API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 引擎与当前频道状态 |
| GET | `/api/channels` | 64 路频道状态 |
| GET | `/api/events?after=<seq>` | 流程事件增量 |
| GET | `/api/logs?after=<seq>&level=` | 日志增量 |
| GET | `/diag` | 引擎逐频道诊断（代理 `8767/diag`） |
| GET | `/all.m3u` | 代理引擎订阅（给外部播放器） |
| POST | `/config` | 保存设置并重启 |
| POST | `/engine/restart` `/engine/stop` `/engine/start` | 引擎控制 |
| POST | `/audio/gain` `/boot/toggle` `/logs/clear` | 沿用 |

## 架构

```
YSP TV (com.tivimatelite.ysp)
├─ SplashActivity ──启动──> YspEngineService（前台服务）
│                              └─ YspGateway（:yspcore 纯 Java）
│                                   ├─ LocalServer :8767  /all.m3u  /<channel>.m3u8  /proxy.ts
│                                   ├─ ChannelEngine（64 路、JCE/BK/设备协议）
│                                   └─ TsSegmentCache / 日志总线
├─ MainActivity（IJKPlayer + SurfaceView）
│    └─ ChannelLoader ← http://127.0.0.1:8767/all.m3u（唯一频道来源）
└─ LocalAdminServer :5221（NanoHTTPD，控制台）
```

- `:yspcore`：44 个 Java 文件、约 1.4 万行，零 Android 依赖；自带 262 个单元测试。
- 数据目录：`Android/data/com.tivimatelite.ysp/files/ysp/`（设备身份、缓存状态）。
- 默认参数：缓存 32MB、单设备并发 4、换台防抖 10s、高码率**关闭**（低端盒子优先稳定）。

## 构建

仓库包含 GitHub Actions 工作流（`.github/workflows/android.yml`），
推送到 `ysp` 分支即构建 debug APK 并上传 artifact。

本地构建需要 JDK 17+ 与 Android SDK（`platforms;android-35`、`build-tools;35.0.0`）：

```bash
gradle assembleDebug --no-daemon
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

调试签名使用仓库内的 `app/ysp-debug.jks`（口令 `ysplive`），
保证 CI 多次构建可覆盖安装；正式发布请替换为自己的 keystore。

## 已知限制

1. **依赖上游接口**：本项目复刻央视频客户端协议，上游改签名/风控会导致暂时不可用；
   引擎内置 JCE 限流冷却与 SWR 旧列表兜底，但仍可能短暂黑屏。
2. **CDN 速度**：部分时段央视频 CDN 对单连接限速明显，引擎已改为 256KB 并行 Range 取流；
   网络极差时仍可能起播慢。
3. **x86 模拟器**：MuMu 等 x86 环境强制 ffmpeg 软解；真机（ARM）使用硬解。
4. **无 M3U 兜底**：本分支刻意不内置第三方源，引擎不可用时只显示诊断信息，不偷偷切源。
5. **回看**：引擎支持 53 路 7 天时移，App 暂未提供回看时间选择 UI。
6. **4K**：需要设备 HEVC 硬解与 ≥50Mbps 带宽；低端盒子建议保持高码率关闭。

## 来源与声明

- 内核移植自 [`ysp-live-android`](https://github.com/2909272751/ysp-live-android) 的 `core`，
  其原创代码以 MIT 发布（见 `yspcore/LICENSE`）；协议常量与频道数据的权利归原作者。
- 本项目与央视、央视频无任何关联，不存储、不分发视频内容，仅在本机转发公开接口。
- 仅供技术学习与个人研究，请勿用于商业用途或公开传播；使用风险由使用者自行承担。
- `main` 分支与 `ysp` 分支互不影响；本分支的改动不会自动合并回 `main`。
