# S905L3 测试指南

本项目可以直接通过 GitHub Actions 云端构建 APK，再安装到家里的 Android 9 / S905L3 机顶盒上测试。

## 使用 GitHub Actions 构建

1. 把当前项目推送到 GitHub 仓库。
2. 打开仓库页面，进入 `Actions`。
3. 手动运行 `Android CI`，或者推送到 `main` / `master` 自动触发。
4. 等待任务完成后打开 workflow run。
5. 下载 `TiviMateLite-debug-apk` artifact。
6. 解压后得到 `app-debug.apk`。

当前 workflow 会在云端安装 JDK 17 和 Gradle 8.10.2，不依赖你本机安装 Gradle。

## 准备直播源

App 当前从下面这个 asset 路径读取直播源：

```text
app/src/main/assets/channels.m3u
```

如果你要改为网页后台统一管理直播源，设置 `app/build.gradle.kts` 里的：

```kotlin
buildConfigField("String", "PLAYLIST_URL", "\"http://your-server/channels.m3u\"")
```

App 启动时会先尝试拉取这个远程地址；远程失败才回退到本地 `assets/channels.m3u`。

正式测试前，先放一个小型 `channels.m3u`，建议 5-20 个频道。确认焦点、解析、播放都正常后，再换成几千到几万行的大列表。

示例格式：

```m3u
#EXTM3U
#EXTINF:-1 tvg-name="CCTV 1" tvg-logo="https://example.com/logo.png" group-title="Test",CCTV 1
http://example.com/live/cctv1.m3u8
```

## 安装到机顶盒

如果机顶盒开启了网络 ADB，并且电脑和机顶盒在同一局域网：

```bash
adb connect BOX_IP_ADDRESS:5555
adb install -r app-debug.apk
adb shell monkey -p com.tivimatelite 1
```

如果没有网络 ADB，就把 APK 复制到 U 盘，用机顶盒文件管理器安装。

## 基础播放测试

1. 启动 `TiviMateLite`。
2. 按 `OK` 或任意方向键，确认 Overlay 立即显示。
3. 连续按上下键快速滚动频道列表。
4. 停在某个频道超过 300 ms。
5. 确认只有停留后才真正切台播放。
6. 按 `Back`，确认 Overlay 立即隐藏。

## 性能检查命令

播放过程中执行：

```bash
adb shell dumpsys meminfo com.tivimatelite
adb shell top -o PID,CPU,RES,ARGS | grep tivimatelite
adb logcat -s PlayerManager MainActivity MediaCodec ExoPlayer
```

S905L3 上的目标表现：

- App 启动稳定后，进程内存尽量贴近 60 MB 目标。
- 快速按遥控上下键时，频道列表焦点不应卡顿。
- 切台时不应重建 `ExoPlayer` 实例。
- 硬解异常应写入日志，不应直接崩溃。
- Overlay 显示和隐藏不应有动画。

## 大直播源测试

小列表通过后，把 `channels.m3u` 换成完整直播源，再通过 GitHub Actions 重新构建 APK。

大列表测试重点观察：

- `logcat` 是否出现长时间 GC。
- 解析直播源时遥控输入是否卡顿。
- 反复切台后 RAM 是否持续上涨。
- 快速滚动列表时解码器是否锁死。

## 常用恢复命令

```bash
adb shell am force-stop com.tivimatelite
adb shell pm clear com.tivimatelite
adb uninstall com.tivimatelite
```
