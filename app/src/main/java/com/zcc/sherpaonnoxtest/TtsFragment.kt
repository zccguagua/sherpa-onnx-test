package com.zcc.sherpaonnoxtest

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import android.content.Context

/**
 * 朗读：sherpa-onnx 离线合成（多音色可选）+ AudioTrack 播放。
 */
class TtsFragment : Fragment() {

    private lateinit var etInput: EditText
    private lateinit var sbSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var sbSpeaker: SeekBar
    private lateinit var tvSpeaker: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnSpeak: Button
    private lateinit var btnStop: Button

    private val stateListener: (SherpaTtsEngine.State) -> Unit = { updateForState(it) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_tts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        etInput = view.findViewById(R.id.etInput)
        sbSpeed = view.findViewById(R.id.sbSpeed)
        tvSpeed = view.findViewById(R.id.tvSpeed)
        sbSpeaker = view.findViewById(R.id.sbSpeaker)
        tvSpeaker = view.findViewById(R.id.tvSpeaker)
        tvStatus = view.findViewById(R.id.tvStatus)
        btnSpeak = view.findViewById(R.id.btnSpeak)
        btnStop = view.findViewById(R.id.btnStop)

        btnSpeak.isEnabled = false
        btnStop.isEnabled = false

        // 语速：0~100 → 0.1~2.0（50 对应 1.0）
        sbSpeed.max = 100
        sbSpeed.progress = DEFAULT_SPEED_PROGRESS
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = rateOf(progress)
                tvSpeed.text = getString(R.string.speed_value, rate)
                SherpaTtsEngine.speed = rate
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        tvSpeed.text = getString(R.string.speed_value, rateOf(DEFAULT_SPEED_PROGRESS))

        // 音色：按下标选择，模型就绪后按实际音色数设置
        sbSpeaker.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                SherpaTtsEngine.speakerId = progress
                updateSpeakerLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateSpeakerLabel(0)

        btnSpeak.setOnClickListener { speak() }
        btnStop.setOnClickListener {
            SherpaTtsEngine.stop()
            tvStatus.text = getString(R.string.status_ready)
        }

        // 点击输入框以外区域时收起软键盘
        view.findViewById<View>(R.id.ttsRoot).setOnTouchListener { _, event ->
            dismissKeyboardOnOutsideTap(event)
            false
        }

        // 加载模型（若「常用语」页已在加载则复用），并监听状态
        SherpaTtsEngine.init(requireContext().applicationContext)
        SherpaTtsEngine.addStateListener(stateListener)
    }

    override fun onDestroyView() {
        SherpaTtsEngine.removeStateListener(stateListener)
        SherpaTtsEngine.stop()
        super.onDestroyView()
    }

    // ------------------------------------------------------------ 状态

    private fun updateForState(state: SherpaTtsEngine.State) {
        when (state) {
            SherpaTtsEngine.State.IDLE, SherpaTtsEngine.State.LOADING -> {
                tvStatus.text = getString(R.string.status_loading_model)
                btnSpeak.isEnabled = false
                btnStop.isEnabled = false
                sbSpeaker.isEnabled = false
            }

            SherpaTtsEngine.State.READY -> {
                tvStatus.text = getString(R.string.status_ready)
                btnSpeak.isEnabled = true
                btnStop.isEnabled = true
                configureSpeakerBar()
            }

            SherpaTtsEngine.State.FAILED -> {
                tvStatus.text = getString(R.string.status_init_failed)
                btnSpeak.isEnabled = false
                btnStop.isEnabled = false
                sbSpeaker.isEnabled = false
            }
        }
    }

    private fun configureSpeakerBar() {
        val count = SherpaTtsEngine.numSpeakers
        sbSpeaker.max = (count - 1).coerceAtLeast(0)
        val current = SherpaTtsEngine.speakerId.coerceIn(0, (count - 1).coerceAtLeast(0))
        sbSpeaker.progress = current
        SherpaTtsEngine.speakerId = current
        sbSpeaker.isEnabled = count > 1
        updateSpeakerLabel(current)
    }

    private fun updateSpeakerLabel(index: Int) {
        val count = SherpaTtsEngine.numSpeakers.coerceAtLeast(1)
        tvSpeaker.text = getString(R.string.speaker_value, index + 1, count)
    }

    // ------------------------------------------------------------ 朗读

    private fun speak() {
        if (SherpaTtsEngine.state != SherpaTtsEngine.State.READY) return

        val text = etInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), R.string.toast_empty, Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = getString(R.string.status_synthesizing)
        SherpaTtsEngine.speak(text, object : SherpaTtsEngine.Callback {
            override fun onStart() {
                if (isAdded) tvStatus.text = getString(R.string.status_speaking)
            }

            override fun onDone() {
                if (isAdded) tvStatus.text = getString(R.string.status_ready)
            }

            override fun onError(message: String) {
                if (isAdded) tvStatus.text = getString(R.string.status_error)
            }
        })
    }

    // ------------------------------------------------------------ 软键盘

    private fun dismissKeyboardOnOutsideTap(ev: MotionEvent) {
        if (ev.action != MotionEvent.ACTION_DOWN) return
        val focused = activity?.currentFocus
        if (focused is EditText) {
            val rect = Rect()
            focused.getGlobalVisibleRect(rect)
            if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                focused.clearFocus()
                val imm = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(focused.windowToken, 0)
            }
        }
    }

    private companion object {
        const val DEFAULT_SPEED_PROGRESS = 50

        /** 进度条 0~100 映射为语速 0.1~2.0（50 对应正常语速 1.0） */
        fun rateOf(progress: Int): Float = (progress / 50f).coerceAtLeast(0.1f)
    }
}
