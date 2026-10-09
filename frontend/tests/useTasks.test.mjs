import test from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { effectScope, ref } from 'vue'
import axios from 'axios'

const root = fileURLToPath(new URL('../', import.meta.url))

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

async function loadTasks(t) {
  // Load the app's ESM .js through Vite without changing package.json's type.
  const server = await createServer({
    root,
    configFile: false,
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, hmr: false, ws: false, watch: null }
  })
  const scope = effectScope()
  t.after(async () => {
    scope.stop()
    await server.close()
  })
  const { useTasks } = await server.ssrLoadModule('/src/composables/useTasks.js')
  return { useTasks, scope, server }
}

async function setup(t, threadId = 'A') {
  const { useTasks, scope } = await loadTasks(t)
  const activeThreadId = ref(threadId)
  const requests = []
  const request = id => {
    const pending = deferred()
    requests.push({ threadId: id, ...pending })
    return pending.promise
  }
  const tasks = scope.run(() => useTasks({ activeThreadId, request }))
  return { activeThreadId, requests, ...tasks }
}

const task = (id, status = 'PENDING') => ({ id, status })
const ids = taskList => taskList.value.map(item => item.id)

async function populate(state, data) {
  const done = state.refreshTasks()
  state.requests.at(-1).resolve({ data })
  await done
}

test('A/B responses arriving out of order cannot replace B tasks', async t => {
  const state = await setup(t)
  await populate(state, [task('A-good')])
  const old = state.refreshTasks()
  state.activeThreadId.value = 'B'
  assert.deepEqual(ids(state.taskList), [], 'switching clears immediately')
  const latest = state.refreshTasks()
  assert.deepEqual(state.requests.map(r => r.threadId), ['A', 'A', 'B'])
  state.requests[2].resolve({ data: [task('B-good')] })
  await latest
  state.requests[1].resolve({ data: [task('A-stale')] })
  await old
  assert.deepEqual(ids(state.taskList), ['B-good'])
})

test('same-thread refreshes accept only the latest response', async t => {
  const state = await setup(t)
  const old = state.refreshTasks()
  const latest = state.refreshTasks()
  assert.ok(old instanceof Promise)
  assert.ok(latest instanceof Promise)
  state.requests[1].resolve({ data: [task('latest')] })
  await latest
  state.requests[0].resolve({ data: [task('old')] })
  await old
  assert.deepEqual(ids(state.taskList), ['latest'])
})

test('A/B/A rejects the first A generation even when threadId matches again', async t => {
  const state = await setup(t)
  const firstA = state.refreshTasks()
  state.activeThreadId.value = 'B'
  const b = state.refreshTasks()
  state.activeThreadId.value = 'A'
  const latestA = state.refreshTasks()
  state.requests[2].resolve({ data: [task('A-latest')] })
  await latestA
  state.requests[0].resolve({ data: [task('A-old')] })
  await firstA
  state.requests[1].resolve({ data: [task('B-old')] })
  await b
  assert.deepEqual(ids(state.taskList), ['A-latest'])
})

test('synchronous A/B/A switches invalidate pending work without a refresh', async t => {
  const state = await setup(t)
  await populate(state, [task('A-good')])
  const pending = state.refreshTasks()
  state.activeThreadId.value = 'B'
  assert.deepEqual(ids(state.taskList), [])
  state.activeThreadId.value = 'A'
  state.requests[1].resolve({ data: [task('A-stale')] })
  await pending
  assert.deepEqual(ids(state.taskList), [])
  assert.equal(state.requests.length, 2, 'the watcher does not duplicate requests')
})

test('a thread switch blocks an old response before the new refresh starts', async t => {
  const state = await setup(t)
  const pending = state.refreshTasks()
  state.activeThreadId.value = 'B'
  state.requests[0].resolve({ data: [task('A-stale')] })
  await pending
  assert.deepEqual(ids(state.taskList), [])
})

