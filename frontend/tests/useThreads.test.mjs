import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'

const server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false, ws: false, watch: null } })
test.after(() => server.close())
const { useThreads, THREADS_STORAGE_KEY } = await server.ssrLoadModule('/src/composables/useThreads.js')
const { reduceResearchProgress } = await server.ssrLoadModule('/src/utils/researchProgress.js')
function storage(t) {
  const original = globalThis.localStorage, data = new Map()
  globalThis.localStorage = { getItem: key => data.get(key) || null, setItem: (key, value) => data.set(key, value), removeItem: key => data.delete(key) }
  t.after(() => { globalThis.localStorage = original })
  return data
}
const progress = status => reduceResearchProgress(null, { runId: 'run', operation: 'WORKFLOW', status: 'RUNNING', stage: 'ANALYZING', timestamp: 10, counts: { total: 4 } })

test('save/load sanitize progress, retain final snapshots, convert running transport to unknown', t => {
  const data = storage(t), store = useThreads()
  let running = progress()
  running.secret = 'discard'
  running.runs[0].papers = [{ paper: 'paper', title: 'title', operations: { DOWNLOAD: { status: 'SUCCEEDED', detail: '<script>', timestamp: 11 } }, secret: 'discard' }]
  let final = reduceResearchProgress(progress(), { runId: 'run', operation: 'WORKFLOW', status: 'LIMIT_REACHED', stage: 'DONE', timestamp: 20 })
  final.transportStatus = 'done'
  store.threads.value = { A: { id: 'A', title: 'title', lastActive: 1, messages: [
    { id: 'running', role: 'assistant', content: 'partial', streaming: true, transportStatus: 'running', progress: running },
    { id: 'final', role: 'assistant', content: 'final text', transportStatus: 'done', progress: final, error: 'error'.repeat(300) },
    { id: 'approval', role: 'assistant', content: 'approve', approvals: [{ toolId: 't', toolName: 'tool', arguments: '{}', editing: true }], approvalsSubmitted: true },
    { id: 'bad', role: 'user', content: 'hello', progress: 'bad' }
  ] } }
  store.saveThreads()
  const saved = JSON.parse(data.get(THREADS_STORAGE_KEY)).A.messages
  assert.equal(saved[0].streaming, undefined)
  assert.equal(saved[0].progress.secret, undefined)
  assert.equal(saved[0].progress.runs[0].papers[0].secret, undefined)
  assert.equal(saved[1].error.length, 1024)
  store.loadThreads()
  const messages = store.threads.value.A.messages
  assert.equal(messages[0].transportStatus, 'unknown')
  assert.equal(messages[0].progress.transportStatus, 'unknown')
  assert.equal(messages[0].progress.runs[0].status, 'RUNNING')
  assert.equal(messages[0].incomplete, true)
  assert.equal(messages[1].progress.runs[0].status, 'LIMIT_REACHED')
  assert.equal(messages[1].progress.transportStatus, 'done')
  assert.equal(messages[2].approvalsSubmitted, true)
  assert.equal(messages[2].approvals[0].editing, undefined)
  assert.equal(messages[3].progress, undefined)
})

test('new/switch/delete call stop before changing active thread; history progress is not cleared', t => {
  storage(t)
  const store = useThreads(), calls = []
  store.threads.value = { A: { id: 'A', title: 'A', lastActive: 1, messages: [{ id: 'm', role: 'assistant', content: '', progress: progress() }] }, B: { id: 'B', title: 'B', lastActive: 2, messages: [] } }
  store.activeThreadId.value = 'A'
  store.setHooks({ stopStream: id => calls.push([id, store.activeThreadId.value]) })
  store.newThread()
  assert.deepEqual(calls[0], [undefined, 'A'])
  const newId = store.activeThreadId.value
  store.switchThread('A')
  assert.deepEqual(calls[1], [undefined, newId])
  assert.equal(store.threads.value.A.messages[0].progress.activeRunId, 'run')
  store.deleteThread('A')
  assert.deepEqual(calls[2], ['A', 'A'])
  assert.notEqual(store.activeThreadId.value, 'A')
})

test('malformed local progress/thread/message data cannot break restore', t => {
  const data = storage(t), store = useThreads()
  data.set(THREADS_STORAGE_KEY, JSON.stringify({ A: { id: 'A', messages: [null, { role: 'invalid' }, { role: 'assistant', progress: { runs: [null, {}, { runId: 'x', counts: { total: -4, failed: 'bad' }, papers: [null] }] } }] } }))
  store.loadThreads()
  assert.equal(store.threads.value.A.messages.length, 1)
  assert.deepEqual(store.threads.value.A.messages[0].progress.runs[0].counts, {})
})
