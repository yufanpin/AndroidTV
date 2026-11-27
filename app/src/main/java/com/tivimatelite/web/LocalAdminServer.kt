package com.tivimatelite.web

import android.app.ActivityManager
import android.content.Context
import com.tivimatelite.player.PlaybackHistoryStore
import com.tivimatelite.ysp.YspConfig
import com.tivimatelite.ysp.YspEngine
import com.tivimatelite.ysp.YspEngineService
import com.tivimatelite.ysp.YspEvents
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * ysp 分支的本地控制台（5221）。
 * 四个页签：状态 / 流程 / 设置 / 日志；同时提供 JSON API 给页面增量刷新。
 */
class LocalAdminServer(
    context: Context,
    port: Int
) : NanoHTTPD("0.0.0.0", port) {

    private val appContext = context.applicationContext

    override fun serve(session: IHTTPSession): Response = when (session.method) {
        Method.GET -> handleGet(session)
        Method.POST -> handlePost(session)
        else -> newFixedLengthResponse(
            Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed"
        )
    }

    // ---------------------------------------------------------------- GET

    private fun handleGet(session: IHTTPSession): Response {
        return when (session.uri) {
            "/" -> html(pageStatus())
            "/flow" -> html(pageFlow())
            "/settings" -> html(pageSettings())
            "/logs" -> html(pageLogs())
            "/api/status" -> json(statusJson())
            "/api/channels" -> json(channelsJson())
            "/api/events" -> json(eventsJson(queryLong(session, "after")))
            "/api/logs" -> json(logsJson(queryLong(session, "after"), queryString(session, "level")))
            "/logs/raw" -> newFixedLengthResponse(
                Response.Status.OK, "text/plain; charset=utf-8", AppLogStore.dump()
            )
            "/all.m3u" -> proxyEngine("/all.m3u", "application/vnd.apple.mpegurl")
            "/diag" -> proxyEngine("/diag", "text/plain; charset=utf-8")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
    }

    private fun statusJson(): String {
        val state = YspEngine.state.value
        val last = PlaybackHistoryStore.getLastPlayedState(appContext)
        return JSONObject().apply {
            put("ready", state.ready)
            put("starting", state.starting)
            put("failed", state.failed)
            put("stage", state.stage)
            put("error", state.error)
            put("port", state.port)
            put("channels", state.channels)
            put("highBitrate", state.highBitrateReady)
            put("uptimeMs", state.uptimeMs)
            put("cacheEntries", state.cacheEntries)
            put("cacheHitRate", state.cacheHitRate)
            put("guid", state.guid)
            put("heartbeat", state.heartbeat)
            put("rssMb", rssMb())
            put("adminUrl", LocalAdminServerManager.getAdminUrl())
            put("engineUrl", YspEngine.playlistUrl())
            put("currentChannel", last.channelName.orEmpty())
            put("currentUrl", last.url.orEmpty())
            put("logTotal", AppLogStore.totalLines())
        }.toString()
    }

    private fun channelsJson(): String {
        val array = JSONArray()
        for (info in YspEngine.channels()) {
            array.put(JSONObject().apply {
                put("slug", info.slug)
                put("name", info.name)
                put("group", info.group)
                put("mode", info.mode)
                put("defn", info.defn)
                put("segments", info.segments)
                put("lastOk", info.lastOkSec)
                put("error", info.error)
            })
        }
        return JSONObject().put("channels", array).toString()
    }

    private fun eventsJson(after: Long): String {
        val array = JSONArray()
        var lastSeq = after
        for (event in YspEvents.since(after)) {
            lastSeq = maxOf(lastSeq, event.seq)
            array.put(JSONObject().apply {
                put("seq", event.seq)
                put("at", event.at)
                put("channel", event.channel)
                put("stage", event.stage)
                put("message", event.message)
                put("level", event.level)
            })
        }
        return JSONObject().put("events", array).put("lastSeq", lastSeq).toString()
    }

    private fun logsJson(after: Long, level: String?): String {
        val (total, text) = AppLogStore.dumpFrom(after)
        val lines = if (text.isEmpty()) emptyList() else text.split("\n")
        val filtered = when (level?.uppercase(Locale.ROOT)) {
            "E", "ERROR" -> lines.filter { it.contains(" E/") }
            "W", "WARN" -> lines.filter { it.contains(" W/") || it.contains(" E/") }
            else -> lines
        }
        return JSONObject().apply {
            put("total", total)
            put("lines", JSONArray(filtered))
        }.toString()
    }

    // ---------------------------------------------------------------- POST

    private fun handlePost(session: IHTTPSession): Response {
        runCatching { session.parseBody(HashMap()) }
            .onFailure { AppLogStore.w(TAG, "POST parseBody failed", it) }

        return when (session.uri) {
            "/config" -> {
                saveConfig(session)
                restartLater()
                redirect("/settings")
            }
            "/engine/restart" -> {
                restartLater()
                redirect("/")
            }
            "/engine/stop" -> {
                Thread { YspEngineService.stop(appContext) }.start()
                redirect("/")
            }
            "/engine/start" -> {
                Thread { YspEngineService.start(appContext) }.start()
                redirect("/")
            }
            "/audio/gain" -> {
                val raw = param(session, "gain")
                val parsed = raw?.toFloatOrNull()
                if (parsed != null) {
                    AudioGainPrefs.saveGain(appContext, parsed)
                } else {
                    AppLogStore.w(TAG, "audio/gain ignored: raw=$raw")
                }
                redirect("/settings")
            }
            "/boot/toggle" -> {
                val enabled = param(session, "enabled") == "1"
                BootPrefs.setAutoStartEnabled(appContext, enabled)
                redirect("/settings")
            }
            "/logs/clear" -> {
                AppLogStore.clear()
                AppLogStore.i(TAG, "日志已清空")
                redirect("/logs")
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
    }

    private fun saveConfig(session: IHTTPSession) {
        val current = YspConfig.load(appContext)
        val updated = current.copy(
            port = param(session, "port")?.toIntOrNull() ?: current.port,
            cacheMb = param(session, "cache")?.toIntOrNull() ?: current.cacheMb,
            linksPerDevice = param(session, "links")?.toIntOrNull() ?: current.linksPerDevice,
            zapDebounceSec = param(session, "zap")?.toDoubleOrNull() ?: current.zapDebounceSec,
            disableHighBitrate = param(session, "high_bitrate") != "1",
            only4kHighBitrate = param(session, "only4k") == "1",
            allowLan = param(session, "allow_lan") == "1",
            logLevel = param(session, "log_level")?.toIntOrNull() ?: current.logLevel
        )
        YspConfig.save(appContext, updated)
        com.gary.ysplive.core.spi.Log.setMinLevel(updated.logLevel)
        AppLogStore.i(TAG, "配置已保存: $updated")
        YspEvents.record("", YspEvents.STAGE_OUTPUT, "配置已保存，引擎重启中")
    }

    private fun restartLater() {
        Thread {
            runCatching { Thread.sleep(150) }
            YspEngineService.restart(appContext)
        }.start()
    }

    // ---------------------------------------------------------------- 页面

    private fun pageStatus(): String {
        val state = YspEngine.state.value
        val last = PlaybackHistoryStore.getLastPlayedState(appContext)
        val body = buildString {
            append("<div class=\"grid\">")
            append(metric("引擎", if (state.ready) "就绪" else if (state.failed) "失败" else "启动中"))
            append(metric("监听端口", if (state.port > 0) state.port.toString() else "-"))
            append(metric("频道", state.channels.toString()))
            append(metric("运行时长", formatUptime(state.uptimeMs)))
            append(metric("进程内存", "${rssMb()} MB"))
            append(metric("分片缓存", "${state.cacheEntries} 条 / ${fmt(state.cacheHitRate)}%"))
            append("</div>")
            append("<div class=\"card\" style=\"margin-top:12px\">")
            append("<div class=\"k\">当前频道</div>")
            append("<div class=\"v\">${esc(last.channelName ?: "未播放")}</div>")
            append("<div class=\"muted\">${esc(last.url ?: "")}</div>")
            append("</div>")
            append("<div class=\"card\" style=\"margin-top:12px\">")
            append("<div class=\"row\"><span>高码率设备</span><span>${if (state.highBitrateReady) "已就绪" else "未就绪（1080p 模式）"}</span></div>")
            append("<div class=\"row\"><span>设备会话</span><span>心跳 ${state.heartbeat} · GUID ${esc(state.guid.ifEmpty { "-" })}</span></div>")
            append("<div class=\"row\"><span>订阅地址</span><span>${esc(YspEngine.playlistUrl().ifEmpty { "-" })}</span></div>")
            append("<div class=\"row\"><span>控制台</span><span>${esc(LocalAdminServerManager.getAdminUrl())}</span></div>")
            if (state.error.isNotBlank()) {
                append("<div class=\"row\"><span>最近错误</span><span class=\"err\">${esc(state.error)}</span></div>")
            }
            append("</div>")
            append("<div class=\"actions\">")
            append("<form action=\"/engine/restart\" method=\"post\"><button class=\"btn\">重启引擎</button></form>")
            append("<form action=\"/engine/stop\" method=\"post\"><button class=\"btn\">停止引擎</button></form>")
            append("<a class=\"btn ghost\" href=\"/diag\" target=\"_blank\">引擎诊断</a>")
            append("</div>")
        }
        return layout("状态", "status", body)
    }

    private fun pageFlow(): String {
        val body = """
            <div class="pipe">
              <div class="step" id="s_request"><div class="k">1 请求</div><div class="v">-</div><div class="muted">次/分</div></div>
              <div class="step" id="s_select"><div class="k">2 选源</div><div class="v">-</div><div class="muted">事件</div></div>
              <div class="step" id="s_upstream"><div class="k">3 上游取流</div><div class="v">-</div><div class="muted">事件</div></div>
              <div class="step" id="s_rewrite"><div class="k">4 列表改写</div><div class="v">-</div><div class="muted">事件</div></div>
              <div class="step" id="s_output"><div class="k">5 输出</div><div class="v">-</div><div class="muted">事件</div></div>
              <div class="step" id="s_player"><div class="k">6 播放器</div><div class="v">-</div><div class="muted">事件</div></div>
            </div>
            <div class="grid" style="margin-top:12px">
              <div class="card"><div class="k">当前频道</div><div class="v" id="flowChannel">-</div><div class="muted" id="flowSource">-</div></div>
              <div class="card"><div class="k">线路分布</div><div class="v" id="flowModes">-</div><div class="muted">JCE / BK / 设备</div></div>
            </div>
            <div class="card" style="margin-top:12px">
              <div class="k">事件流</div>
              <div class="events" id="eventList"></div>
            </div>
            <script>
              const stageIds = ['request','select','upstream','rewrite','output','player'];
              async function tickFlow() {
                try {
                  const st = await (await fetch('/api/status', {cache:'no-store'})).json();
                  const ev = await (await fetch('/api/events?after=0', {cache:'no-store'})).json();
                  const now = Date.now();
                  const counts = {};
                  (ev.events || []).slice(-80).forEach(function(e) {
                    if (now - e.at < 60000) counts[e.stage] = (counts[e.stage] || 0) + 1;
                  });
                  stageIds.forEach(function(id) {
                    const el = document.getElementById('s_' + id);
                    if (!el) return;
                    el.querySelector('.v').textContent = counts[id] || 0;
                    el.classList.toggle('hot', (counts[id] || 0) > 0);
                  });
                  const rows = [];
                  (ev.events || []).slice(-18).reverse().forEach(function(e) {
                    const t = new Date(e.at);
                    const hh = String(t.getHours()).padStart(2,'0');
                    const mm = String(t.getMinutes()).padStart(2,'0');
                    const ss = String(t.getSeconds()).padStart(2,'0');
                    rows.push('<div class="ev"><span class="ts">' + hh + ':' + mm + ':' + ss + '</span><span class="tag ' + e.stage + '">' + e.stage + '</span><span class="msg">' + escapeHtml(e.message) + '</span></div>');
                  });
                  document.getElementById('eventList').innerHTML = rows.join('') || '<div class="muted">暂无事件</div>';
                  document.getElementById('flowChannel').textContent = st.currentChannel || '-';
                  document.getElementById('flowSource').textContent = st.engineUrl || '-';
                } catch (err) {}
              }
              function escapeHtml(s) {
                return String(s == null ? '' : s).replace(/[&<>"]/g, function(c) {
                  return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];
                });
              }
              tickFlow(); setInterval(tickFlow, 1500);
            </script>
        """.trimIndent()
        return layout("流程", "flow", body)
    }

    private fun pageSettings(): String {
        val config = YspConfig.load(appContext)
        val gain = String.format(Locale.US, "%.2f", AudioGainPrefs.loadGain(appContext))
        val boot = BootPrefs.isAutoStartEnabled(appContext)
        val body = """
            <form action="/config" method="post">
              <div class="card">
                <div class="k">引擎设置（标 * 的项保存后会自动重启）</div>
                <div class="row"><span>高码率 / 4K *</span><label class="switch"><input type="checkbox" name="high_bitrate" value="1" ${if (!config.disableHighBitrate) "checked" else ""}/><span></span></label></div>
                <div class="row"><span>仅 4K / 8K 走高码率 *</span><label class="switch"><input type="checkbox" name="only4k" value="1" ${if (config.only4kHighBitrate) "checked" else ""}/><span></span></label></div>
                <div class="row"><span>分片缓存（MB）*</span><input class="num" type="number" name="cache" min="16" max="128" value="${config.cacheMb}"/></div>
                <div class="row"><span>单设备并发 *</span><input class="num" type="number" name="links" min="1" max="12" value="${config.linksPerDevice}"/></div>
                <div class="row"><span>换台防抖（秒）*</span><input class="num" type="number" step="0.5" name="zap" min="0" max="30" value="${config.zapDebounceSec}"/></div>
                <div class="row"><span>监听端口 *</span><input class="num" type="number" name="port" min="1024" max="65535" value="${config.port}"/></div>
                <div class="row"><span>局域网共享 8767 *</span><label class="switch"><input type="checkbox" name="allow_lan" value="1" ${if (config.allowLan) "checked" else ""}/><span></span></label></div>
                <div class="row"><span>日志级别</span>
                  <select class="num" name="log_level">
                    <option value="0" ${if (config.logLevel == 0) "selected" else ""}>DEBUG</option>
                    <option value="1" ${if (config.logLevel == 1) "selected" else ""}>INFO</option>
                    <option value="2" ${if (config.logLevel == 2) "selected" else ""}>WARN</option>
                    <option value="3" ${if (config.logLevel == 3) "selected" else ""}>ERROR</option>
                  </select>
                </div>
                <div class="actions"><button class="btn" type="submit">保存并重启引擎</button></div>
              </div>
            </form>

            <form action="/audio/gain" method="post">
              <div class="card" style="margin-top:12px">
                <div class="k">音频增益（当前 $gain 倍）</div>
                <div class="row" style="border:none">
                  <input type="range" name="gain" min="1.0" max="3.0" step="0.1" value="$gain" style="width:60%" oninput="document.getElementById('gainOut').textContent=Number(this.value).toFixed(2)"/>
                  <output id="gainOut">$gain</output>
                  <button class="btn small" type="submit">保存</button>
                </div>
                <div class="muted">保存后会自动重播当前频道，使新增益立即生效。</div>
              </div>
            </form>

            <form action="/boot/toggle" method="post">
              <div class="card" style="margin-top:12px">
                <div class="row" style="border:none"><span>开机自动启动</span><label class="switch"><input type="checkbox" name="enabled" value="1" ${if (boot) "checked" else ""} onchange="this.form.submit()"/><span></span></label></div>
              </div>
            </form>
        """.trimIndent()
        return layout("设置", "settings", body)
    }

    private fun pageLogs(): String {
        val body = """
            <div class="card">
              <div class="actions" style="margin:0 0 10px">
                <select id="level" class="num" onchange="resetLogs()">
                  <option value="">全部</option>
                  <option value="W">警告 + 错误</option>
                  <option value="E">仅错误</option>
                </select>
                <a class="btn ghost" href="/logs/raw" target="_blank">下载原始日志</a>
                <form action="/logs/clear" method="post"><button class="btn ghost" type="submit">清空</button></form>
                <span class="muted" id="logState">实时刷新中</span>
              </div>
              <pre id="logBox" class="logs"></pre>
            </div>
            <script>
              let after = 0;
              async function pollLogs() {
                try {
                  const level = document.getElementById('level').value;
                  const r = await fetch('/api/logs?after=' + after + '&level=' + level, {cache:'no-store'});
                  const j = await r.json();
                  if (j.lines && j.lines.length) {
                    const box = document.getElementById('logBox');
                    box.textContent += (box.textContent ? '\n' : '') + j.lines.join('\n');
                    if (box.textContent.length > 60000) box.textContent = box.textContent.slice(-50000);
                    box.scrollTop = box.scrollHeight;
                  }
                  after = j.total || after;
                  document.getElementById('logState').textContent = '实时刷新中 · ' + after + ' 条';
                } catch (err) {}
              }
              function resetLogs() {
                after = 0;
                document.getElementById('logBox').textContent = '';
                pollLogs();
              }
              pollLogs(); setInterval(pollLogs, 1200);
            </script>
        """.trimIndent()
        return layout("日志", "logs", body)
    }

    // ---------------------------------------------------------------- 工具

    private fun layout(title: String, active: String, body: String): String {
        val state = YspEngine.state.value
        val pill = when {
            state.ready -> "<span class=\"pill ok\">引擎运行中</span>"
            state.failed -> "<span class=\"pill bad\">引擎失败</span>"
            else -> "<span class=\"pill\">引擎启动中</span>"
        }
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width,initial-scale=1"/>
            <title>MIGU TV · $title</title>
            <style>
              *{box-sizing:border-box}
              body{margin:0;background:#080d0c;color:#e6efec;font-family:system-ui,-apple-system,"Segoe UI",sans-serif}
              .wrap{max-width:1080px;margin:0 auto;padding:22px}
              header{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}
              h1{font-size:19px;margin:0;font-weight:600}
              .sub{color:#8aa19c;font-size:12px;margin-top:3px}
              .pill{font-size:12px;padding:5px 10px;border-radius:999px;background:#141c1a;border:1px solid #24322f;color:#9fb3ae}
              .pill.ok{background:#0f2a25;border-color:#1f6b5d;color:#7fe3d6}
              .pill.bad{background:#2a1212;border-color:#7a2c2c;color:#ffb4b4}
              .tabs{display:flex;gap:8px;margin:16px 0}
              .tab{font-size:13px;padding:6px 14px;border-radius:999px;border:1px solid #24322f;color:#9fb3ae;text-decoration:none}
              .tab.on{background:#0f2a25;border-color:#2AA198;color:#7fe3d6}
              .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px}
              .card{background:#0f1513;border:1px solid #1e2a27;border-radius:10px;padding:14px}
              .k{font-size:11px;color:#8aa19c}
              .v{font-size:19px;margin-top:4px}
              .muted{font-size:11px;color:#7c938e;margin-top:3px}
              .err{color:#ffb4b4}
              .row{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid #182320;font-size:13px}
              .actions{display:flex;gap:8px;align-items:center;margin-top:12px;flex-wrap:wrap}
              .btn{background:#0f2a25;border:1px solid #2AA198;color:#bdf3ea;border-radius:8px;padding:7px 14px;font-size:12px;cursor:pointer;text-decoration:none}
              .btn.ghost{background:transparent;border-color:#2b3a36;color:#9fb3ae}
              .btn.small{padding:5px 10px}
              input.num,select.num{background:#0b1210;border:1px solid #2b3a36;color:#e6efec;border-radius:6px;padding:5px 8px;width:110px;font-size:12px}
              .switch input{display:none}
              .switch span{display:inline-block;width:34px;height:18px;border-radius:999px;background:#2b3a36;position:relative;transition:.15s}
              .switch span:after{content:"";position:absolute;width:14px;height:14px;border-radius:50%;background:#8aa19c;top:2px;left:2px;transition:.15s}
              .switch input:checked+span{background:#1f6b5d}
              .switch input:checked+span:after{left:18px;background:#dcfffa}
              .pipe{display:flex;gap:6px}
              .step{flex:1;min-width:0;background:#0f1513;border:1px solid #1e2a27;border-radius:9px;padding:10px}
              .step.hot{border-color:#2AA198;background:#0f2a25}
              .step .v{font-size:20px}
              .events{font-family:ui-monospace,Consolas,monospace;font-size:11px;line-height:1.9;margin-top:8px;max-height:360px;overflow:auto}
              .ev{display:flex;gap:8px}
              .ts{color:#6f8781}
              .tag{display:inline-block;min-width:64px;color:#7fe3d6}
              .tag.error{color:#ffb4b4}
              .msg{color:#b9c9c5;word-break:break-all}
              .logs{font-family:ui-monospace,Consolas,monospace;font-size:11px;line-height:1.7;background:#070b0a;border:1px solid #182320;border-radius:8px;padding:10px;max-height:560px;overflow:auto;white-space:pre-wrap;margin:0}
            </style></head>
            <body><div class="wrap">
              <header>
                <div><h1>MIGU TV 控制台</h1><div class="sub">${esc(LocalAdminServerManager.getAdminUrl())} · 本机引擎 ${if (state.port > 0) "127.0.0.1:${state.port}" else "尚未启动"}</div></div>
                $pill
              </header>
              <nav class="tabs">
                <a class="tab ${if (active == "status") "on" else ""}" href="/">状态</a>
                <a class="tab ${if (active == "flow") "on" else ""}" href="/flow">流程</a>
                <a class="tab ${if (active == "settings") "on" else ""}" href="/settings">设置</a>
                <a class="tab ${if (active == "logs") "on" else ""}" href="/logs">日志</a>
              </nav>
              $body
            </div></body></html>
        """.trimIndent()
    }

    private fun metric(label: String, value: String): String =
        "<div class=\"card\"><div class=\"k\">$label</div><div class=\"v\">${esc(value)}</div></div>"

    private fun formatUptime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun rssMb(): Int {
        return runCatching {
            val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))[0].totalPss / 1024
        }.getOrDefault(0)
    }

    private fun proxyEngine(path: String, mime: String): Response {
        val port = YspEngine.state.value.port
        if (port <= 0) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "engine not ready"
            )
        }
        return runCatching {
            val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 8000
            val bytes = connection.inputStream.readBytes()
            newFixedLengthResponse(
                Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong()
            )
        }.getOrElse {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "engine error: ${it.message}"
            )
        }
    }

    private fun param(session: IHTTPSession, name: String): String? =
        session.parameters[name]?.firstOrNull()

    private fun queryString(session: IHTTPSession, name: String): String? {
        val raw = session.queryParameterString ?: return null
        for (pair in raw.split("&")) {
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            if (key == name) {
                return if (idx >= 0) pair.substring(idx + 1) else ""
            }
        }
        return null
    }

    private fun queryLong(session: IHTTPSession, name: String): Long =
        queryString(session, name)?.toLongOrNull() ?: 0L

    private fun redirect(path: String): Response {
        val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, "")
        response.addHeader("Location", path)
        return response
    }

    private fun html(content: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", content)

    private fun json(content: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", content)

    private fun esc(value: String?): String {
        val text = value ?: ""
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    companion object {
        private const val TAG = "AdminServer"
    }
}
