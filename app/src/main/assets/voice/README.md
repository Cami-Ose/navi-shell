# 预录播报

本目录保存词表清单 `phrases.json`（6 KB 文本）。
音频文件由使用者自行生成，仓库内不包含（133 个文件、25 MB）。

## 预录的用途

转向播报有明确的时间窗口。「前方三百米左转」若走云端合成，音频返回时路口可能已经过去。

因此固定句式使用预录音频，命中后本地播放。长句和临时路况（施工、拥堵）走实时合成接口。

## 修改词表

`phrases.json` 由脚本生成。词表定义在 `tools/gen-voice.mjs`，修改词表时改该脚本后重新生成。

```bash
node tools/gen-voice.mjs --dry             # 列出将生成的内容，不联网
node tools/gen-voice.mjs --manifest-only   # 只重写 phrases.json
node tools/gen-voice.mjs                   # 生成音频
```

生成音频会调用后端服务的换取凭证和语音合成接口：

```bash
export VOICE_TTS_BASE='https://your-host'   # Windows: $env:VOICE_TTS_BASE='...'
export VOICE_TTS_TOKEN='...'                # 无鉴权可留空
node tools/gen-voice.mjs
```

生成的文件形如 `left_m500.wav`，后缀按接口返回的实际格式确定，`.wav` 和 `.mp3` 均可识别。

## 音频文件缺失时

`BroadcastScript` 能查到词表条目但打不开音频文件时，该句转为实时合成。
导航功能正常，播报延迟略有增加。

单元测试只依赖 `phrases.json`，克隆后可直接运行 `gradlew testDebugUnitTest`。
