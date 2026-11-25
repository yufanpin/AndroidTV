package com.tivimatelite

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.tivimatelite.databinding.ActivitySplashBinding
import com.tivimatelite.ysp.YspEngine
import com.tivimatelite.ysp.YspEngineService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动页：拉起引擎前台服务，等引擎就绪后进入主界面。
 * 不再固定等待 900ms；引擎失败时给出原因与重试入口。
 */
class SplashActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySplashBinding
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playEnterAnimation()

        binding.splashRetry.setOnClickListener {
            binding.splashError.visibility = View.GONE
            binding.splashRetry.visibility = View.GONE
            binding.splashProgress.visibility = View.VISIBLE
            YspEngineService.restart(this)
            waitForEngine()
        }

        YspEngineService.start(this)
        waitForEngine()
    }

    private fun playEnterAnimation() {
        binding.splashContent.post {
            binding.splashContent.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(420)
                .start()
        }
    }

    private fun waitForEngine() {
        scope.launch {
            val startedAt = System.currentTimeMillis()
            val deadline = startedAt + 12000L
            while (System.currentTimeMillis() < deadline) {
                val state = YspEngine.state.value
                if (state.ready) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (elapsed < MIN_SPLASH_MS) delay(MIN_SPLASH_MS - elapsed)
                    binding.splashStage.text = "引擎就绪，正在进入…"
                    openMain()
                    return@launch
                }
                if (state.failed) {
                    showFailure(state.error.ifBlank { "引擎启动失败" })
                    return@launch
                }
                binding.splashStage.text = state.stage.ifBlank { "正在启动直播引擎…" }
                delay(150)
            }
            showFailure("引擎启动超时，请检查网络后重试")
        }
    }

    private fun showFailure(reason: String) {
        binding.splashProgress.visibility = View.GONE
        binding.splashStage.text = "启动失败"
        binding.splashError.text = "$reason\n可访问 设备IP:5221 查看详细日志"
        binding.splashError.visibility = View.VISIBLE
        binding.splashRetry.visibility = View.VISIBLE
        binding.splashRetry.requestFocus()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MIN_SPLASH_MS = 600L
    }
}
