# TiviMateLite

Android TV IPTV 播放器，面向晶晨 S905L3（2GB RAM、Android 9）等低端机顶盒。
像素级致敬 TiviMate 的操作体验，目标常驻内存 ≤60MB、秒切台、不掉帧。

> **播放内核：IJKPlayer**（debugly/ijkplayer k0.8.9 预编译 AAR，OpenGL ES 渲染后端，
> 自带 `armeabi-v7a` / `arm64-v8a` 原生库）。不依赖 Vulkan，可在无 Vulkan 驱动的设备
> （含 S905L3 机顶盒、MuMu 模拟器）上正常出画。

---

## 功能特性

| 特性 | 说明 |
|---|---|
| 左右键切源 | 上下键换台；左右键在当前频道的多条线路间循环切换（300ms 防抖，悬浮显示"线路 x/N"） |
| 智能线路记忆 | 播放成功记录域名，切台 / 故障转移时优先选记忆中可播放的源，失败自动移除 |
| 多源自动故障转移 | 当前源出错 / 加载超时 / 缓冲超时 / 播放结束 → 依次尝试该频道其它源；单源频道退化为重试 |
| 加载超时保护 | 15s 内未 READY → 自动切下一源 |
| 缓冲超时 | BUFFERING 且播放中持续 35s → 切下一源 |
| Ready-stall 监控 | READY 但位置与缓冲均停滞 300s → 切源（60s 预热 + 300s 恢复冷却） |
| 上次播放记忆 | 重启后恢复上次频道及其线路 |
| Web 后台 | NanoHTTPD（端口 5220）：切换内置/自定义源、增删源、粘贴源、开机自启开关、实时日志 |
| 文件日志持久化 | 写入 `cacheDir/tivimate_diag.txt`（512KB 上限），含未捕获异常处理器，ADB 断开后仍可拉取 |
| 内存日志 | `AppLogStore` 环形缓冲 500 行，供 Web 后台实时查看 |
| 网速悬浮层 | 每秒刷新实时下行速率 |
| 心跳日志 | 每 10s 写一条 `HEARTBEAT`，用于判定进程存活 |
| 开机自启 | `BootReceiver` + Web 后台开关 |

---

## 快速开始

### 使用 GitHub Actions 构建

1. 把项目推送到 GitHub 仓库。
2. 打开仓库 `Actions` → 自动触发 `Android CI`。
3. 等待任务完成后下载 `TiviMateLite-debug-apk` artifact。
4. 解压得到 `app-debug.apk`。

CI 配置（`.github/workflows/android.yml`）：JDK 17 + Gradle 8.10.2 + Android SDK platform 35 / build-tools 35.0.0。
推送 `main` / `master` 或手动触发；构建 debug APK 并运行单元测试。

本地构建（需 JDK 17）：

```bash
gradle assembleDebug --no-daemon --stacktrace
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 准备直播源

默认从 asset 读取：

```text
app/src/main/assets/channels.m3u
```

也可改为远程源（`app/build.gradle.kts`）：

```kotlin
buildConfigField("String", "PLAYLIST_URL", "\"http://your-server/channels.m3u\"")
```

启动时的源加载优先级（见 `RemotePlaylistRepository.buildCandidateUrls()`）：

1. `BuildConfig.PLAYLIST_URL`（若已配置）
2. 本机 Web 后台候选：`http://<ip>:5220/channels.m3u`、`http://<ip>:5220/playlist`
   （随后回退 `127.0.0.1` 的同一路径）——后台会按"内置 / 自定义源"模式返回生效播放列表
3. asset 兜底：`app/src/main/assets/channels.m3u`

示例：

```m3u
#EXTM3U
#EXTINF:-1 tvg-name="CCTV 1" tvg-logo="https://example.com/logo.png" group-title="Test",CCTV 1
http://example.com/live/cctv1.m3u8
```

> 同名频道会合并为一个"频道组"，其下多个 URL 即为可切换的"线路"。

### 安装到机顶盒

```bash
adb connect BOX_IP:5555
adb install -r app-debug.apk
adb shell monkey -p com.tivimatelite 1
```

---

## 按键映射

| 按键 | 动作 |
|---|---|
| 上 / 下 | 换台（300ms 防抖） |
| 左 / 右 | 切换当前频道线路（300ms 防抖，悬浮显示"线路 x/N"） |
| 数字键 0-9 | 数字选台（最长 4 位，900ms 无输入后确认） |
| MENU / SETTINGS / INFO | 切换后台诊断浮层（显示 IP / 后台地址 / 生效源，4s 自动隐藏） |
| OK / ENTER | 已消费（无动作） |

---

## 核心架构

