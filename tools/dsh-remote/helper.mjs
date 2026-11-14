#!/usr/bin/env node
/**
 * 鲸歌「远程 DSH 小助手」（M83 阶段 4）——电脑侧独立进程，**不改 DSH**。
 *
 * 它只做四件事：
 *  1. 从 ~/.dsh/.credentials.yaml 读 client-connection/browser-session.secret，按 DSH 的算法签浏览器 cookie
 *     （cookie 名 dsh-auth-<sha256(authority)>，值 v1.<payload>.<hmac>；每次请求现签，不落盘）；
 *  2. 在本机 / Tailscale 上开一个带 token 的转发口（默认 8137），手机只认 token、不碰 secret；
 *  3. /api/dsh/* 原样转发到本机 DSH（普通 HTTP + WebSocket 升级都转），手机自己讲 DSH 的协议；
 *  4. 打印手机要填的地址 + 短码（token）。
 *
 * secret 只在电脑内存里用，不落盘、不打印、不传给手机。
 *
 * 用法：
 *   node helper.mjs                # 读 ~/.dsh/.credentials.yaml，监听 0.0.0.0:8137
 *   node helper.mjs --port 9000 --dsh http://127.0.0.1:63167
 *   node helper.mjs --token 123456  # 固定短码（默认随机生成并写 ~/.dsh-remote/token）
 *
 * 手机侧：设置 → 远程 DSH → 地址 http://<电脑IP>:8137，令牌 <短码>。
 */
import { createServer, request as httpRequest } from 'node:http'
import { connect as netConnect } from 'node:net'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { homedir, networkInterfaces } from 'node:os'
import { join } from 'node:path'
import { randomBytes, createHash, createHmac } from 'node:crypto'

const args = process.argv.slice(2)
function arg(name, fallback) {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback
}

const PORT = Number(arg('port', process.env.DSH_REMOTE_PORT || 8137))
const DSH = arg('dsh', process.env.DSH_URL || 'http://127.0.0.1:63167')
const CRED = arg('credentials', process.env.DSH_CREDENTIALS || join(homedir(), '.dsh', '.credentials.yaml'))
const TOKEN_FILE = join(homedir(), '.dsh-remote', 'token')
const DSH_URL = new URL(DSH)
if (DSH_URL.protocol !== 'http:' && DSH_URL.protocol !== 'https:') throw new Error(`DSH 地址必须是 http(s)：${DSH}`)

/** 从 credentials.yaml（结构固定）里抠出 secret：只认 client-connection/browser-session 下的 secret。 */
function readSecret(path) {
  const text = readFileSync(path, 'utf8')
  const lines = text.split(/\r?\n/)
  let inRecord = false
  let recordIndent = -1
  for (const line of lines) {
    if (/^\s*client-connection\/browser-session\s*:\s*$/.test(line)) {
      inRecord = true
      recordIndent = line.search(/\S/)
      continue
    }
    if (inRecord) {
      const indent = line.search(/\S/)
      if (line.trim() && indent <= recordIndent) { inRecord = false; continue }
      const m = line.match(/^\s*secret\s*:\s*(.+?)\s*$/)
      if (m) {
        let v = m[1]
        if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1)
        return v
      }
    }
  }
  throw new Error(`没在 ${path} 里找到 client-connection/browser-session.secret`)
}

function base64url(buf) {
  return Buffer.from(buf).toString('base64').replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '')
}

function decodeBase64Url(text) {
  if (!/^[A-Za-z0-9_-]*$/.test(text) || text.length % 4 === 1) return undefined
  const padding = '='.repeat((4 - (text.length % 4)) % 4)
  const decoded = Buffer.from(text.replaceAll('-', '+').replaceAll('_', '/') + padding, 'base64')
  return base64url(decoded) === text ? decoded : undefined
}

function loadToken(fixed) {
  if (fixed) return fixed
  try {
    const saved = readFileSync(TOKEN_FILE, 'utf8').trim()
    if (saved.length >= 6) return saved
  } catch {}
  const token = randomBytes(4).toString('hex') // 8 位短码
  try {
    mkdirSync(join(homedir(), '.dsh-remote'), { recursive: true })
    writeFileSync(TOKEN_FILE, token + '\n', { mode: 0o600 })
  } catch {}
  return token
}

const SECRET = readSecret(CRED)
const SECRET_BYTES = decodeBase64Url(SECRET)
if (SECRET_BYTES === undefined || SECRET_BYTES.byteLength !== 32) throw new Error('credentials 里的 browser-session.secret 不是 32 字节 base64url，无法签名')
const TOKEN = loadToken(arg('token', ''))

