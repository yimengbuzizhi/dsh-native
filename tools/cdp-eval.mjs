#!/usr/bin/env node
/*
 * 真机 WebView CDP 探针（M64 起用）：连 `adb forward tcp:<port> localabstract:webview_devtools_remote_<pid>`
 * 暴露的 DevTools，执行一段 Runtime.evaluate 并打印结果。
 *
 * 用法：node tools/cdp-eval.mjs 9223 "document.title"
 */
const port = process.argv[2] || '9223'
let expression = process.argv[3] || 'document.title'
let timeoutArg = process.argv[4]
if (expression === '--file') {
  const { readFileSync } = await import('node:fs')
  expression = readFileSync(process.argv[4], 'utf8')
  timeoutArg = process.argv[5]
}
const timeoutMs = Number(timeoutArg || 15000)

const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json()
const titleFilter = process.env.CDP_PAGE_TITLE || ''
const page = (titleFilter
  ? targets.find((t) => (t.title || '').includes(titleFilter))
  : null) || targets.find((t) => t.type === 'page') || targets[0]
if (!page || !page.webSocketDebuggerUrl) {
  console.error('no page target:', JSON.stringify(targets))
  process.exit(2)
}

const timer = setTimeout(() => {
  console.error('timeout')
  process.exit(3)
}, timeoutMs)

const ws = new WebSocket(page.webSocketDebuggerUrl)
ws.onopen = () => {
  ws.send(JSON.stringify({
    id: 1,
    method: 'Runtime.evaluate',
    params: { expression, returnByValue: true, awaitPromise: true, userGesture: true }
  }))
}
ws.onmessage = (event) => {
  let msg
  try { msg = JSON.parse(event.data) } catch { return }
  if (msg.id !== 1) return
  clearTimeout(timer)
  if (msg.result && msg.result.exceptionDetails) {
    console.error(JSON.stringify(msg.result.exceptionDetails, null, 2))
    process.exit(4)
  }
  const value = msg.result?.result?.value
  console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 2))
  ws.close()
  process.exit(0)
}
ws.onerror = (event) => {
  clearTimeout(timer)
  console.error('ws error:', event.message || event)
  process.exit(5)
}
