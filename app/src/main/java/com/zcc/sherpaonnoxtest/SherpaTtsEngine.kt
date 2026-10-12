package com.zcc.sherpaonnoxtest

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局共用的 sherpa-onnx 离线语音合成（TTS）引擎。
 *
 * 「朗读」和「常用语」两个页面共用同一个实例，模型只在首次调用 [init] 时加载一次，
 * 避免各页面重复加载上百 MB 的模型。
 *
 * 合成用 [OfflineTts]，播放用 [AudioTrack]（静态模式，PCM float）。
 */
object SherpaTtsEngine {

    /**
     * TTS 模型目录名（位于 assets 下）。换模型时改这里，并在 assets 放入对应文件。
     * 当前：matcha-icefall-zh-en（中英混读，单音色，16kHz，声学模型约 79MB + 声码器约 51MB）。
     */
    const val MODEL_DIR = "matcha-icefall-zh-en"

    /** Matcha 声码器文件名，需放在 assets 根目录（不在 [MODEL_DIR] 下）。 */
    private const val VOCODER_FILE = "vocos-16khz-univ.onnx"

    /** 文本归一化规则候选（数字/日期/电话），按在模型目录中是否存在自动启用。 */
    private val RULE_FST_CANDIDATES = listOf(
        "phone-zh.fst", "date-zh.fst", "number-zh.fst",
        "phone.fst", "date.fst", "number.fst",
    )

    /**
     * espeak-ng-data 在 assets 中的相对路径。
     * 它是目录，espeak-ng 只能读文件系统，故运行时须先复制到应用私有目录再交给引擎。
     */
    private const val ESPEAK_ASSET_DIR = "$MODEL_DIR/espeak-ng-data"

    /** 复制完成后用于判断数据目录是否就绪的哨兵文件。 */
    private const val ESPEAK_SENTINEL = "phontab"

    enum class State { IDLE, LOADING, READY, FAILED }

    interface Callback {
        fun onStart()
        fun onDone()
        fun onError(message: String)
    }

    @Volatile
    var state: State = State.IDLE
        private set

    /** 当前模型支持的说话人（音色）数量，至少为 1。 */
    @Volatile
    var numSpeakers: Int = 1
        private set

    var sampleRate: Int = 0
        private set

    /** 朗读用的音色下标与语速，两个页面共用。 */
    @Volatile
    var speakerId: Int = 0
    @Volatile
    var speed: Float = 1.0f

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var track: AudioTrack? = null

    /** 每次 [speak] 递增；[stop] 也会递增，用于让正在播放的线程自行退出。 */
    private val generation = AtomicInteger(0)

    @Volatile
    private var initialized = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    fun addStateListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeStateListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    private fun setState(newState: State) {
        state = newState
        mainHandler.post { listeners.toList().forEach { it(newState) } }
    }

    /** 首次调用时在子线程加载模型；之后重复调用直接返回。 */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        setState(State.LOADING)
        val appContext = context.applicationContext
        Thread {
            try {
                val assets = appContext.assets
                // espeak-ng-data 是目录，无法从 assets 直接读取，先复制到应用私有目录
                val dataDir = ensureEspeakDataDir(appContext)
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        matcha = OfflineTtsMatchaModelConfig(
                            acousticModel = "$MODEL_DIR/model-steps-3.onnx",
                            vocoder = VOCODER_FILE,
                            lexicon = "$MODEL_DIR/lexicon.txt",
                            tokens = "$MODEL_DIR/tokens.txt",
                            dataDir = dataDir,
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                    ruleFsts = existingRuleFsts(assets, MODEL_DIR),
                )
                val engine = OfflineTts(assetManager = assets, config = config)
                numSpeakers = engine.numSpeakers().coerceAtLeast(1)
                sampleRate = engine.sampleRate()
                speakerId = speakerId.coerceIn(0, numSpeakers - 1)
                tts = engine
                setState(State.READY)
            } catch (e: Exception) {
                setState(State.FAILED)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 收集模型目录中实际存在的文本归一化规则（数字/日期/电话），不存在就跳过。 */
    private fun existingRuleFsts(assets: AssetManager, dir: String): String =
        RULE_FST_CANDIDATES
            .filter { assetExists(assets, "$dir/$it") }
            .joinToString(",") { "$dir/$it" }

    private fun assetExists(assets: AssetManager, path: String): Boolean =
        try {
            assets.open(path).close()
            true
        } catch (_: Exception) {
            false
        }

    /**
     * 确保 espeak-ng-data 已从 assets 复制到应用私有目录，返回其绝对路径。
     * 已复制过（存在哨兵文件）则直接复用，避免每次启动重复拷贝约 17MB。
     */
    private fun ensureEspeakDataDir(context: Context): String {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dest = File(root, ESPEAK_ASSET_DIR)
        if (File(dest, ESPEAK_SENTINEL).exists()) return dest.absolutePath
        dest.deleteRecursively()
        copyAssetDir(context.assets, ESPEAK_ASSET_DIR, dest)
        return dest.absolutePath
    }

    /** 递归复制 assets 中的文件或目录到 [dest]（assets 里的目录无法被原生库直接读取）。 */
    private fun copyAssetDir(assets: AssetManager, assetPath: String, dest: File) {
        val children = assets.list(assetPath)
        if (!children.isNullOrEmpty()) {
            dest.mkdirs()
            for (child in children) copyAssetDir(assets, "$assetPath/$child", File(dest, child))
            return
        }
        try {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        } catch (_: FileNotFoundException) {
            // 空目录：当作目录创建即可
            dest.mkdirs()
        }
    }

    /** 合成并播放 [text]，会打断上一次朗读。回调都在主线程。 */
    fun speak(text: String, callback: Callback) {
        val engine = tts ?: run {
            mainHandler.post { callback.onError("engine not ready") }
            return
        }

        val myGeneration = generation.incrementAndGet()
        stopCurrentTrack()

        Thread {
            var player: AudioTrack? = null
            try {
                val audio = engine.generate(text, speakerId, speed)
                val samples = audio.samples
                if (samples.isEmpty() || myGeneration != generation.get()) {
                    mainHandler.post { callback.onDone() }
                    return@Thread
                }

                player = buildTrack(audio.sampleRate, samples.size)
                player.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                if (myGeneration != generation.get()) return@Thread

                synchronized(this) { if (myGeneration == generation.get()) track = player }
                mainHandler.post { if (myGeneration == generation.get()) callback.onStart() }
                player.play()

                // MODE_STATIC：等播放帧数追上总帧数
                while (myGeneration == generation.get() &&
                    player.playbackHeadPosition < samples.size
                ) {
                    Thread.sleep(30L)
                }
                // 留一点尾巴，避免最后一个音节被切掉
                if (myGeneration == generation.get()) Thread.sleep(120L)
                mainHandler.post { if (myGeneration == generation.get()) callback.onDone() }
            } catch (e: Exception) {
                mainHandler.post { if (myGeneration == generation.get()) callback.onError(e.message ?: "error") }
            } finally {
                try {
                    player?.stop()
                } catch (_: Exception) {
                }
                try {
                    player?.release()
                } catch (_: Exception) {
                }
                synchronized(this) { if (track === player) track = null }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 停止当前朗读。 */
    fun stop() {
        generation.incrementAndGet()
        stopCurrentTrack()
    }

    @Synchronized
    private fun stopCurrentTrack() {
        val player = track ?: return
        track = null
        try {
            player.stop()
        } catch (_: Exception) {
        }
        try {
            player.release()
        } catch (_: Exception) {
        }
    }

    private fun buildTrack(sampleRate: Int, frameCount: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(frameCount * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
}
