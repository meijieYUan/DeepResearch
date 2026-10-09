import test from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'

const server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false, ws: false, watch: null } })
test.after(() => server.close())
const { default: Panel } = await server.ssrLoadModule('/src/components/ResearchProgress.vue')
const { reduceResearchProgress: reduce, setProgressTransport } = await server.ssrLoadModule('/src/utils/researchProgress.js')
const render = progress => renderToString(createSSRApp({ render: () => h(Panel, { progress }) }))
const start = { runId: 'r', workflow: 'PAPER_DEEP_READ', topic: '<img src=x onerror=alert(1)>', operation: 'WORKFLOW', status: 'RUNNING', stage: 'ANALYZING', label: '精读论文', timestamp: 1000 }

test('SSR renders escaped plain text, real counts, operations and no chart/percent/ETA; creates no timer', async t => {
  const original = globalThis.setInterval
  let timers = 0
  globalThis.setInterval = (...args) => { timers++; return original(...args) }
  t.after(() => { globalThis.setInterval = original })
  let p = reduce(null, { ...start, counts: { total: 6, succeeded: 2, skipped: 0 } })
  p = reduce(p, { runId: 'r', operation: 'DOWNLOAD', status: 'SUCCEEDED', paper: '<script>bad</script>', detail: '<b>detail</b>', timestamp: 2000 })
  const html = await render(p)
  assert.equal(timers, 0)
  assert.match(html, /open/)
  assert.match(html, /论文精读/)
  assert.match(html, /总数：6/)
  assert.match(html, /成功：2/)
  assert.match(html, /跳过：0/)
  assert.doesNotMatch(html, /失败：0/)
  assert.match(html, /下载：成功/)
  assert.match(html, /&lt;script&gt;bad&lt;\/script&gt;/)
  assert.match(html, /&lt;b&gt;detail&lt;\/b&gt;/)
  assert.doesNotMatch(html, /<script>|<img|<svg|<canvas|progressbar|ETA|%/)
})

test('terminal and disconnected histories remain visible but folded, transport done never means PASS', async () => {
  let p = reduce(null, start)
  p = reduce(p, { ...start, operation: 'WORKFLOW', status: 'LIMIT_REACHED', stage: 'DONE', timestamp: 4000 })
  p = setProgressTransport(p, 'done')
  const html = await render(p)
  assert.doesNotMatch(html, /<details[^>]*\sopen(?:[\s=>])/)
  assert.match(html, /达到轮次上限/)
  assert.match(html, /不代表工作流审核通过/)
  assert.match(html, /已用时 3 秒/)
  assert.doesNotMatch(html, />审核通过</)
  const disconnected = await render(setProgressTransport(reduce(null, start), 'connection_lost'))
  assert.match(disconnected, /后台任务可能仍在继续/)
  assert.doesNotMatch(disconnected, /<details[^>]*\sopen(?:[\s=>])/)
})

test('legacy panel displays stage/detail without invented paper counts', async () => {
  const html = await render(reduce(null, { stage: 'ANALYZING', label: '精读', detail: '已精读 5/6 篇', timestamp: 1000 }))
  assert.match(html, /已精读 5\/6 篇/)
  assert.match(html, /尚未收到工作流计数/)
  assert.doesNotMatch(html, /论文操作|成功：5|总数：6/)
})
