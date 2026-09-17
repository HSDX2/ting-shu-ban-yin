package com.audiobook.bgmusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.audiobook.bgmusic.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TrackAdapter

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                Prefs.folderUri = uri.toString()
                ServiceState.folderName.value = MusicLibrary.folderDisplayName(this, uri)
                scanFolder(uri)
                sendCommand(MusicSyncService.ACTION_RELOAD_FOLDER)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        ServiceState.syncEnabled.value = Prefs.syncEnabled
        ServiceState.whitelist.value = Prefs.whitelist.toList()
        ServiceState.folderName.value =
            Prefs.folderUri?.let { MusicLibrary.folderDisplayName(this, Uri.parse(it)) } ?: ""
        // 已有文件夹则立即扫描显示列表（不依赖服务）
        Prefs.folderUri?.let { scanFolder(Uri.parse(it)) }

        adapter = TrackAdapter(emptyList())
        binding.lvTracks.adapter = adapter
        binding.lvTracks.setOnItemClickListener { _, _, position, _ ->
            sendCommand(MusicSyncService.ACTION_SELECT_TRACK, MusicSyncService.EXTRA_INDEX to position)
        }

        binding.swSync.isChecked = Prefs.syncEnabled
        binding.swAutostart.isChecked = Prefs.autoStart
        binding.sbVolume.progress = (ServiceState.volume.value * 100).toInt()
        binding.etWhitelist.setText(Prefs.whitelist.joinToString(", "))

        binding.btnPickFolder.setOnClickListener { pickFolder.launch(null) }
        binding.btnStart.setOnClickListener {
            if (Prefs.folderUri == null) {
                binding.tvStatus.text = "请先选择音乐文件夹"
                return@setOnClickListener
            }
            startServiceIfNeeded()
        }
        binding.btnStop.setOnClickListener { MusicSyncService.stop(this) }
        binding.btnSaveWhitelist.setOnClickListener { saveWhitelist() }
        binding.btnNotifAccess.setOnClickListener { openNotificationAccessSettings() }
        binding.btnBattery.setOnClickListener { requestBatteryOptimization() }

        binding.swSync.setOnCheckedChangeListener { _, checked ->
            Prefs.syncEnabled = checked
            sendCommand(MusicSyncService.ACTION_SET_SYNC, MusicSyncService.EXTRA_SYNC to checked)
        }
        binding.swAutostart.setOnCheckedChangeListener { _, checked ->
            Prefs.autoStart = checked
        }
        binding.sbVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvVolumePct.text = "$progress%"
                if (fromUser) {
                    val v = progress / 100f
                    Prefs.volume = v
                    ServiceState.volume.value = v
                    sendCommand(
                        MusicSyncService.ACTION_SET_VOLUME,
                        MusicSyncService.EXTRA_VOLUME to v
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        observeState()
        requestNotificationPermission()
        updateNotifHint()
        updateBatteryHint()
    }

    override fun onResume() {
        super.onResume()
        updateNotifHint()
        updateBatteryHint()
        startServiceIfNeeded()
        // 用户可能刚在系统设置里授权了「通知使用权」，回来时让服务重新挂上监听
        if (hasNotifAccess() && ServiceState.serviceRunning.value) {
            sendCommand(MusicSyncService.ACTION_REFRESH_WATCHER)
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryHint() {
        val ignored = isBatteryOptimizationIgnored()
        binding.tvBatteryHint.visibility = if (ignored) View.GONE else View.VISIBLE
        binding.btnBattery.visibility = if (ignored) View.GONE else View.VISIBLE
    }

    private fun requestBatteryOptimization() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun hasNotifAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun updateNotifHint() {
        val granted = hasNotifAccess()
        binding.tvNotifHint.visibility = if (granted) View.GONE else View.VISIBLE
        binding.btnNotifAccess.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun openNotificationAccessSettings() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure {
            runCatching { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        }
    }

    private fun scanFolder(uri: Uri) {
        lifecycleScope.launch {
            ServiceState.scanning.value = true
            val tracks = MusicLibrary.scan(this@MainActivity, uri)
            ServiceState.folderName.value = MusicLibrary.folderDisplayName(this@MainActivity, uri)
            ServiceState.playlist.value = tracks
            ServiceState.scanning.value = false
        }
    }

    private fun applyWindowInsets() {
        // 浅色状态栏/导航栏图标（白色背景上显示深色图标）
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        // Android 15+ 强制 edge-to-edge：给根布局加上系统栏安全区内边距，避免被状态栏/导航栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun startServiceIfNeeded() {
        if (ServiceState.serviceRunning.value) return
        if (Prefs.folderUri == null) {
            return
        }
        MusicSyncService.start(this)
    }

    private fun sendCommand(action: String, vararg extras: Pair<String, Any?>) {
        if (!ServiceState.serviceRunning.value) return
        val intent = Intent(this, MusicSyncService::class.java).setAction(action)
        for ((k, v) in extras) {
            when (v) {
                is Boolean -> intent.putExtra(k, v)
                is Float -> intent.putExtra(k, v)
                is Int -> intent.putExtra(k, v)
            }
        }
        startService(intent)
    }

    private fun saveWhitelist() {
        val text = binding.etWhitelist.text?.toString()?.trim() ?: ""
        val list = text.split(',', '，', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        Prefs.whitelist = list.toSet()
        ServiceState.whitelist.value = list
        if (ServiceState.serviceRunning.value) {
            sendCommand(MusicSyncService.ACTION_REFRESH_WATCHER)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ServiceState.serviceRunning.collect { running ->
                        binding.btnStart.isEnabled = !running
                        binding.btnStop.isEnabled = running
                    }
                }
                launch {
                    ServiceState.syncEnabled.collect { checked ->
                        if (binding.swSync.isChecked != checked) binding.swSync.isChecked = checked
                    }
                }
                launch {
                    ServiceState.volume.collect { v ->
                        val p = (v * 100).toInt()
                        if (binding.sbVolume.progress != p) binding.sbVolume.progress = p
                    }
                }
                launch {
                    ServiceState.playlist.collect { list ->
                        adapter.clear()
                        adapter.addAll(list)
                        binding.tvListHeader.text = "音乐列表（${list.size} 首）"
                    }
                }
                launch {
                    ServiceState.scanning.collect { scanning ->
                        if (scanning) binding.tvListHeader.text = "正在扫描音乐…"
                    }
                }
                launch {
                    ServiceState.folderName.collect { name ->
                        binding.tvFolder.text = name.ifEmpty { "未选择" }
                    }
                }
                launch {
                    val core = combine(
                        ServiceState.serviceRunning,
                        ServiceState.syncEnabled,
                        ServiceState.externalPlaying,
                        ServiceState.externalAppName,
                        ServiceState.isMusicPlaying
                    ) { running, sync, external, app, music ->
                        arrayOf<Any?>(running, sync, external, app, music)
                    }
                    combine(core, ServiceState.syncPermissionGranted) { c, permission ->
                        statusText(
                            running = c[0] as Boolean,
                            sync = c[1] as Boolean,
                            external = c[2] as Boolean,
                            app = c[3] as String?,
                            music = c[4] as Boolean,
                            permission = permission
                        )
                    }.collect { text -> binding.tvStatus.text = text }
                }
            }
        }
    }

    private fun statusText(
        running: Boolean,
        sync: Boolean,
        external: Boolean,
        app: String?,
        music: Boolean,
        permission: Boolean
    ): String {
        return when {
            !running -> "服务未启动"
            !permission -> "已启动，但需开启「通知使用权」才能自动跟随"
            !sync -> "手动模式（未自动跟随）"
            external && music -> "跟随「${app ?: "其他应用"}」播放中"
            external && !music -> "跟随中（背景音乐已暂停）"
            else -> "服务运行中，等待其他应用播放…"
        }
    }

    private inner class TrackAdapter(items: List<Track>) :
        ArrayAdapter<Track>(this@MainActivity, R.layout.item_track, items.toMutableList()) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_track, parent, false)
            val t = getItem(position)
            v.findViewById<TextView>(R.id.tv_title).text = t?.name ?: ""
            v.findViewById<TextView>(R.id.tv_subtitle).text =
                t?.artist?.takeIf { it.isNotBlank() } ?: "未知艺术家"
            v.findViewById<TextView>(R.id.tv_duration).text = formatDuration(t?.durationMs ?: 0L)
            return v
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val total = ms / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }
}
