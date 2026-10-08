# navi-shell

一个 Android 导航 App。路线、转向、底图来自高德；界面和播报词表由本工程提供，播报的文本和音色接你自己的服务。

高德负责算路、转向判定、定位纠偏、测速提醒；语音和界面在这里接管。

## 特性

- **高德出数据，你出声音** — 关闭高德内置语音后，它通过 `onGetNavigationText` 交出播报原文，播报方式和音色由你决定
- **预录优先，实时兜底** — 固定句式走本地预录音频，几乎零延迟；长句和临时路况走后端实时合成
- **后端不可用不影响导航** — 算路、转向播报、测速提醒、出行记录全部本地完成，网络断开时导航照常
- **导航对话独立会话** — 使用独立 `chat_id`（`navi_<时间戳>`），不与日常对话混在一起
- **出行记录** — 到达终点后上报起终点、出发到达时间、里程、耗时；轨迹每 30 米记一个点
- **常用地点后端共享** — 手机和后端读写同一份，任一边修改另一边可见
- **导航期间前台服务** — 切走 App 或锁屏后继续播报转向

## 架构

```
   高德导航 SDK                          你的后端服务
┌──────────────────┐              ┌──────────────────┐
│ 算路 / 转向 / 定位 │              │ 语音识别          │
│ 底图（带高德标识） │              │ 对话生成          │
└────────┬─────────┘              │ 语音合成          │
         │                        └────────┬─────────┘
         │  setUseInnerVoice(false)       │
         │  → onGetNavigationText(文本)    │
         ▼                                ▼
   ┌────────────────────────────────────────────┐
   │      界面（Compose）+ 播报（你的音色）        │
   └────────────────────────────────────────────┘
```

接管播报靠两行：

```kotlin
AMapNavi.setUseInnerVoice(false)
AMapNavi.setBroadcastMode(AMapNavi.BroadcastMode.CONCISE)
```

关闭内置语音后，高德通过回调交出要播的文本：

```kotlin
override fun onGetNavigationText(type: Int, text: String) {
    // text 是高德生成的播报原文，例如「前方五百米左转」
}
```

转向播报、测速提醒、偏航重算的文本都走这里。

## 快速开始

需要 JDK 17、Android SDK（compileSdk 36）。Android Studio 或命令行构建都可以。

### 1. 申请高德 key

