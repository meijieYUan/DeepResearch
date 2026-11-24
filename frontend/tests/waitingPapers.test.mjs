import test from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'

const server = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, plugins: [vue()], optimizeDeps: { noDiscovery: true, include: [] }, server: { middlewareMode: true, hmr: false, ws: false, watch: null } })
test.after(() => server.close())
const { waitingText } = await server.ssrLoadModule('/src/composables/useChatStream.js')
const { default: MessageItem } = await server.ssrLoadModule('/src/components/MessageItem.vue')

// A short name is only a label: both of these are called Survey and they are different papers.
const waiting = {
  topic: 't',
  libraryPath: 'paper-library/papers',
  missingPapers: [
    { shortName: 'Survey', title: 'Deep Residual Learning for Image Recognition', doi: '10.1109/cvpr.2016.90', fileName: 'Survey-10.1109-cvpr.2016.90.pdf' },
    { shortName: 'Survey', arxivId: '1706.03762', fileName: 'Survey-1706.03762.pdf' }
  ]
}

test('the paused text tells two papers with one short name apart', () => {
  const text = waitingText(waiting)
  assert.match(text, /Deep Residual Learning/)
  assert.match(text, /DOI 10\.1109\/cvpr\.2016\.90/)
  assert.match(text, /arXiv 1706\.03762/)
  assert.match(text, /Survey-10\.1109-cvpr\.2016\.90\.pdf/)
  assert.match(text, /Survey-1706\.03762\.pdf/)
})

test('the paused panel lists both papers with the file name to use', async () => {
  const html = await renderToString(createSSRApp({
    render: () => h(MessageItem, { msg: { role: 'assistant', content: '', waiting } })
  }))
  assert.equal(html.match(/<li/g).length, 2)
  assert.match(html, /arXiv 1706\.03762/)
  // Scoped styles add an attribute to the tag.
  assert.match(html, /<code[^>]*>Survey-1706\.03762\.pdf<\/code>/)
  assert.match(html, /<code[^>]*>Survey-10\.1109-cvpr\.2016\.90\.pdf<\/code>/)
})

test('a paper known only by name still renders', () => {
  assert.match(waitingText({ libraryPath: '', missingPapers: [{ shortName: 'Survey' }] }), /- Survey$/m)
})
