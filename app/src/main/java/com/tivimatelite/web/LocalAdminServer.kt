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
                val playlist = PlaylistStore.loadEffectivePlaylist(appContext)
                newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL", playlist)
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        return when (session.uri) {
            "/save" -> {
                val form = HashMap<String, String>()
                session.parseBody(form)
                val content = session.parameters["playlist"]?.firstOrNull().orEmpty()
                PlaylistStore.saveCustomPlaylist(appContext, content)
                AppLogStore.i("AdminServer", "Playlist updated from web admin")
                redirect("/")
            }
            "/logs/clear" -> {
                AppLogStore.clear()
                AppLogStore.i("AdminServer", "Log cleared from web admin")
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
        val playlist = escapeHtml(PlaylistStore.loadEffectivePlaylist(appContext))
        val hasCustom = PlaylistStore.hasCustomPlaylist(appContext)
        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8" />
                <title>TiviMateLite Admin</title>
                <style>
                  body { font-family: sans-serif; background: #111; color: #fff; margin: 24px; }
                  textarea { width: 100%; height: 420px; background: #000; color: #fff; border: 1px solid #333; padding: 10px; }
                  button { padding: 10px 16px; margin-top: 10px; }
                  a { color: #8ecbff; }
                  .tip { color: #aaa; margin-top: 8px; }
                </style>
              </head>
              <body>
                <h2>TiviMateLite Playlist Admin</h2>
                <p>Custom playlist active: <b>${if (hasCustom) "YES" else "NO"}</b></p>
                <form action="/save" method="post">
                  <textarea name="playlist">$playlist</textarea>
                  <br/>
                  <button type="submit">Save Playlist</button>
                </form>
                <p><a href="/logs">View App Logs</a></p>
                <p class="tip">Save immediately affects /channels.m3u source for this app.</p>
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
                <title>TiviMateLite Logs</title>
                <style>
                  body { font-family: monospace; background: #111; color: #fff; margin: 24px; }
                  pre { background: #000; border: 1px solid #333; padding: 12px; white-space: pre-wrap; }
                  button { padding: 8px 12px; margin-right: 8px; }
                  a { color: #8ecbff; }
                </style>
              </head>
              <body>
                <h2>App Logs</h2>
                <p><a href="/">Back to Playlist Admin</a></p>
                <form action="/logs/clear" method="post">
                  <button type="submit">Clear Logs</button>
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
