import axios from 'axios'
import { toast } from '../utils/toast'

const api = axios.create({ baseURL: '/api', timeout: 120000 })

api.interceptors.response.use(
  response => response,
  error => {
    // `silent: true` on a request opts out of the global toast — used by the
    // health poll, whose failure is already shown by the sidebar status chip and
    // would otherwise toast on every tick while the backend is down.
    if (!error.config?.silent) {
      const msg = error.response?.data?.message || error.message || 'Request failed'
      toast(msg, 'error')
    }
    return Promise.reject(error)
  }
)

// Health — polled on a timer, so it fails fast on its own short timeout (the
// global 120s would keep a dead backend looking "pending") and never toasts.
export const getHealth = () => api.get('/health', { silent: true, timeout: 5000 })

// Chat — the POST response body IS the run: an SSE stream carrying
// progress / delta / answer / interruption / error events, closed by done.
// EventSource cannot issue a POST, so the stream is parsed from a fetch reader.
//
// handlers: { onProgress, onDelta, onAnswer, onInterruption, onError, onDone }
// — each receives the parsed JSON payload (delta: {text}, answer: {text, threadId,
// planEnabled, planActive}, ...). `signal` (an AbortSignal) cancels the request and
// the reader loop; aborting rejects the promise with an AbortError. The returned
// promise resolves when the stream closes; it rejects if the stream never started
// (network/HTTP error) or is aborted. A stream can also close without ever sending
// `done`, so callers must not rely on onDone alone to finalize state.
export async function streamChatEvents(threadId, path, body, handlers, signal) {
  const res = await fetch(`/api/chat/${threadId}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
    signal
  })
  if (!res.ok || !res.body) {
    throw new Error(`Chat stream failed to open (HTTP ${res.status})`)
  }

  const dispatch = (name, data) => {
    const handler = handlers['on' + name.charAt(0).toUpperCase() + name.slice(1)]
    if (handler) handler(data)
  }
  const dispatchFrame = () => {
    if (dataLines.length === 0) { eventName = 'message'; return }
    const raw = dataLines.join('\n')
    dataLines = []
    const name = eventName
    eventName = 'message'
    try {
      const payload = raw ? JSON.parse(raw) : {}
      if (name === 'done') handlers.onDone?.(payload)
      else if (name === 'message') { /* an unnamed frame carries nothing we render */ }
      else dispatch(name, payload)
    } catch { /* a malformed frame is not worth breaking the stream over */ }
  }

  let eventName = 'message'
  let dataLines = []
  let buffer = ''
  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let nl
    while ((nl = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, nl).replace(/\r$/, '')
      buffer = buffer.slice(nl + 1)
      if (line === '') { dispatchFrame(); continue }
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  dispatchFrame()
}

export const chatStream = (threadId, message, mode, handlers, signal) =>
  streamChatEvents(threadId, '', { message, mode }, handlers, signal)

export const approveStream = (threadId, decisions, handlers, signal) =>
  streamChatEvents(threadId, '/approve', { decisions }, handlers, signal)

// Todos — every query is scoped to a conversation threadId
export const getTodos = (threadId) => api.get('/todos', { params: { threadId } })
export const getPendingTodos = (threadId) => api.get('/todos/pending', { params: { threadId } })
export const getOverdueTodos = (threadId) => api.get('/todos/overdue', { params: { threadId } })
export const queryTodos = (threadId, status, priority, keyword) =>
  api.post('/todos/query', { threadId, status, priority, keyword })

// Knowledge
export const uploadKnowledge = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/knowledge/upload', fd)
}
