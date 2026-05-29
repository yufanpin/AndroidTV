package top.yogiczy.mytv.data.repositories.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import top.yogiczy.mytv.data.repositories.NetworkClient
import top.yogiczy.mytv.data.repositories.git.parser.GitReleaseParser
import top.yogiczy.mytv.utils.Loggable

class GitRepository : Loggable() {

    suspend fun latestRelease(url: String) = withContext(Dispatchers.IO) {
        log.d("获取最新发行版: $url")

        val request = Request.Builder().url(url).build()

        try {
            NetworkClient.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("获取最新发行版失败: ${response.code}")
                }

                val parser = GitReleaseParser.instances.first { it.isSupport(url) }
                return@use parser.parse(response.body!!.string())
            }
        } catch (ex: Exception) {
            log.e("获取最新发行版失败", ex)
            throw Exception("获取最新发行版失败，请检查网络连接", ex)
        }
    }
}
