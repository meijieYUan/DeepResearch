import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'
import { effectScope, ref } from 'vue'

const server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false, ws: false, watch: null } })
test.after(() => server.close())
const { useChatStream } = await server.ssrLoadModule('/src/composables/useChatStream.js')
const tick = () => new Promise(resolve => setImmediate(resolve))
const progress = (runId = 'run', extra = {}) => ({ runId, operation: 'WORKFLOW', status: 'RUNNING', stage: 'ANALYZING', timestamp: 10, ...extra })
function setup(t) {
  const scope = effectScope()
  t.after(() => scope.stop())
  const threads = ref({ A: { id: 'A', title: '', messages: [] }, B: { id: 'B', title: '', messages: [] } })
  const activeThreadId = ref('A'), planMode = ref(false), planActive = ref(false)
  const requests = [], calls = { save: 0, refresh: 0, scroll: 0 }, frames = new Map()
  let frame = 0
  const originalRAF = globalThis.requestAnimationFrame, originalCancel = globalThis.cancelAnimationFrame
  globalThis.requestAnimationFrame = callback => { const id = ++frame; frames.set(id, callback); return id }
  globalThis.cancelAnimationFrame = id => frames.delete(id)
  t.after(() => { globalThis.requestAnimationFrame = originalRAF; globalThis.cancelAnimationFrame = originalCancel })
  const request = (kind, threadId, body, handlers, signal) => new Promise((resolve, reject) => requests.push({ kind, threadId, body, handlers, signal, resolve, reject }))
  const stream = scope.run(() => useChatStream({ threads, activeThreadId, planMode, planActive,
    saveThreads: () => calls.save++, refreshTasks: () => calls.refresh++, scrollDown: () => calls.scroll++,
    requestChat: (id, text, mode, handlers, signal) => request('chat', id, { text, mode }, handlers, signal),
    requestApproval: (id, decisions, handlers, signal) => request('approval', id, decisions, handlers, signal)
  }))
  const flush = () => { const pending = [...frames.values()]; frames.clear(); for (const fn of pending) fn() }
  return { ...stream, threads, activeThreadId, planMode, planActive, requests, calls, frames, flush, scope }
}
const bubble = (state, id = state.activeThreadId.value) => state.threads.value[id].messages.at(-1)

test('A/B/A and old finally cannot mutate new bubble, plan, progress, refresh or loading', async t => {
  const s = setup(t)
  assert.equal(s.send('first', 'Default'), true)
  await tick()
  const first = s.requests[0], oldBubble = bubble(s)
  first.handlers.onDelta({ text: 'partial' })
  s.activeThreadId.value = 'B'
  assert.equal(oldBubble.content, 'partial')
  assert.equal(oldBubble.streaming, false)
  assert.equal(oldBubble.transportStatus, 'stopped')
  assert.equal(first.signal.aborted, true)
  assert.equal(s.frames.size, 0)
  assert.equal(s.send('B', 'Default'), true)
  await tick()
  const second = s.requests[1]
  s.activeThreadId.value = 'A'
  assert.equal(s.send('latest', 'Default'), true)
  await tick()
  const current = bubble(s), before = { ...s.calls }
  for (const request of [first, second]) {
    request.handlers.onProgress(progress('stale'))
    request.handlers.onDelta({ text: 'stale' })
    request.handlers.onAnswer({ text: 'stale', planEnabled: true, planActive: true })
    request.handlers.onInterruption({ message: 'stale', pendingApprovals: [{ toolId: 't' }], planEnabled: true })
    request.handlers.onError({ message: 'stale' })
    request.handlers.onDone()
  }
  s.flush()
  first.reject(new Error('old network error'))
  second.resolve()
  await tick()
  assert.equal(s.loading.value, true)
  assert.equal(current.content, '')
  assert.equal(current.progress, undefined)
  assert.equal(s.planMode.value, false)
  assert.deepEqual(s.calls, before)
  s.requests[2].handlers.onAnswer({ text: 'new' })
  s.requests[2].handlers.onDone()
  s.requests[2].resolve()
  await tick()
  assert.equal(current.content, 'new')
  assert.equal(s.loading.value, false)
})

test('object graph reload resolves by message.id and answer keeps progress history', async t => {
  const s = setup(t)
  s.send('hello', 'Default')
  await tick()
  const request = s.requests[0], oldId = bubble(s).id
  s.threads.value = structuredClone(JSON.parse(JSON.stringify(s.threads.value)))
  request.handlers.onProgress(progress('a'))
  request.handlers.onDelta({ text: 'part' })
  s.flush()
  assert.equal(bubble(s).id, oldId)
  assert.equal(bubble(s).content, 'part')
  request.handlers.onAnswer({ text: 'answer', planEnabled: true })
  request.handlers.onDone()
  const before = { ...s.calls }
  request.handlers.onAnswer({ text: 'late' })
  request.handlers.onProgress(progress('late'))
  request.resolve()
  await tick()
  assert.equal(bubble(s).content, 'answer')
  assert.equal(bubble(s).progress.activeRunId, 'a')
  assert.equal(bubble(s).progress.transportStatus, 'done')
  assert.equal(bubble(s).progress.runs[0].status, 'RUNNING')
  assert.equal(bubble(s).incomplete, false)
  assert.deepEqual(s.calls, before)
})

