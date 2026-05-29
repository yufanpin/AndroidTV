package top.yogiczy.mytv

import android.annotation.SuppressLint
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import top.yogiczy.mytv.utils.Logger

// 防止部分直播源链接证书不被信任
@SuppressLint("CustomX509TrustManager")
class UnsafeTrustManager : X509TrustManager {
    @SuppressLint("TrustAllX509TrustManager")
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        // Do nothing and trust all certificates
    }

    @SuppressLint("TrustAllX509TrustManager")
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        // Do nothing and trust all certificates
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return emptyArray()
    }

    companion object {
        private val log = Logger.create(UnsafeTrustManager::class.java.simpleName)

        fun enableUnsafeTrustManager() {
            try {
                val trustAllCerts = arrayOf<TrustManager>(UnsafeTrustManager())
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
                HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            } catch (e: NoSuchAlgorithmException) {
                log.e("启用不安全证书信任失败", e)
            } catch (e: KeyManagementException) {
                log.e("启用不安全证书信任失败", e)
            }
        }
    }
}
