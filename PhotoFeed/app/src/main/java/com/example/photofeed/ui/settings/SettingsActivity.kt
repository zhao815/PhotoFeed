package com.example.photofeed.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.photofeed.R
import com.example.photofeed.data.MotionRecognitionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val btn = findViewById<MaterialButton>(R.id.btnRecognizeMotion)

        btn.setOnClickListener {
            // 只负责「点火」：任务跑在进程作用域里，退出设置页、切到视频频道
            // 继续刷都不会中断，识别结果写进缓存后会自动刷新动图频道。
            MotionRecognitionManager.start(applicationContext)
        }

        // 按钮状态跟随后台任务：旋转或重新进入设置页也能正确显示「识别中」。
        lifecycleScope.launch {
            MotionRecognitionManager.running.collect { running ->
                btn.isEnabled = !running
                btn.text = getString(
                    if (running) R.string.recognize_motion_running
                    else R.string.recognize_motion
                )
            }
        }

        // 只有用户还停留在设置页时才弹结果，避免在刷视频时突然被打断。
        lifecycleScope.launch {
            MotionRecognitionManager.finished.collect { result ->
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
                Toast.makeText(
                    applicationContext,
                    getString(
                        R.string.recognize_motion_done,
                        result.total,
                        result.scannedNew,
                        result.skipped
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