test('error and network failure retain pending partial text with separate error/transport/incomplete', async t => {
  for (const network of [false, true]) {
    const s = setup(t)
    s.send('hello', 'Default')
    await tick()
    const request = s.requests[0]
    request.handlers.onProgress(progress())
    request.handlers.onDelta({ text: 'partial answer' })
    if (network) request.reject(new Error('network failed'))
    else { request.handlers.onError({ message: 'server failed' }); request.handlers.onDone(); request.resolve() }
    await tick()
    assert.equal(bubble(s).content, 'partial answer')
    assert.equal(bubble(s).error, network ? 'network failed' : 'server failed')
    assert.equal(bubble(s).transportStatus, 'error')
    assert.equal(bubble(s).progress.transportStatus, 'error')
    assert.equal(bubble(s).incomplete, true)
    assert.equal(bubble(s).streaming, false)
    assert.equal(s.frames.size, 0)
    assert.equal(s.loading.value, false)
  }
})

test('EOF without done is connection_lost even after answer; done is not business PASS', async t => {
  const s = setup(t)
  s.send('hello', 'Default')
  await tick()
  const request = s.requests[0]
  request.handlers.onProgress(progress('a', { stage: 'DONE', status: 'LIMIT_REACHED' })) // no explicit start, ignored
  request.handlers.onProgress(progress('a'))
  request.handlers.onProgress(progress('a', { stage: 'DONE', status: 'LIMIT_REACHED', timestamp: 20 }))
  request.handlers.onAnswer({ text: 'answer' })
  request.resolve()
  await tick()
  assert.equal(bubble(s).transportStatus, 'connection_lost')
  assert.equal(bubble(s).incomplete, true)
  assert.equal(bubble(s).content, 'answer')
  assert.equal(bubble(s).progress.runs[0].status, 'LIMIT_REACHED')
})

test('approval preserves the bubble snapshot and enforces loading + single-flight + current-message gate', async t => {
  const s = setup(t)
  s.send('hello', 'Default')
  await tick()
  const request = s.requests[0], id = bubble(s).id
  request.handlers.onProgress(progress('a'))
  request.handlers.onDelta({ text: 'partial' })
  request.handlers.onInterruption({ message: 'approve', pendingApprovals: [{ toolId: 'tool', arguments: '{}' }] })
  const approval = bubble(s)
  assert.equal(approval.id, id)
  assert.equal(approval.content, 'partial\n\napprove')
  assert.equal(approval.progress.activeRunId, 'a')
  approval.approvals[0].decision = 'APPROVED'
  assert.equal(s.submitApprovals(approval), false, 'original connection still owns loading')
  request.handlers.onDone(); request.resolve(); await tick()
  assert.equal(s.submitApprovals({ id: 'missing', approvals: approval.approvals }), false)
  assert.equal(s.submitApprovals(approval), true)
  assert.equal(s.submitApprovals(approval), false)
  assert.equal(s.send('duplicate', 'Default'), false)
  await tick()
  assert.equal(s.requests.length, 2)
  assert.equal(s.requests[1].kind, 'approval')
  assert.deepEqual(s.requests[1].body, [{ toolId: 'tool', result: 'APPROVED' }])
  s.requests[1].handlers.onDone(); s.requests[1].resolve(); await tick()
  assert.equal(s.submitApprovals(approval), false, 'cannot replay a submitted group')
  assert.equal(approval.progress.activeRunId, 'a')
})

test('stop/unmount clears pending RAF, flushes partial once and rejects all later handlers', async t => {
  const s = setup(t)
  s.send('hello', 'Default')
  await tick()
  const request = s.requests[0], current = bubble(s)
  request.handlers.onDelta({ text: 'tail' })
  s.stopStream('B')
  assert.equal(s.loading.value, true)
  s.scope.stop()
  assert.equal(current.content, 'tail')
  assert.equal(current.transportStatus, 'stopped')
  assert.equal(s.frames.size, 0)
  const before = { ...s.calls }
  request.handlers.onDelta({ text: 'late' }); request.handlers.onDone(); request.resolve()
  await tick()
  assert.equal(current.content, 'tail')
  assert.deepEqual(s.calls, before)
})
