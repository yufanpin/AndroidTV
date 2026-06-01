package com.tivimatelite.web

import android.content.Context
import fi.iki.elonen.NanoHTTPD

class LocalAdminServer(
    context: Context,
    port: Int
) : NanoHTTPD("0.0.0.0", port) {
    private val appContext = context.applicationContext

    override fun serve(session: IHTTPSession): Response {
        return when (session.method) {
            Method.GET -> handleGet(session)
            Method.POST -> handlePost(session)
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
        }
    }

    private fun handleGet(session: IHTTPSession): Response {
        return when (session.uri) {
            "/" -> htmlResponse(buildHomePage())
            "/logs" -> htmlResponse(buildLogsPage())
            "/logs/raw" -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, AppLogStore.dump())
            "/channels.m3u", "/playlist" -> {
                val effective = PlaylistStore.loadEffectivePlaylist(appContext)
                val response = newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL", effective.content)
                response.addHeader("X-Playlist-Mode", effective.mode)
                response.addHeader("X-Playlist-Active", effective.activeSourceLabel)
                response
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        runCatching {
            session.parseBody(HashMap())
        }.onFailure {
            AppLogStore.w("AdminServer", "POST parseBody failed", it)
        }

        return when (session.uri) {
            "/boot/toggle" -> {
                val enabled = session.parameters["enabled"]?.firstOrNull() == "1"
                BootPrefs.setAutoStartEnabled(appContext, enabled)
                redirect("/")
            }
            "/mode" -> {
                val mode = session.parameters["mode"]?.firstOrNull().orEmpty()
                if (mode == "custom") PlaylistStore.setModeCustom(appContext) else PlaylistStore.setModeBuiltin(appContext)
                redirect("/")
            }
            "/source/add" -> {
                val name = session.parameters["name"]?.firstOrNull().orEmpty()
                val url = session.parameters["url"]?.firstOrNull().orEmpty()
                PlaylistStore.addCustomSource(appContext, name, url)
                redirect("/")
            }
            "/source/add-content" -> {
                val name = session.parameters["name"]?.firstOrNull().orEmpty()
                val content = session.parameters["content"]?.firstOrNull().orEmpty()
                PlaylistStore.addCustomSourceWithContent(appContext, name, content)
                redirect("/")
            }
            "/source/select" -> {
                val id = session.parameters["id"]?.firstOrNull().orEmpty()
                PlaylistStore.selectCustomSource(appContext, id)
                PlaylistStore.setModeCustom(appContext)
                redirect("/")
            }
            "/source/delete" -> {
                val id = session.parameters["id"]?.firstOrNull().orEmpty()
                PlaylistStore.deleteCustomSource(appContext, id)
                redirect("/")
            }
            "/logs/clear" -> {
                AppLogStore.clear()
                AppLogStore.i("AdminServer", "日志已清空")
                redirect("/logs")
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun redirect(path: String): Response {
        val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, "")
        response.addHeader("Location", path)
        return response
    }

    private fun htmlResponse(html: String): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun buildHomePage(): String {
        val autoStartEnabled = BootPrefs.isAutoStartEnabled(appContext)
        val isCustom = PlaylistStore.isCustomMode(appContext)
        val selectedId = PlaylistStore.getSelectedSourceId(appContext)
        val sources = PlaylistStore.getCustomSources(appContext)
        val effective = PlaylistStore.loadEffectivePlaylist(appContext)

        val rows = buildString {
            for (source in sources) {
                val checked = if (source.id == selectedId) "checked" else ""
                append("<tr>")
                append("<td><form action=\"/source/select\" method=\"post\"><input type=\"hidden\" name=\"id\" value=\"${escapeHtml(source.id)}\"/><input type=\"radio\" $checked onclick=\"this.form.submit()\"/></form></td>")
                append("<td>${escapeHtml(source.name)}</td>")
                append("<td>${escapeHtml(source.url)}</td>")
                append("<td><form action=\"/source/delete\" method=\"post\"><input type=\"hidden\" name=\"id\" value=\"${escapeHtml(source.id)}\"/><button type=\"submit\">删除</button></form></td>")
                append("</tr>")
            }
        }

        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8" />
                <title>TiviMateLite 后台</title>
                <style>
                  body { font-family: sans-serif; background: #111; color: #fff; margin: 24px; }
                  input, button { padding: 8px; }
                  table { width: 100%; border-collapse: collapse; margin-top: 10px; }
                  th, td { border: 1px solid #333; padding: 8px; vertical-align: top; }
                  th { background: #1a1a1a; }
                  a { color: #8ecbff; }
                  .box { border: 1px solid #333; padding: 12px; margin-top: 12px; }
                </style>
              </head>
              <body>
                <h2>TiviMateLite 本地后台</h2>
                <p><b>当前生效源：</b>${escapeHtml(effective.activeSourceLabel)}</p>

                <div class="box">
                  <h3>源模式</h3>
                  <form action="/mode" method="post">
                    <label><input type="radio" name="mode" value="builtin" ${if (!isCustom) "checked" else ""}/> 使用内置源</label><br/>
                    <label><input type="radio" name="mode" value="custom" ${if (isCustom) "checked" else ""}/> 使用自定义源</label><br/><br/>
                    <button type="submit">保存模式</button>
                  </form>
                </div>

                <div class="box">
                  <h3>添加自定义源</h3>
                  <form action="/source/add" method="post">
                    <input type="text" name="name" placeholder="源名称" style="width:220px"/>
                    <input type="text" name="url" placeholder="http://.../channels.m3u" style="width:420px"/>
                    <button type="submit">添加</button>
                  </form>
                </div>

                <div class="box">
                  <h3>直接粘贴直播源内容</h3>
                  <form action="/source/add-content" method="post">
                    <input type="text" name="name" placeholder="源名称" style="width:220px"/><br/><br/>
                    <textarea name="content" rows="10" style="width:100%;font-family:monospace;" placeholder="#EXTM3U ..."></textarea><br/><br/>
                    <button type="submit">添加粘贴源</button>
                  </form>
                </div>

                <div class="box">
                  <h3>自定义源列表（勾选即启用）</h3>
                  <table>
                    <thead><tr><th>启用</th><th>名称</th><th>地址</th><th>操作</th></tr></thead>
                    <tbody>
                      $rows
                    </tbody>
                  </table>
                </div>

                <div class="box">
                  <h3>系统</h3>
                  <form action="/boot/toggle" method="post">
                    <label>
                      <input type="checkbox" name="enabled" value="1" ${if (autoStartEnabled) "checked" else ""} onchange="this.form.submit()"/>
                      开机自动启动 App
                    </label>
                  </form>
                </div>

                <p><a href="/logs">查看应用日志</a></p>
              </body>
            </html>
        """.trimIndent()
    }

    private fun buildLogsPage(): String {
        val logs = escapeHtml(AppLogStore.dump())
        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8" />
                <title>TiviMateLite 日志</title>
                <style>
                  body { font-family: monospace; background: #111; color: #fff; margin: 24px; }
                  pre { background: #000; border: 1px solid #333; padding: 12px; white-space: pre-wrap; }
                  button { padding: 8px 12px; margin-right: 8px; }
                  a { color: #8ecbff; }
                </style>
              </head>
              <body>
                <h2>应用日志</h2>
                <p><a href="/">返回后台</a></p>
                <form action="/logs/clear" method="post">
                  <button type="submit">清空日志</button>
                </form>
                <pre id="logBox">$logs</pre>
                <script>
                  setInterval(async () => {
                    const r = await fetch('/logs/raw', { cache: 'no-store' });
                    document.getElementById('logBox').textContent = await r.text();
                  }, 2000);
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
