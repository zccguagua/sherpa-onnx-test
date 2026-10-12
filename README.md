# sherpa-onnx 语音测试

一个纯离线（无需联网）的语音小应用，**语音合成（TTS）和语音识别（ASR）全部使用开源框架
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**。项目从 `TtsTest` 提取而来，去掉了
系统 TTS 引擎、拍照识别（OCR）和悬浮窗朗读，只保留三个页面：

- **朗读**：输入文字，用 sherpa-onnx 离线合成并朗读，可调速（多说话人模型还可切换音色）。
- **语音识别**：按住说话、松开识别，用 sherpa-onnx 的 SenseVoice 模型离线识别，保留识别历史。
- **常用语**：点击即朗读（复用同一套 TTS 模型），支持拖拽排序、侧滑删除、新增。

## 目录结构

```
app/src/main/
├── assets/                       # 离线模型（体积大，未入库，需自行下载）
│   ├── matcha-icefall-zh-en/                         # TTS 声学模型（Matcha-TTS）
│   ├── vocos-16khz-univ.onnx                          # TTS 声码器（Matcha 必需，放 assets 根目录）
│   └── sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/   # ASR 模型
├── java/com/zcc/sherpaonnoxtest/
│   ├── MainActivity.kt           # 底部导航 + 3 个 Fragment
│   ├── SherpaTtsEngine.kt        # 全局 TTS 引擎（合成 + AudioTrack 播放）
│   ├── TtsFragment.kt            # 「朗读」页
│   ├── SpeechFragment.kt         # 「语音识别」页（ASR）
│   ├── PhrasesFragment.kt        # 「常用语」页
│   └── PhraseAdapter.kt          # 常用语列表适配器
└── res/                          # 布局 / 颜色 / 图标
```

## 依赖

sherpa-onnx 以本地 AAR 形式引入：`app/libs/sherpa-onnx-1.13.8.aar`（同时提供 ASR 与 TTS 的
Java/Kotlin API）。`app/build.gradle.kts` 里通过 `implementation(files("libs/sherpa-onnx-1.13.8.aar"))`
引用，避免从 JitPack 拉取失败。

## 模型（必读）

TTS 和 ASR 是**两套独立模型，互不通用**，都需要下载后放到 `app/src/main/assets/` 下。
仓库不包含模型文件（体积过大）。用附带脚本下载：

```bash
# 只下载 TTS 模型（默认）
python download_models.py
# 只下载 ASR 模型（如果还没有）
python download_models.py --asr
# 两个都下载
python download_models.py --all
```

也可以手动从 GitHub Releases 下载对应的 `.tar.bz2`，解压到 `app/src/main/assets/`：
<https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models>（TTS）
<https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models>（ASR）

### 默认 TTS 模型：`matcha-icefall-zh-en`

- 基于 Matcha-TTS，**中英混读**，单音色，16kHz，声学模型约 79MB。
- 需要的文件：`model-steps-3.onnx`、`lexicon.txt`、`tokens.txt`，以及可选的
  `phone-zh.fst` / `date-zh.fst` / `number-zh.fst`（数字、日期读法；存在才启用，缺失自动忽略）。
- **需要额外的声码器** `vocos-16khz-univ.onnx`（约 51MB），放在 assets 根目录，
  而不是模型目录内；缺少它会导致加载失败。下载脚本会自动处理。
- 模型目录内的 `espeak-ng-data/`（英文音素化，约 17MB）无法从 assets 直接读取，
  App 首次启动时会自动复制到应用私有目录后再加载。

### 想换别的 TTS 模型？

改 `SherpaTtsEngine.kt` 里的 `MODEL_DIR` 常量，并在 assets 放入同名目录即可。常用备选：

| 模型 | 大小 | 音色数 | 采样率 | 特点 |
|---|---|---|---|---|
| `matcha-icefall-zh-en` | ~79MB + 声码器 ~51MB | 1 | 16kHz | 默认，中英混读，需配声码器 |
| `sherpa-onnx-vits-zh-ll` | ~115MB | 5 | 16kHz | 中文，多音色 |
| `vits-icefall-zh-aishell3` | ~29MB | 174 | 8kHz | 最小、音色最多，音质偏低 |
| `vits-melo-tts-zh_en` | ~167MB | 1 | 44.1kHz | 中文+英文，音质好，单音色 |

> 注意：不同模型的配置方式不同。VITS 用 `vits = OfflineTtsVitsModelConfig(...)`；Matcha 用
> `matcha = OfflineTtsMatchaModelConfig(...)` 且必须指定声码器；部分模型还需把 `espeak-ng-data`
> 或 `dict/` 等目录复制出 assets。内部文件名也可能不同（如 `model.onnx` vs `model-steps-3.onnx`），
> 换模型时需同步调整 `SherpaTtsEngine`。

### 默认 ASR 模型

`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`（int8 量化，`model.int8.onnx`
约 239MB），支持中 / 英 / 日 / 韩 / 粤语。

## 构建与运行

- Kotlin + XML 布局，minSdk 24 / targetSdk 36，AGP 8.13.0 / Kotlin 2.1.21。
- 用 Android Studio 直接 Run；或命令行：

```bash
./gradlew installDebug
```

> 首次构建需联网下载 Gradle 依赖。装好模型后 debug 安装包体积会明显增大
> （ASR 约 240MB + TTS 约 130MB）。

## 与 TtsTest 的关系

`TtsTest` 是原项目（系统 TTS + sherpa-onnx ASR），本项目是独立副本，只共用思路与部分代码，
两者互不影响。