test('empty threads clear tasks, invalidate pending work, and return a Promise', async t => {
  const state = await setup(t)
  await populate(state, [task('A-good')])
  const pending = state.refreshTasks()
  state.activeThreadId.value = ''
  assert.deepEqual(ids(state.taskList), [])
  const empty = state.refreshTasks()
  assert.ok(empty instanceof Promise)
  await empty
  assert.equal(state.requests.length, 2, 'empty threads do not request todos')
  state.activeThreadId.value = 'A'
  state.requests[1].resolve({ data: [task('A-stale')] })
  await pending
  assert.deepEqual(ids(state.taskList), [])

  state.activeThreadId.value = null
  await state.refreshTasks()
  assert.equal(state.requests.length, 2)
  assert.deepEqual(ids(state.taskList), [])
})

test('stale failures cannot clear a newer thread or same-thread success', async t => {
  const state = await setup(t)
  const oldA = state.refreshTasks()
  state.activeThreadId.value = 'B'
  const oldB = state.refreshTasks()
  const latestB = state.refreshTasks()
  state.requests[2].resolve({ data: [task('B-good')] })
  await latestB
  state.requests[0].reject(new Error('old A failed'))
  await oldA
  assert.deepEqual(ids(state.taskList), ['B-good'])
  state.requests[1].reject(new Error('old B failed'))
  await oldB
  assert.deepEqual(ids(state.taskList), ['B-good'])
})

test('a latest same-thread failure preserves the last good list, not an older pending success', async t => {
  const state = await setup(t)
  await populate(state, [task('last-good')])
  const old = state.refreshTasks()
  const latest = state.refreshTasks()
  state.requests[2].reject(new Error('latest failed'))
  await latest
  assert.deepEqual(ids(state.taskList), ['last-good'])
  state.requests[1].resolve({ data: [task('stale-success')] })
  await old
  assert.deepEqual(ids(state.taskList), ['last-good'])
})

test('a new-thread failure leaves tasks empty rather than restoring the previous thread', async t => {
  const state = await setup(t)
  await populate(state, [task('A-good')])
  state.activeThreadId.value = 'B'
  const latest = state.refreshTasks()
  assert.deepEqual(ids(state.taskList), [])
  state.requests[1].reject(new Error('B failed'))
  await latest
  assert.deepEqual(ids(state.taskList), [])
})

test('tasks are sorted on a copy with stable status ranking and unknown statuses last', async t => {
  const state = await setup(t)
  const data = Object.freeze([
    task('unknown', 'UNKNOWN'),
    task('completed', 'COMPLETED'),
    task('pending-first'),
    task('cancelled', 'CANCELLED'),
    task('running', 'RUNNING'),
    task('failed', 'FAILED'),
    task('pending-second'),
    { id: 'no-status' }
  ].map(Object.freeze))
  const originalIds = data.map(item => item.id)
  await populate(state, data)
  assert.notStrictEqual(state.taskList.value, data)
  assert.deepEqual(data.map(item => item.id), originalIds)
  assert.deepEqual(ids(state.taskList), [
    'running', 'pending-first', 'pending-second', 'failed',
    'completed', 'cancelled', 'unknown', 'no-status'
  ])
  await populate(state, null)
  assert.deepEqual(ids(state.taskList), [])
})

test('the default request uses getTodos with silent mode without changing other todo APIs', async t => {
  const originalAdapter = axios.defaults.adapter
  const configs = []
  axios.defaults.adapter = async config => {
    configs.push(config)
    return { data: [task('default-request')], status: 200, statusText: 'OK', headers: {}, config }
  }
  t.after(() => { axios.defaults.adapter = originalAdapter })

  const { useTasks, scope, server } = await loadTasks(t)
  const { taskList, refreshTasks } = scope.run(() => useTasks({ activeThreadId: ref('A') }))
  await refreshTasks()
  assert.deepEqual(ids(taskList), ['default-request'])
  assert.equal(configs[0].url, '/todos')
  assert.deepEqual(configs[0].params, { threadId: 'A' })
  assert.equal(configs[0].silent, true)

  // SSR uses the same API module, while the other exports retain their config.
  const { getPendingTodos, getOverdueTodos } = await server.ssrLoadModule('/src/api/index.js')
  await getPendingTodos('B')
  await getOverdueTodos('C')
  assert.deepEqual(configs.slice(1).map(config => [config.url, config.params.threadId, config.silent]), [
    ['/todos/pending', 'B', undefined],
    ['/todos/overdue', 'C', undefined]
  ])
})
