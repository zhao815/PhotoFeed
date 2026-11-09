package com.example.photofeed.ui.guide

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.photofeed.MainActivity
import com.example.photofeed.R
import com.example.photofeed.util.PermissionHelper
import com.google.android.material.button.MaterialButton

class GuideActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        PermissionHelper.markAsked(this)
        val granted = result.values.any { it } || PermissionHelper.hasAnyAccess(this)
        if (granted) {
            goMain()
        } else {
            showDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        val btnStart = findViewById<MaterialButton>(R.id.btnStart)
        val btnOpenSettings = findViewById<MaterialButton>(R.id.btnOpenSettings)
        val deniedText = findViewById<TextView>(R.id.deniedText)

        btnStart.setOnClickListener {
            if (PermissionHelper.hasAnyAccess(this)) {
                goMain()
            } else {
                requestPermissionsNow()
            }
        }

        btnOpenSettings.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            startActivity(intent)
        }

        if (PermissionHelper.hasAnyAccess(this)) {
            goMain()
        } else if (PermissionHelper.hasEverAsked(this)) {
            deniedText.visibility = View.VISIBLE
            btnOpenSettings.visibility = View.VISIBLE
        }
    }

    private fun requestPermissionsNow() {
        permissionLauncher.launch(PermissionHelper.requestedPermissions())
    }

    private fun showDenied() {
        val deniedText = findViewById<TextView>(R.id.deniedText)
        val btnOpenSettings = findViewById<MaterialButton>(R.id.btnOpenSettings)
        deniedText.visibility = View.VISIBLE
        btnOpenSettings.visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.btnStart)?.let {
            // Reuse the same button as "retry"
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