/**
 * 与 DSH 的 requestAudience 对齐：HTTP 用 host（含端口），HTTPS 用 origin。
 * cookie 名只由这个公开的 authority 派生，secret 不参与名字。
 */
const AUDIENCE = DSH_URL.protocol === 'https:' ? DSH_URL.origin : DSH_URL.host
const COOKIE_NAME = arg('cookie-name', 'dsh-auth-' + base64url(createHash('sha256').update(AUDIENCE).digest()))

/** 现签一个浏览器会话 cookie（1 小时有效，DSH 默认上限 30 天，重签即可）。 */
function sessionCookie() {
  const issuedAt = Date.now()
  const expiresAt = issuedAt + 60 * 60 * 1000
  const body = base64url(Buffer.from(JSON.stringify({ version: 1, authority: AUDIENCE, issuedAt, expiresAt }), 'utf8'))
  const signature = base64url(createHmac('sha256', SECRET_BYTES).update(body).digest())
  return { name: COOKIE_NAME, value: `v1.${body}.${signature}` }
}

function cookieHeader() {
  const cookie = sessionCookie()
  return `${cookie.name}=${cookie.value}`
}

/** 转发到 DSH 时统一重写：Host 用 DSH 自己的 authority，去掉手机令牌与跨站标记，注入签名 cookie。 */
function upstreamHeaders(src) {
  const headers = { ...src }
  for (const key of Object.keys(headers)) {
    const lower = key.toLowerCase()
    if (lower === 'host' || lower === 'authorization' || lower === 'origin' || lower === 'sec-fetch-site' || lower === 'cookie' || lower === 'content-length') delete headers[key]
  }
  headers.host = DSH_URL.host
  headers.cookie = cookieHeader()
  return headers
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj)
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body) })
  res.end(body)
}

function authorized(req) {
  const h = req.headers['authorization'] || ''
  if (h === `Bearer ${TOKEN}`) return true
  const url = new URL(req.url, 'http://x')
  return url.searchParams.get('token') === TOKEN
}

/** 活着的 WebSocket 连接（只用于 /api/debug/drop-ws 自测断线重连）。 */
const upgradedSockets = new Set()

function proxy(req, res, prefix) {
  const url = new URL(req.url, 'http://x')
  url.searchParams.delete('token') // 令牌只属于手机↔助手，不转给 DSH
  const target = new URL((prefix === '/' ? '/' : url.pathname.slice(prefix.length)) + url.search, DSH)
  const headers = upstreamHeaders(req.headers)
  const upstream = httpRequest(
    {
      hostname: DSH_URL.hostname,
      port: DSH_URL.port || (DSH_URL.protocol === 'https:' ? 443 : 80),
      path: target.pathname + target.search,
      method: req.method,
      headers,
      ...(DSH_URL.protocol === 'https:' ? { agent: undefined } : {}),
    },
    (up) => {
      const out = { ...up.headers }
      delete out['set-cookie']
      res.writeHead(up.statusCode || 502, out)
      up.pipe(res)
    },
  )
  upstream.on('error', (e) => {
    if (!res.headersSent) sendJson(res, 502, { ok: false, error: `转发 DSH 失败：${e.message}` })
    else res.destroy()
  })
  req.pipe(upstream)
}

/** WebSocket 升级：手机直连 /api/dsh/api/remote.mux，助手改写头后把 TCP 字节透传。 */
function proxyUpgrade(req, socket, head) {
  if (!authorized(req)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
    socket.destroy()
    return
  }
  const url = new URL(req.url, 'http://x')
  if (!url.pathname.startsWith('/api/dsh/')) {
    socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
    socket.destroy()
    return
  }
  url.searchParams.delete('token')
  const path = url.pathname.slice('/api/dsh'.length) + url.search
  upgradedSockets.add(socket)
  socket.on('close', () => upgradedSockets.delete(socket))
  const headers = upstreamHeaders(req.headers)
  delete headers.connection
  delete headers.upgrade
  const lines = [`GET ${path} HTTP/1.1`]
  for (const [key, value] of Object.entries(req.headers)) {
    const lower = key.toLowerCase()
    if (lower === 'host' || lower === 'authorization' || lower === 'origin' || lower === 'sec-fetch-site' || lower === 'cookie' || lower === 'connection' || lower === 'upgrade') continue
    if (value !== undefined) lines.push(`${key}: ${Array.isArray(value) ? value.join(', ') : value}`)
  }
  lines.push(`host: ${DSH_URL.host}`)
  lines.push(`cookie: ${cookieHeader()}`)
  lines.push('connection: Upgrade')
  lines.push('upgrade: websocket')
  const upstream = netConnect({ host: DSH_URL.hostname, port: DSH_URL.port || 80 }, () => {
    upstream.write(lines.join('\r\n') + '\r\n\r\n')
    if (head && head.length > 0) upstream.write(head)
    upstream.pipe(socket)
    socket.pipe(upstream)
  })
  upstream.on('error', () => socket.destroy())
  socket.on('error', () => upstream.destroy())
  upstream.on('close', () => socket.destroy())
  socket.on('close', () => upstream.destroy())
}

