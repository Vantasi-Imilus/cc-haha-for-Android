#!/usr/bin/env node
/**
 * 端到端会话测试：通过 server API 创建会话并用 WebSocket 跑一轮真实对话。
 *
 * 用法：
 *   node tools/e2e-session-test.mjs [--host 127.0.0.1:3456] ["消息内容"]
 *
 * 流程：
 *   1. POST /api/sessions 创建会话（workDir 由服务器侧 CALLER_DIR 决定）
 *   2. WS 连接 /ws/:sessionId
 *   3. 发送 { type: 'user_message', content }
 *   4. 收集流式消息（content_delta 拼接回复文本），直到 message_complete / turn 结束
 */
import WebSocket from 'ws'

const args = process.argv.slice(2)
const hostIdx = args.indexOf('--host')
const host = hostIdx >= 0 ? args[hostIdx + 1] : '127.0.0.1:3456'
const prompt =
  (hostIdx >= 0 ? args.filter((a, i) => i !== hostIdx && i !== hostIdx + 1) : args).join(' ') ||
  '请回复一句话，确认你能正常工作。'

const http = `http://${host}`
const wsUrl = (id) => `ws://${host}/ws/${encodeURIComponent(id)}`

async function main() {
  // 1. 创建会话
  console.log('[1] 创建会话 ...')
  const createResp = await fetch(`${http}/api/sessions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ permissionMode: 'default' }),
  })
  if (!createResp.ok) {
    throw new Error(`创建会话失败: ${createResp.status} ${await createResp.text()}`)
  }
  const session = await createResp.json()
  const sessionId = session.sessionId ?? session.id
  console.log(`[1] 会话: ${sessionId}`)

  // 2. WS 连接
  console.log('[2] 连接 WebSocket ...')
  const ws = new WebSocket(wsUrl(sessionId))
  const done = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('120 秒超时')), 120_000)
    let reply = ''
    let sent = false
    let sawPermission = false

    ws.on('open', () => {
      console.log('[2] 已连接，发送消息 ...')
      sent = true
      ws.send(JSON.stringify({ type: 'user_message', content: prompt }))
    })

    ws.on('message', (data) => {
      const msg = JSON.parse(data.toString())
      switch (msg.type) {
        case 'connected':
        case 'session_state':
        case 'user_message_replay':
          break
        case 'content_delta':
          if (msg.text) {
            reply += msg.text
            process.stdout.write(msg.text)
          }
          break
        case 'content_start':
          if (msg.blockType === 'tool_use') {
            console.log(`\n[工具调用] ${msg.toolName}`)
          }
          break
        case 'tool_use_complete':
          console.log(`\n[工具输入] ${JSON.stringify(msg.input).slice(0, 200)}`)
          break
        case 'tool_result':
          console.log(
            `\n[工具结果] ${msg.isError ? '❌ ' : ''}${JSON.stringify(msg.content).slice(0, 200)}`,
          )
          break
        case 'permission_request':
          sawPermission = true
          console.log(
            `\n[权限请求] ${msg.toolName} ${msg.description ?? ''} — 自动放行`,
          )
          ws.send(
            JSON.stringify({
              type: 'permission_response',
              requestId: msg.requestId,
              allowed: true,
            }),
          )
          break
        case 'message_complete':
          console.log(`\n[message_complete] usage=${JSON.stringify(msg.usage)}`)
          break
        case 'result':
          console.log(
            `\n[result] is_error=${msg.is_error} ${JSON.stringify(msg).slice(0, 800)}`,
          )
          break
        default:
          // 其他类型打印摘要
          const s = JSON.stringify(msg)
          if (s.length < 200) console.log(`\n[${msg.type}] ${s}`)
      }
      // 结束条件：出现 message_complete 且已收到回复，或 turn 状态回 idle
      if (msg.type === 'message_complete' && reply) {
        clearTimeout(timeout)
        resolve({ reply })
      }
    })

    ws.on('error', (e) => {
      clearTimeout(timeout)
      reject(new Error(`WS 错误: ${e.message}`))
    })
    ws.on('close', (code) => {
      clearTimeout(timeout)
      if (!sent) reject(new Error(`WS 提前关闭 code=${code}`))
      else resolve({ reply, closed: true })
    })
  })

  const result = await done
  console.log('\n\n========== 测试结果 ==========')
  console.log(`回复长度: ${(result.reply ?? '').length} 字符`)
  console.log(`回复内容: ${(result.reply ?? '').slice(0, 500)}`)
  console.log(result.closed ? '（连接已关闭）' : '')
  ws.close()
  process.exit(0)
}

main().catch((e) => {
  console.error('\n❌ 测试失败:', e.message)
  process.exit(1)
})