```
MainActivity（唯一 Activity）
  ├─ dispatchKeyEvent：上下换台、左右切源、数字选台、MENU 切诊断浮层
  ├─ SurfaceHolder.Callback → PlayerManager.setSurface(holder.surface)
  │     （直接绑定 holder.surface，绕过 Amlogic phantom-surface bug）
  ├─ PlayerManager.Events：onError→移除记忆域名并切下一源；
  │     STATE_READY→取消超时/启动 ready-stall 监控/记录可播放域名
  ├─ ChannelLoader：拉取→分组→按上次播放恢复选中
  ├─ ChannelSwitcher：选源 + 多源故障转移
  ├─ ReadyStallWatch：ready-stall / 网速 / 心跳 / 播放列表变更监听
  └─ InputHandler：数字选台缓冲与防抖

PlayerManager（object，全局单例 IjkMediaPlayer）
  ├─ 懒构造 + 原生库加载失败永久置位（避免重试风暴）
  ├─ play()：reset → applyOptions → setSurface → setDataSource → prepareAsync
  └─ 状态机 IDLE / BUFFERING / READY / ENDED（由 IjkMediaPlayer 回调驱动）

ChannelSwitcher
  ├─ 频道切换（上下）与线路切换（左右）各自 300ms 防抖
  ├─ 智能选源（记忆域名优先）
  ├─ 15s 加载超时 / 35s 缓冲超时 / 单源重试（最多 3 次，间隔递增 5-15s）
  └─ playNextSourceForCurrentChannel()：优先切到记忆中的好域名

PlayableHostStore      （SharedPreferences `playable_hosts`）
  └─ addHost / removeHost / getHosts / extractHost（记录可播放域名）

PlaybackHistoryStore   （SharedPreferences `playback_history`）
  └─ 记录上次频道名 / 线路索引 / URL，用于重启恢复

PlaylistStore          （SharedPreferences `playlist_admin`）
  └─ 内置/自定义源模式、自定义源增删选、粘贴源内容、配置指纹

ReadyStallWatch
  ├─ ready-stall 检测（READY 停滞 → 切源）
  ├─ 播放列表变更监听（配置指纹变化 → 热重载）
  ├─ 网速监控（每秒）
  └─ 心跳（每 10s）

LocalAdminServer / LocalAdminServerManager （NanoHTTPD, 端口 5220）
  └─ Web 后台 + `/channels.m3u` 供本机回环拉取

FileLogStore（cacheDir/tivimate_diag.txt）/ AppLogStore（内存 500 行环形缓冲）
```

### 包结构（`com.tivimatelite`）

| 文件 | 职责 |
|---|---|
| `SplashActivity.kt` | 启动闪屏，约 900ms 后跳转 MainActivity |
| `MainActivity.kt` | 唯一 Activity：按键分发、切台/切源、故障转移、ready-stall 监控 |
| `BootReceiver.kt` | 开机自启广播（受 Web 后台开关控制） |
| `model/Channel.kt` | 频道数据类（name, logoUrl, groupName, streamUrl, epgText） |
| `parser/M3U8Parser.kt` | 流式 M3U8 解析器（`BufferedReader.lineSequence()` + Kotlin Flow） |
| `player/PlayerManager.kt` | **全局单例 IJKPlayer 封装** + 播放选项 + 状态机 + 事件回调 |
| `player/PlayableHostStore.kt` | 智能线路记忆（可播放域名集合） |
| `player/PlaybackHistoryStore.kt` | 上次播放频道/线路持久化 |
| `switcher/ChannelSwitcher.kt` | 选源、切台/切源防抖、多源故障转移、加载/缓冲超时 |
| `monitor/ReadyStallWatch.kt` | ready-stall、网速、心跳、播放列表变更监听 |
| `input/InputHandler.kt` | 数字选台缓冲（防抖 900ms）与悬浮层 |
| `loader/ChannelLoader.kt` | 拉取 + 同名合并为频道组 + 恢复上次选中 |
| `data/RemotePlaylistRepository.kt` | 远程/本机候选源探测与拉取 |
| `ui/ChannelAdapter.kt` | RecyclerView 适配器（DiffUtil + Glide RGB_565） |
| `util/HttpFetcher.kt` | HTTP 拉取工具 |
| `web/LocalAdminServer.kt` | NanoHTTPD Web 后台（端口 5220） |
| `web/LocalAdminServerManager.kt` | 后台生命周期与候选 URL |
| `web/PlaylistStore.kt` | 直播源管理模式（内置 / 自定义 / 粘贴） |
| `web/BootPrefs.kt` | 开机自启开关 |
| `web/FileLogStore.kt` | 文件日志（`cacheDir/tivimate_diag.txt`）+ 未捕获异常处理器 |
| `web/AppLogStore.kt` | 内存环形缓冲日志（500 行），供 Web 后台查看 |

### 播放内核（IJKPlayer）选项

