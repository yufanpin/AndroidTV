package com.tivimatelite

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.tivimatelite.databinding.ActivitySplashBinding
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySplashBinding
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playSplashAnimation()
        openMainWithDelay()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun playSplashAnimation() {
        binding.splashContent.visibility = View.VISIBLE
        binding.splashContent.post {
            binding.splashContent.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(420)
                .start()
        }
    }

    private fun openMainWithDelay() {
        scope.launch {
            delay(900)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
