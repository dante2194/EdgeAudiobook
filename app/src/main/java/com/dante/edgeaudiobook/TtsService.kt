package com.dante.edgeaudiobook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class St { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class JobItem(
    val name: String,
    val fileName: String,
    val text: String,
    val status: St = St.QUEUED,
    val progress: Float = 0f,
    val note: String = "",
    val outUri: String? = null
)

data class JobConfig(
    val voice: String,
    val rate: Int,
    val pitch: Int,
    val readTitle: Boolean,
    val treeUri: String?,
    val folder: String,
    val rules: List<PronRule> = emptyList(),
    val builtinRefs: Boolean = true,
    val emph: Emph = Emph(),
    val skipRefs: Boolean = false,
    val skipSuper: Boolean = false,
    val cover: ByteArray? = null,
    val artist: String = "",
    val album: String = "",
    val parallel: Int = 3
)

object Jobs {
    val items = MutableStateFlow<List<JobItem>>(emptyList())
    val running = MutableStateFlow(false)
    @Volatile var config: JobConfig? = null
    /** True until the queue definition (texts + settings) has been written to disk. */
    @Volatile var defDirty = false

    fun submit(list: List<JobItem>, cfg: JobConfig) {
        config = cfg
        defDirty = true
        items.value = list
    }

    /** Puts failed / interrupted chapters back in the queue; finished ones stay done. */
    fun requeueFailed(): Int {
        var n = 0
        items.update { l ->
            n = l.count { it.status == St.FAILED || it.status == St.CANCELLED }
            l.map {
                if (it.status == St.FAILED || it.status == St.CANCELLED) it.copy(status = St.QUEUED, progress = 0f, note = "")
                else it
            }
        }
        return n
    }

    fun update(i: Int, f: (JobItem) -> JobItem) {
        items.update { l ->
            if (i !in l.indices) l else l.toMutableList().also { it[i] = f(it[i]) }
        }
    }
}

class TtsService : Service() {
    companion object {
        const val ACTION_STOP = "com.dante.edgeaudiobook.STOP"
        private const val CH = "generation"
        private const val NID = 1
        private const val NID_DONE = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var stateJob: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Audio generation", NotificationManager.IMPORTANCE_LOW)
        )
        // Keep a copy of every chapter's status on disk so a killed app can pick up again.
        stateJob = scope.launch {
            Jobs.items.sample(1000).collect {
                try { JobStore.saveState(applicationContext, it) } catch (_: Exception) {}
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (job?.isActive == true) job?.cancel() else stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NID, buildNotif("Edge Audiobook", "Starting…", -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (job?.isActive != true) job = scope.launch { runQueue() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        stateJob?.cancel()
        releaseWake()
        super.onDestroy()
    }

    private fun releaseWake() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
    }

    private fun buildNotif(title: String, text: String, progress: Int): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .apply { if (progress >= 0) setProgress(100, progress, false) }
            .build()
    }

    private fun post(title: String, text: String, progress: Int) {
        getSystemService(NotificationManager::class.java).notify(NID, buildNotif(title, text, progress))
    }

    private suspend fun runQueue() {
        val cfg = Jobs.config
        if (cfg == null) { stopSelf(); return }
        if (Jobs.defDirty) {
            try {
                JobStore.saveDef(applicationContext, Jobs.items.value, cfg)
                Jobs.defDirty = false
            } catch (_: Exception) {}
        }
        Jobs.running.value = true
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "edgeaudiobook:generate")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        try {
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "edgeaudiobook:wifi")
                .apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}

        val total = Jobs.items.value.size
        var ok = 0
        try {
            for (i in 0 until total) {
                val cur = Jobs.items.value[i]
                if (cur.status == St.DONE) { ok++; continue }   // finished in an earlier run
                cur.outUri?.let { Sinks.deleteQuietly(applicationContext, it) }   // half-written file from a crash
                Jobs.update(i) { it.copy(status = St.RUNNING, progress = 0f, note = "", outUri = null) }
                post("Chapter ${i + 1} of $total", cur.name, 0)
                try {
                    convert(i, total, cur, cfg)
                    Jobs.update(i) { it.copy(status = St.DONE, progress = 1f, note = "") }
                    ok++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Jobs.update(i) { it.copy(status = St.FAILED, note = e.message ?: e.javaClass.simpleName) }
                }
            }
        } finally {
            withContext(NonCancellable) {
                Jobs.items.update { l ->
                    l.map { if (it.status == St.QUEUED || it.status == St.RUNNING) it.copy(status = St.CANCELLED) else it }
                }
                Jobs.running.value = false
                try { JobStore.saveState(applicationContext, Jobs.items.value) } catch (_: Exception) {}
                releaseWake()
                stopForeground(STOP_FOREGROUND_REMOVE)
                val open = PendingIntent.getActivity(
                    this@TtsService, 2, Intent(this@TtsService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                val n = NotificationCompat.Builder(this@TtsService, CH)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("Edge Audiobook")
                    .setContentText("$ok of $total chapters saved")
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
                getSystemService(NotificationManager::class.java).notify(NID_DONE, n)
                stopSelf()
            }
        }
    }

    private suspend fun convert(idx: Int, total: Int, item: JobItem, cfg: JobConfig) {
        val ctx = applicationContext
        val paragraphs = Pron.dropSections(TextOps.speechParagraphs(item.text, cfg.emph.skipForeign), cfg.rules)
        val body = (if (cfg.readTitle) listOf(item.name.trim().trimEnd('.') + ".") else emptyList()) + paragraphs
        val pr = Pronouncer(cfg.rules, cfg.builtinRefs, cfg.skipRefs, cfg.skipSuper)
        val chunks = TextOps.balance(TextOps.chunkText(body.map { pr.apply(it) }))
        if (chunks.isEmpty()) error("Nothing readable in this range (non-Latin text is skipped — see Speech tab)")

        // Pieces finished in an earlier attempt are picked up from the cache instead of the network.
        val keys = chunks.map { ChunkCache.key(it, cfg) }
        val cached = keys.count { ChunkCache.has(ctx, it) }
        Jobs.update(idx) {
            it.copy(note = if (cached > 0) "Resuming — $cached of ${chunks.size} pieces already saved" else "")
        }

        val sink = Sinks.open(ctx, cfg.treeUri, cfg.folder, item.fileName)
        Jobs.update(idx) { it.copy(outUri = sink.uri?.toString()) }
        var success = false
        try {
            sink.stream.write(
                Id3.build(item.name, cfg.album.ifBlank { cfg.folder }, cfg.artist, idx + 1, total, cfg.cover)
            )
            val gate = kotlinx.coroutines.sync.Semaphore(cfg.parallel.coerceIn(1, 8))
            kotlinx.coroutines.coroutineScope {
                val parts = chunks.mapIndexed { k, chunk ->
                    async {
                        // Each piece is saved the moment it arrives, so a failure elsewhere keeps it.
                        ChunkCache.get(ctx, keys[k]) ?: gate.withPermit {
                            EdgeTts.speak(chunk, cfg.voice, cfg.rate, cfg.pitch, cfg.emph)
                                .also { ChunkCache.put(ctx, keys[k], it) }
                        }
                    }
                }
                parts.forEachIndexed { k, d ->
                    sink.stream.write(d.await())
                    val p = (k + 1f) / chunks.size
                    Jobs.update(idx) { it.copy(progress = p) }
                    post("Chapter ${idx + 1} of $total", item.name, (p * 100).toInt())
                }
            }
            success = true
        } finally {
            sink.close(success)   // a failed chapter never leaves a half file behind
            Jobs.update(idx) { it.copy(outUri = null) }
        }
        ChunkCache.remove(ctx, keys)   // chapter saved: its cached pieces are no longer needed
    }
}