在 `PlayerManager.applyOptions()` 中设置（category：1=FORMAT, 2=CODEC, 4=PLAYER）：

- **PLAYER**：`mediacodec` 与 `mediacodec-all-videos/-avc/-hevc/-mpeg2/-mpeg4` 硬解，
  `mediacodec-handle-resolution-change`（分辨率切换），`framedrop`（丢帧策略），
  `overlay-format=RV32`，`packet-buffering=0`（低延迟）
- **FORMAT**：`user_agent=SYTV/1.6`，`probesize` / `analyzeduration`，
  `reconnect=1`（自动重连）
- **CODEC**：`skip_loop_filter=48`（解码加速）

> 未在本 AAR 的 `libijkplayer.so` 中注册的选项会被 `av_opt_set_dict` 静默忽略，故代码中已剔除无效项。

### 关键设计

- **单 IJKPlayer 实例**：切台/切源只 `reset()` + 重新 `setDataSource` + `prepareAsync`，不重建播放器
- **SurfaceView**：裸 `SurfaceView`（`setZOrderMediaOverlay(true)`），直接绑定 `holder.surface`，消除切台黑闪
- **纯黑背景**：`#000000`，无动画过渡
- **Glide**：强制 `override(64,64)` + `RGB_565`，列表静止时才加载台标
- **硬解/原生库异常**：只记录日志不崩溃；原生库加载失败永久置位，避免重试风暴
- **Ready-stall 兜底**：应对 HLS timeline 不连续导致 `currentPosition` 卡死

---

## 调试命令

### 基础检查

```bash
adb shell dumpsys meminfo com.tivimatelite
adb shell top -o PID,CPU,RES,ARGS | grep tivimatelite
adb logcat -s PlayerManager MainActivity ChannelSwitcher ReadyStallWatch
```

### 功能验证

```bash
# 切源（左右键）
adb logcat -s MainActivity | findstr "Manual source switch"

# 加载超时
adb logcat -s MainActivity | findstr "Load timeout"
# → "Load timeout (15000ms), trying next source"

# 线路记忆
adb logcat -s MainActivity | findstr "playable hosts"
# → "Removed failed host from playable hosts: http://..."

# 设备端日志文件（ADB 断开后仍可用）
adb shell cat /data/data/com.tivimatelite/cache/tivimate_diag.txt
```

### Web 后台

在浏览器打开 `http://<盒子IP>:5220/`（避免 ADB，直接局域网访问）。
经 ADB 转发时：

```bash
adb forward tcp:5220 tcp:5220
# 然后访问 http://127.0.0.1:5220/
```

### 长测与恢复

```bash
# ADB over TCP 约 3 分钟无活动断开，长测必开 keepalive
powershell -c "while(1){sleep 5; adb shell echo alive > $null}"

# 恢复命令
adb shell am force-stop com.tivimatelite
adb shell pm clear com.tivimatelite
adb uninstall com.tivimatelite
```

---

## 构建约束

- AGP 8.7.3 + Kotlin 2.0.21，Gradle 8.10.2
- `compileSdk = 35`、`targetSdk = 28`（Android 9）、`minSdk = 24`
- `abiFilters`：`armeabi-v7a` + `arm64-v8a`（IJKPlayer AAR 两种原生库均提供）
- ViewBinding + BuildConfig 均已开启，JVM target 17

### 主要依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| `androidx.core:core-ktx` | 1.13.1 | Kotlin 扩展 |
| `androidx.appcompat:appcompat` | 1.7.0 | AppCompat |
| `ijkplayer-cmake-release.aar` | k0.8.9 | **播放内核**（本地 AAR） |
| `androidx.recyclerview:recyclerview` | 1.3.2 | 频道列表 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | 协程 |
| `com.github.bumptech.glide:glide` | 4.16.0 | 台标加载 |
| `org.nanohttpd:nanohttpd` | 2.3.1 | Web 后台 |

---

## 已知问题

- **HLS timeline 不连续**（部分 `fmt=ts2hls` 实时转码源）：`currentPosition` 卡住时，由 300s ready-stall 兜底切源
- **单源频道**：所有源失败后按 5-15s 递增间隔重试，最多 3 次，之后放弃直到用户切台
- **ADB over TCP 约 3 分钟无活动断开**：长测需后台 keepalive

---

## 开发约定

- 仓库根目录 `要求.txt` 为原始架构需求文档（历史，播放内核已由该文档描述的 ExoPlayer 迁移为 IJKPlayer）
- `AGENTS.md` 为面向 AI/开发者的精简指南
- **PowerShell 中 `$pid` 为只读自动变量**，不可赋值，改用 `$currentProcId` 等
- 修改 `app/build.gradle.kts` 中的 `PLAYLIST_URL` 切换远程直播源