function lanAddresses() {
  const out = []
  for (const [name, list] of Object.entries(networkInterfaces())) {
    for (const item of list || []) {
      if (item.family === 'IPv4' && !item.internal) out.push({ name, address: item.address })
    }
  }
  return out
}

/** health：先验浏览器 cookie（GET /），再验 API 通道（POST /api/session/list）。 */
function health(res) {
  const probe = httpRequest(
    { hostname: DSH_URL.hostname, port: DSH_URL.port || 80, path: '/', method: 'GET', headers: { host: DSH_URL.host, cookie: cookieHeader() } },
    (up) => {
      up.resume()
      const reachable = (up.statusCode || 0) < 400
      if (!reachable) {
        sendJson(res, 200, { ok: true, dsh: `http ${up.statusCode}`, dshUrl: DSH })
        return
      }
      const body = JSON.stringify({ type: 'client-request', rpcId: 'health-1', method: 'session/list', payload: { args: { _request: {} } } })
      const api = httpRequest(
        {
          hostname: DSH_URL.hostname,
          port: DSH_URL.port || 80,
          path: '/api/session/list',
          method: 'POST',
          headers: { host: DSH_URL.host, cookie: cookieHeader(), 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) },
        },
        (answer) => {
          let text = ''
          answer.setEncoding('utf8')
          answer.on('data', (chunk) => { text += chunk })
          answer.on('end', () => {
            let apiState
            try {
              const parsed = JSON.parse(text)
              apiState = parsed?.result?.ok === true ? 'ok' : `error: ${parsed?.result?.error?.code ?? 'unknown'}`
            } catch {
              apiState = `http ${answer.statusCode}`
            }
            sendJson(res, 200, { ok: true, dsh: 'reachable', api: apiState, dshUrl: DSH })
          })
        },
      )
      api.on('error', (e) => sendJson(res, 200, { ok: true, dsh: 'reachable', api: `unreachable: ${e.message}`, dshUrl: DSH }))
      api.end(body)
    },
  )
  probe.on('error', (e) => sendJson(res, 200, { ok: true, dsh: `unreachable: ${e.message}`, dshUrl: DSH }))
  probe.end()
}

const server = createServer((req, res) => {
  if (req.url === '/api/health') return health(res)
  if (!authorized(req)) return sendJson(res, 401, { ok: false, error: '令牌不对' })
  // 自测：主动掐掉所有 WebSocket，验证手机端的断线提示 + 自动重连（不碰 DSH）。
  if (req.url === '/api/debug/drop-ws') {
    let dropped = 0
    for (const socket of upgradedSockets) {
      dropped += 1
      socket.destroy()
    }
    upgradedSockets.clear()
    return sendJson(res, 200, { ok: true, dropped })
  }
  if (req.url.startsWith('/api/dsh/')) return proxy(req, res, '/api/dsh')
  return sendJson(res, 404, { ok: false, error: '只有 /api/health 和 /api/dsh/*' })
})
server.on('upgrade', proxyUpgrade)

server.listen(PORT, '0.0.0.0', () => {
  const addrs = lanAddresses()
  console.log('鲸歌远程 DSH 小助手已启动')
  console.log(`  本机 DSH：${DSH}`)
  console.log(`  会话 cookie：${COOKIE_NAME}（自动签名，不打印 secret）`)
  console.log(`  令牌（短码）：${TOKEN}`)
  console.log(`  手机填：`)
  console.log(`    http://127.0.0.1:${PORT}（仅电脑本机）`)
  for (const a of addrs) console.log(`    http://${a.address}:${PORT}  [${a.name}]`)
  console.log('  提示：手机和电脑在同一个 Tailscale 网络时，用 100.x 的地址；不要把这个端口暴露到公网。')
  console.log('  secret 只在电脑内存里使用，不写盘、不打印。')
})

process.on('SIGINT', () => { server.close(() => process.exit(0)) })
