package com.tiktokhd.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var bytesView: TextView
    private lateinit var progressBar: ProgressBar

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        bytesView = findViewById(R.id.bytes)
        progressBar = findViewById(R.id.progress)

        requestNotificationPermissionIfNeeded()
        observe()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val raw: String? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (raw == null) return

        val url = Downloader.extractTikTokUrl(raw)
        if (url == null) {
            DownloadState.set(Status.Error("No TikTok URL found in the shared text."))
            return
        }

        DownloadState.set(Status.ReceivingUrl)

        val service = Intent(this, DownloadService::class.java)
            .putExtra(DownloadService.EXTRA_URL, url)
        ContextCompat.startForegroundService(this, service)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadState.status.collect { render(it) }
            }
        }
    }

    private fun render(s: Status) {
        when (s) {
            is Status.Idle -> {
                statusView.text = "Waiting for a shared TikTok link."
                hideProgress()
            }
            is Status.ReceivingUrl -> {
                statusView.text = "Status:\nReceiving URL."
                indeterminate()
            }
            is Status.GettingLink -> {
                statusView.text = "Status:\nGetting HD link."
                indeterminate()
            }
            is Status.Downloading -> {
                statusView.text = "Status:\nDownloading."
                progressBar.visibility = View.VISIBLE
                bytesView.visibility = View.VISIBLE
                if (s.total > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = ((s.downloaded * 100) / s.total).toInt()
                    bytesView.text = String.format(
                        Locale.US, "%.1f MB / %.1f MB", mb(s.downloaded), mb(s.total)
                    )
                } else {
                    progressBar.isIndeterminate = true
                    bytesView.text = String.format(Locale.US, "%.1f MB", mb(s.downloaded))
                }
            }
            is Status.Completed -> {
                statusView.text = "Status:\nCompleted!\n\n${s.path}"
                hideProgress()
            }
            is Status.AlreadyExists -> {
                statusView.text = "Status:\nFile already exists.\n\n${s.path}"
                hideProgress()
            }
            is Status.Error -> {
                statusView.text = "Error: ${s.reason}"
                hideProgress()
            }
        }
    }

    private fun mb(bytes: Long): Double = bytes / 1024.0 / 1024.0

    private fun indeterminate() {
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        bytesView.visibility = View.GONE
    }

    private fun hideProgress() {
        progressBar.visibility = View.GONE
        bytesView.visibility = View.GONE
    }
}
