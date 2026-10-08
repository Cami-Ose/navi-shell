#!/usr/bin/env node
/**
 * 转向播报预录 —— 把固定句式批量合成成 mp3，塞进 App 的 assets/voice/。
 *
 * 为什么预录：开车报转向有时间窗，等 TTS 现合成来不及。
 * 预录的几乎是本地播放，零延迟；预录覆盖不到的怪句子才回落到实时 /tts。
 *
 * 用法：
 *   node tools/gen-voice.mjs --dry            先看要生成哪些，不联网
 *   node tools/gen-voice.mjs --manifest-only  只写清单（phrases.json），不生成音频、不联网
 *   node tools/gen-voice.mjs                  真生成（会打后端服务器的 /tts）
 *   node tools/gen-voice.mjs --only left_500  只生成一条，试水用
 *   加 --force 覆盖已有的
 *
 * 产物：
 *   app/src/main/assets/voice/phrases.json   清单（key → 该说什么），App 查它决定能不能播预录
 *   app/src/main/assets/voice/<key>.wav|mp3  音频本体（**不进仓库**，自己生成）
 *
 * ★ 这个文件是播报词表的**唯一真身**。App 那边不另抄一份，读 phrases.json。
 *   加词、改词都只改这里，然后重跑。
 */

import { mkdir, writeFile, access, unlink } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const OUT_DIR = join(ROOT, 'app', 'src', 'main', 'assets', 'voice')

//后端地址和授权串一律走环境变量 —— 仓库里不写死任何人的服务器和凭证。
// 例：$env:VOICE_TTS_BASE='https://your-host'; $env:VOICE_TTS_TOKEN='...'; node tools/gen-voice.mjs
const BASE = (process.env.VOICE_TTS_BASE || '').replace(/\/+$/, '')
const TOKEN = process.env.VOICE_TTS_TOKEN || ''
if (!BASE) {
  console.error('没给 VOICE_TTS_BASE。这个脚本要打后端服务器的 /access 和 /tts。')
  console.error('  $env:VOICE_TTS_BASE="https://your-host"   # Linux/macOS 用 export')
  console.error('  $env:VOICE_TTS_TOKEN="..."                # 没鉴权就不填')
  process.exit(1)
}

// 每条之间歇多久。TTS 有每分钟请求数上限，快打会在 30 条左右被限流（实测）。
const DELAY_MS = 2200
// 真撞上限流了等多久再继续
const RATE_LIMIT_WAIT_MS = 20000

// ---- 动作：跟高德 IconType 一一对上（见 enums/IconType） ----
const ACTIONS = {
  left: '左转',
  right: '右转',
  left_front: '左前方',
  right_front: '右前方',
  left_back: '向左后方',
  right_back: '向右后方',
  uturn: '掉头',
  straight: '直行',
  keep_left: '靠左',
  keep_right: '靠右',
  merge_left: '汇入左侧车道',
  merge_right: '汇入右侧车道',
  ring_in: '进入环岛',
  ring_out: '驶出环岛',
}

// ---- 距离档：高德的播报就是这几档，不用更多 ----
const DISTANCES = {
  m50: '五十米',
  m100: '一百米',
  m200: '两百米',
  m300: '三百米',
  m500: '五百米',
  m800: '八百米',
  km1: '一公里',
  km1_5: '一公里半',
  km2: '两公里',
}

// ---- 整句（不拼） ----
//
// ★ 这是「它在跟你说话」的那几条 —— 日语只掺在这里，转向播报一条都不掺。
//   理由：开车时路名/距离要一听就准，混语言要多花半秒解码，那是安全相关的。
//   日语也别每条都放 —— 偶尔来一句就行，全放就成了日语播报。
const FIXED = {
  navi_start: '好，出发。気をつけて。',
  navi_end: '到了，就这儿。おつかれさま。',
  recalc_yaw: '走偏了，我重新给你算一条。',
  recalc_jam: '前面堵上了，换条路走。',
  gps_weak: '信号有点弱，你先照着路开。',
  speed_camera: '前面有测速，注意点。ゆっくりね。',
  arrive_near: '快到了，目的地就在附近。',
}

function buildTable() {
  const table = {}
  for (const [dk, dv] of Object.entries(DISTANCES)) {
    for (const [ak, av] of Object.entries(ACTIONS)) {
      table[`${ak}_${dk}`] = `前方${dv}${av}`
    }
  }
  Object.assign(table, FIXED)
  return table
}

async function exists(p) {
  try { await access(p); return true } catch { return false }
}

