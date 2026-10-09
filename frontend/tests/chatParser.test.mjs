import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'

const server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false, ws: false, watch: null } })
test.after(() => server.close())
const { streamChatEvents, chatStream, approveStream } = await server.ssrLoadModule('/src/api/index.js')
const encode = text => new TextEncoder().encode(text)
function mock(t, chunks, readError) {
  const original = globalThis.fetch
  const stats = { reads: 0, cancel: 0, release: 0, requests: [] }
  globalThis.fetch = async (url, options) => {
    stats.requests.push({ url, options })
    return { ok: true, status: 200, body: { getReader() { return {
      async read() {
        const index = stats.reads++
        if (readError && index >= chunks.length) throw readError
        return index < chunks.length ? { done: false, value: chunks[index] } : { done: true }
      },
      async cancel() { stats.cancel++ },
      releaseLock() { stats.release++ }
    } } } }
  }
  t.after(() => { globalThis.fetch = original })
  return stats
}

test('parser decodes split UTF8, CRLF, bad JSON, multiline data and the EOF final line', async t => {
  const source = encode('event: delta\r\ndata: {"text":"中文"}\r\n\r\nevent: delta\ndata: {bad}\n\nevent: progress\ndata: {"stage":\ndata: "ANALYZING"}\n\nevent: answer\ndata: {"text":"最后"}')
  const chunks = [...source].map(byte => new Uint8Array([byte]))
  const stats = mock(t, chunks)
  const events = []
  await streamChatEvents('A', '', {}, { onDelta: p => events.push(['delta', p.text]), onProgress: p => events.push(['progress', p.stage]), onAnswer: p => events.push(['answer', p.text]) })
  assert.deepEqual(events, [['delta', '中文'], ['progress', 'ANALYZING'], ['answer', '最后']])
  assert.equal(stats.cancel, 1)
  assert.equal(stats.release, 1)
})

test('done dispatches once and stops subsequent buffered events and reader reads', async t => {
  const stats = mock(t, [encode('event: done\ndata: {}\n\nevent: delta\ndata: {"text":"ignored"}\n\n'), encode('event: done\ndata: {}\n\n')])
  const events = []
  await streamChatEvents('A', '', {}, { onDone: () => events.push('done'), onDelta: () => events.push('delta') })
  assert.deepEqual(events, ['done'])
  assert.equal(stats.reads, 1)
  assert.equal(stats.cancel, 1)
  assert.equal(stats.release, 1)
})

test('bare done at EOF ends the transport even without data', async t => {
  const stats = mock(t, [encode('event: done')])
  let done = 0
  await streamChatEvents('A', '', {}, { onDone: () => done++ })
  assert.equal(done, 1)
  assert.equal(stats.release, 1)
})

test('handler exceptions propagate and release resources, including onDone', async t => {
  for (const name of ['delta', 'done']) {
    const stats = mock(t, [encode(`event: ${name}\ndata: {}\n\n`)])
    const error = new Error(`handler-${name}`)
    await assert.rejects(streamChatEvents('A', '', {}, { [name === 'done' ? 'onDone' : 'onDelta']: () => { throw error } }), error)
    assert.equal(stats.cancel, 1)
    assert.equal(stats.release, 1)
  }
})

test('reader errors/abort propagate and clean up', async t => {
  const error = new Error('read failed')
  const stats = mock(t, [], error)
  await assert.rejects(streamChatEvents('A', '', {}, {}), error)
  assert.equal(stats.cancel, 1)
  assert.equal(stats.release, 1)
})

test('decoder flushes a trailing incomplete UTF8 codepoint at EOF', async t => {
  // JSON whitespace cannot contain U+FFFD, so a truncated payload is ignored.
  const stats = mock(t, [encode('event: delta\ndata: {"text":"ok"}\n\n'), new Uint8Array([0xe4])])
  const events = []
  await streamChatEvents('A', '', {}, { onDelta: p => events.push(p.text) })
  assert.deepEqual(events, ['ok'])
  assert.equal(stats.release, 1)
})

test('chat/approval URLs, HTTP bodies and signal remain unchanged', async t => {
  const stats = mock(t, [])
  const signal = new AbortController().signal
  await chatStream('A', 'hello', 'PlanMode', {}, signal)
  await approveStream('B', [{ toolId: 'tool', result: 'APPROVED' }], {}, signal)
  assert.equal(stats.requests[0].url, '/api/chat/A')
  assert.equal(stats.requests[0].options.body, JSON.stringify({ message: 'hello', mode: 'PlanMode' }))
  assert.equal(stats.requests[1].url, '/api/chat/B/approve')
  assert.equal(stats.requests[1].options.body, JSON.stringify({ decisions: [{ toolId: 'tool', result: 'APPROVED' }] }))
  assert.strictEqual(stats.requests[0].options.signal, signal)
})