在[高德开放平台](https://lbs.amap.com/)创建应用，平台选 Android。需要填两项：

| 项 | 值 |
|---|---|
| 包名 | `com.navi.shell`（可以改成自己的，改完按新包名重新申请） |
| 签名 SHA1 | 自己证书的指纹，见下一步 |

### 2. 准备签名证书

高德 key 校验「包名 + 签名 SHA1」。debug 包用同一把证书签名，key 才能通过校验。

```bash
keytool -genkeypair -v -keystore keystore/release.jks \
        -alias release -keyalg RSA -keysize 2048 -validity 10000
keytool -list -v -keystore keystore/release.jks | grep SHA1
```

证书密码写在 `keystore/PASSWORD.txt`，单行、UTF-8 无 BOM。

### 3. 配置 local.properties

```bash
cp local.properties.example local.properties
```

四项：`sdk.dir`、`amap.key`、`app.base.url`、`app.auth.token`。该文件已在 `.gitignore` 中。

### 4. 构建

```bash
./gradlew assembleDebug         # 调试包
./gradlew assembleRelease       # 发布包
./gradlew testDebugUnitTest     # 本地自检，21 项
```

## API

本工程会调用下表这些接口。路径是当前使用的写法，可以按自己的服务端调整（见 `net/NaviApi.kt`）。

| 功能 | 路径 | 说明 |
|---|---|---|
| 换取会话凭证 | `GET /access` | 换取 serve 端会话 cookie |
| 语音识别 | `POST /asr` | multipart，字段名 `audio`，16k 单声道 WAV，返回 `{"text": "..."}` |
| 对话生成 | `POST /proxy_chat` | 发送消息，返回回复文本 |
| 语音合成 | `POST /tts` | 返回音频字节流，WAV 或 MP3 |
| 保存出行记录 | `POST /api/navi/trip` | 起终点、时间、里程、耗时、轨迹点 |
| 出行记录列表 | `GET /api/navi/trips` | 足迹页数据 |
| 单次轨迹 | `GET /api/navi/track/<id>` | 某次出行的轨迹点 |
| 全部路线 | `GET /api/navi/tracks` | 每次出行各一条，画「全部路线」 |
| 导航状态通知 | `POST /api/navi/mode` | 进入/退出导航时通知后端 |
| 常用地点 | `GET/POST/DELETE /api/navi/places` | 手机和后端共用一份 |

后端不可用时，导航、转向播报、测速提醒、出行记录功能均可继续使用。

## 目录结构

```
app/src/main/java/com/navi-shell/
  MainActivity.kt          隐私同意 → 权限 → 界面
  AmapPrivacy.kt           高德合规调用入口
  data/                    AuthStore / Models / PlaceStore
  net/NaviApi.kt           后端接口调用
  location/Locator.kt      一次性定位
  location/HeadingSensor.kt 方向传感器（GPS 航向在静止时不更新）
  navi/NaviController.kt   高德封装：算路 / 导航 / 转向 / 播报
  navi/TripRecorder.kt     出行记账（按实际行驶距离）
  navi/NaviService.kt      导航期间的前台服务
  audio/WavRecorder.kt     录音 → 16k WAV
  audio/VoicePlayer.kt     音频播放（预录 / 实时）
  voice/VoiceSession.kt    录音 → 识别 → 生成 → 播报
  voice/BroadcastScript.kt 预录词表匹配
  ui/UiColors.kt           界面配色
  ui/NaviScreen.kt         主界面
  ui/FootprintsPanel.kt    足迹页
res/values/strings.xml     ai_name 配置项，默认留空
res/drawable/              应用图标（占位）
tools/gen-voice.mjs        预录词表的唯一来源
```

## 配置

### 命名（`res/values/strings.xml`）

`ai_name` 默认为空，此时界面只显示播报内容：

```
对话气泡：   「前方三百米有测速」
导航通知：   「正在给你看着路」
隐私说明：   「说话的是你的 AI —— 录音会发给它；语音由它合成。」
```

填入名称后（例如「小助手」），三处显示同步变为「小助手 · 前方三百米有测速」
「小助手在给你看着路」。

### 界面

界面为最小功能布局，配色取值集中在 `ui/UiColors.kt`，替换该文件的色值即可换主题。

地图标记（当前位置、终点）使用高德默认样式，地图样式为默认，应用图标为占位图标
（`res/drawable/ic_launcher_foreground.xml`）。

### 播报音频（`tools/gen-voice.mjs`）

高德给出的文本先查预录词表，命中则播放本地音频，未命中则调用实时合成接口。
转向播报有明确的时间窗口，固定句式走预录可以省掉合成往返的时间。

词表定义在 `tools/gen-voice.mjs`，这是词表的唯一来源。仓库内保存生成好的清单
`phrases.json`（单元测试用它校验词表），音频文件自行生成：

```bash
node tools/gen-voice.mjs --dry             # 列出将生成的内容，不联网
node tools/gen-voice.mjs --manifest-only   # 只写清单，不生成音频

# 生成音频，会调用后端服务的合成接口
export VOICE_TTS_BASE='https://your-host'   # Windows: $env:VOICE_TTS_BASE='...'
export VOICE_TTS_TOKEN='...'                # 无鉴权可留空
node tools/gen-voice.mjs
```

## 已知问题

以下五点在编译期不会报错，是在实际调试中定位到的。

**① 高德的 Android 组件需要手写进清单。**

`com.amap.api:navi-3dmap-location-search` 是 jar 包，不含 `AndroidManifest.xml`，
清单合并取不到组件声明。缺这几行时定位服务启动失败，logcat 报
`Unable to start service ... com.amap.api.location.APSService ... not found`，
界面停在「正在定位…」。需要声明 `APSService`、`AmapRouteActivity`、
`RideAccountLoginActivity`、`JsBridgeActivity`、`OfflineMapActivity`，
`app/src/main/AndroidManifest.xml` 中已包含。

**② debug 包使用正式证书签名。**

见「准备签名证书」。签名不一致时 key 校验失败，界面表现为地图空白。

**③ 证书密码文件保存为不带 BOM 的 UTF-8。**

部分编辑器和 PowerShell 的 `Set-Content -Encoding utf8` 会写入 UTF-8 BOM，
JVM 读到的首个字符是 `\uFEFF`，密码校验失败并报
`KeytoolException: keystore password was incorrect`。
读取时可用 `readText().trim().trimStart('\uFEFF').trim()`。

**④ 搜索目的地时传入城市。**

`GeocodeQuery(名称, city)` 的 city 传空串会在全国范围匹配，可能命中几百公里外的同名地点，
路线摘要显示「全程 621.8 公里」。定位回调中记录 `AMapLocation.getCity()`，
搜索时一并传入即可。

**⑤ 高德 SDK 中包含旧版 Support Library 引用。**

工程使用 AndroidX，SDK 内部引用 `android.support.v4.*`，调用时抛
`NoClassDefFoundError`。`gradle.properties` 中的 `android.enableJetifier=true`
会在构建时完成引用改写。

## License

MIT，见 `LICENSE`。

使用前请阅读高德开放平台的授权条款。底图上的高德标识保持可见，
申请的 key 与自己的包名对应。