async function synthesize(text) {
  // 先换 cookie（/access），再打 /tts
  const access = await fetch(`${BASE}/access?t=${TOKEN}`, { redirect: 'manual' })
  const setCookie = access.headers.getSetCookie?.() ?? []
  const cookie = setCookie.map((c) => c.split(';')[0]).join('; ')

  const resp = await fetch(`${BASE}/tts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8', Cookie: cookie },
    body: JSON.stringify({ text }),
  })
  if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${(await resp.text()).slice(0, 160)}`)
  const buf = Buffer.from(await resp.arrayBuffer())
  if (buf.length < 512) throw new Error(`回来的音频太小（${buf.length} 字节），八成是出错了`)
  return buf
}

async function main() {
  const args = process.argv.slice(2)
  const dry = args.includes('--dry')
  const force = args.includes('--force')
  const manifestOnly = args.includes('--manifest-only')
  const fixedOnly = args.includes('--fixed-only')
  const onlyIdx = args.indexOf('--only')
  const only = onlyIdx >= 0 ? args[onlyIdx + 1] : null

  let table = buildTable()
  if (only) {
    if (!table[only]) {
      console.error(`没有这个 key：${only}`)
      process.exit(1)
    }
    table = { [only]: table[only] }
  } else if (fixedOnly) {
    // 只出「整句」那几条（它说话的那几条）—— 用来先定词、先试听，别一上来就全批
    table = Object.fromEntries(Object.entries(table).filter(([k]) => k in FIXED))
  }

  const keys = Object.keys(table)
  console.log(`共 ${keys.length} 条`)
  if (dry) {
    for (const k of keys) console.log(`  ${k.padEnd(22)} ${table[k]}`)
    return
  }

  await mkdir(OUT_DIR, { recursive: true })

  // ★ 清单**永远写全表**，不管这次只生成哪几条。
  //   App 靠清单判断「这句有没有预录」：清单缺项 = 那些句子直接退回实时 TTS，
  //   不报错、只是变慢 —— 悄悄坏掉的那一类。所以用 buildTable()，不是过滤后的 table。
  const full = buildTable()
  await writeFile(join(OUT_DIR, 'phrases.json'), JSON.stringify(full, null, 2) + '\n', 'utf8')
  console.log(`清单写好：${Object.keys(full).length} 条 → ${join(OUT_DIR, 'phrases.json')}`)
  if (manifestOnly) return

  let done = 0, skipped = 0, failed = 0
  for (const k of keys) {
    // ★ 扩展名要看内容定，不能写死 mp3。
    //后端的 /tts 回来的是 **WAV**（RIFF 头，32kHz 单声道），不是 mp3 ——
    //   存成 .mp3 有些机型 MediaPlayer 会拒播，而且编译期毫无提示。
    const outWav = join(OUT_DIR, `${k}.wav`)
    const outMp3 = join(OUT_DIR, `${k}.mp3`)
    if (!force && ((await exists(outWav)) || (await exists(outMp3)))) { skipped++; continue }
    try {
      const bytes = await synthesize(table[k])
      const ext = bytes.length > 12 && bytes.toString('ascii', 0, 4) === 'RIFF' ? '.wav' : '.mp3'
      await writeFile(join(OUT_DIR, `${k}${ext}`), bytes)
      // 老命名的残留清掉，免得包里同时有 left_m50.mp3 和 left_m50.wav
      if (ext === '.wav' && (await exists(outMp3))) await unlink(outMp3)
      if (ext === '.mp3' && (await exists(outWav))) await unlink(outWav)
      done++
      process.stdout.write(`\r生成 ${done} / 失败 ${failed} / 跳过 ${skipped}  `)
      // ★ 必须节流：TTS 有每分钟请求数上限，连着快打会在 30 条左右被
      //   「rate limit exceeded(RPM)」打断（实测）。断了接着跑就行，已有的会跳过。
      await new Promise((r) => setTimeout(r, DELAY_MS))
    } catch (e) {
      failed++
      console.error(`\n[失败] ${k} (${table[k]}): ${e.message}`)
      // 限流的话多等一会儿再继续，别接着撞
      const waitMs = /rate limit/i.test(e.message) ? RATE_LIMIT_WAIT_MS : 800
      if (/rate limit/i.test(e.message)) console.error(`  限流，等 ${waitMs / 1000} 秒`)
      if (failed >= 40) { console.error('失败太多，先停下看看。'); break }
      await new Promise((r) => setTimeout(r, waitMs))
    }
  }
  console.log(`\n完事：生成 ${done}，跳过 ${skipped}，失败 ${failed}\n输出在 ${OUT_DIR}`)
}

main().catch((e) => { console.error(e); process.exit(1) })
