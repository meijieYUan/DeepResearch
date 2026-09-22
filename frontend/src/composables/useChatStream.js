import { ref } from 'vue'
import { chatStream, approveStream } from '../api'
import { newId } from '../utils/id'

const TITLE_MAX = 40

/**
 * SSE stream lifecycle for one conversation view: the AbortController, the
 * single in-flight run, the streaming bubble's bookkeeping, and the workflow
 * progress that arrives on the same connection.
 *
 * `threads` / `activeThreadId` are the thread store's refs (injected, so both
 * stores address the same object graph). `planMode` / `planActive` are injected
 * too: the header renders them, but the server also reconciles them from the
 * answer / interruption frames, so the stream has to be able to write them.
 */
export function useChatStream({
  threads, activeThreadId, planMode, planActive,
  saveThreads, refreshTasks, scrollDown
}) {
  // A run is in flight: the composer stays disabled until the stream settles.
  const loading = ref(false)

  // Workflow progress, streamed on the same connection as the answer.
  const progressStage = ref('')
  const progressRound = ref(1)
  const progressFailed = ref(false)
  const progressDetail = ref('')

  function resetProgress() {
    progressStage.value = ''
    progressRound.value = 1
    progressFailed.value = false
    progressDetail.value = ''
  }

  // The single in-flight stream, if any. send() and submitApprovals() register
  // their AbortController and finalize hook here, so an unmount, a thread
  // switch, or a delete can stop the run and settle its bubble instead of
  // leaking the reader and leaving the bubble spinning. Only one run is ever in
  // flight, because the composer is disabled while `loading`.
  let liveStream = null

  // Stops the in-flight stream, optionally only when it belongs to `threadId`
  // (an unrelated switch must not kill a run in another conversation). Aborting
  // first stops the reader from delivering more frames; finalize then settles
  // the bubble so it can never be left stuck in the streaming state. No-op when
  // idle, and the finalize hook is idempotent, so a double call is harmless.
  function stopStream(threadId = null) {
    const s = liveStream
    if (!s || (threadId !== null && s.threadId !== threadId)) return
    liveStream = null
    s.controller.abort()
    s.finalize({ incomplete: true })
  }

  // The single entry point for a streamed run. It owns the AbortController and
  // the end-of-stream bookkeeping, so however the transport ends — terminal
  // event, abrupt close, network failure, or abort — the bubble is settled and
  // `loading` is released. `launch` receives the handlers and the abort signal.
  function runStream(threadId, bubble, launch) {
    const controller = new AbortController()
    const handlers = makeStreamHandlers(threadId, bubble)
    const slot = { threadId, controller, finalize: handlers.finalize }
    liveStream = slot
    loading.value = true

    launch(controller.signal, handlers)
      .catch(err => {
        // An abort is not a failure: stopStream already settled the bubble.
        if (err?.name === 'AbortError') return
        finalizeFailure(threadId, bubble, err?.message || 'Request failed')
      })
      .finally(() => {
        // The backstop for a stream that closed without a terminal event (the
        // server never sent `done`): without it the bubble would stream forever.
        handlers.finalize({ incomplete: true })
        if (liveStream === slot) liveStream = null
        loading.value = false
        scrollDown()
      })
  }

  // Everything one streamed run does to the transcript. The target is addressed
  // by threadId and re-resolved from threads.value on every event: loadThreads()
  // can swap in a fresh object graph, and a handler that had closed over the old
  // thread (or bubble) object would then write into an orphan that no view
  // renders — the answer would silently vanish. If the thread or bubble is gone,
  // the event is dropped rather than resurrecting stale state.
  //
  // `finalize` (attached to the returned handlers) is the one settle path,
  // shared by the server's `done` event and the transport's fallback in
  // runStream.
  function makeStreamHandlers(threadId, bubble) {
    resetProgress()
    let pendingDelta = ''
    let flushScheduled = false
    let finalized = false

    const liveThread = () => threads.value[threadId] || null
    // Resolve the bubble through the reactive array, never the closure variable.
    // The array stores the raw object it was pushed, so writing to `bubble`
    // directly notifies no effect: the memoised markdown computed in MessageItem
    // would never invalidate and streamed text would never paint (and the same
    // applies to `streaming` / `incomplete`). `t.messages[idx]` is the proxy, so
    // every read and write below is tracked.
    const liveBubble = () => {
      const t = liveThread()
      if (!t) return null
      const idx = (t.messages || []).indexOf(bubble)
      return idx >= 0 ? t.messages[idx] : null
    }
    const flushDeltas = () => {
      if (!pendingDelta) return
      const b = liveBubble()
      if (b) b.content += pendingDelta
      pendingDelta = ''
    }
    const touch = (t) => { if (t) t.lastActive = Date.now() }

    const handlers = {
      onProgress(p) {
        progressStage.value = p.label || ''
        progressRound.value = p.round || 1
        progressFailed.value = p.stage === 'FAILED'
        progressDetail.value = p.detail || ''
        scrollDown()
      },
      onDelta(d) {
        pendingDelta += d.text || ''
        // Coalesce token deltas into one DOM update per frame; re-rendering the
        // markdown for every chunk is wasted work the eye cannot see.
        if (!flushScheduled) {
          flushScheduled = true
          requestAnimationFrame(() => {
            flushScheduled = false
            flushDeltas()
            scrollDown()
          })
        }
      },
      onAnswer(p) {
        if (p.planEnabled !== undefined) planMode.value = p.planEnabled
        if (p.planActive !== undefined) planActive.value = p.planActive
        // The server's final text wins: it is the same run read from its settled
        // state, so a lost delta cannot leave the transcript truncated — drop the
        // unflushed tail instead of appending it after the authoritative text.
        pendingDelta = ''
        const b = liveBubble()
        if (b) {
          b.content = p.text || b.content
          b.streaming = false
          b.incomplete = false
        }
        touch(liveThread())
        saveThreads()
        refreshTasks()
        scrollDown()
      },
      onInterruption(p) {
        if (p.planEnabled !== undefined) planMode.value = p.planEnabled
        if (p.planActive !== undefined) planActive.value = p.planActive
        const t = liveThread()
        if (!t) return
        // The empty placeholder has nothing to show — the approval panel takes over.
        const idx = t.messages.indexOf(bubble)
        if (idx >= 0) t.messages.splice(idx, 1)
        const approvals = (p.pendingApprovals || []).map(a => ({
          ...a, id: newId('app'), decision: null, editing: false, editedArgs: a.arguments
        }))
        t.messages.push({
          id: newId('msg'), role: 'assistant',
          content: p.message || '', approvals, threadId: p.threadId
        })
        touch(t)
        saveThreads()
        refreshTasks()
        scrollDown()
      },
      onError(p) {
        const b = liveBubble()
        if (b) {
          b.content = '⚠️ Error: ' + (p.message || 'Unknown error')
          b.streaming = false
        }
        touch(liveThread())
        saveThreads()
        scrollDown()
      },
      // The server's terminal frame. Reaching it with the bubble still streaming
      // means no answer / interruption ever arrived, so the run was cut short.
      onDone() { handlers.finalize({ incomplete: true }) }
    }

    // Settles the bubble exactly once, however the run ended.
    handlers.finalize = ({ incomplete = false } = {}) => {
      if (finalized) return
      finalized = true
      const b = liveBubble()
      if (!b) return
      // Paint whatever the last coalescing frame had not yet flushed.
      flushDeltas()
      if (!b.streaming) return
      b.streaming = false
      if (incomplete) b.incomplete = true
      touch(liveThread())
      saveThreads()
      scrollDown()
    }

    return handlers
  }

  // The stream itself failed to open (network, non-200): nothing was ever streamed.
  function finalizeFailure(threadId, bubble, message) {
    const t = threads.value[threadId]
    if (!t) return
    const idx = (t.messages || []).indexOf(bubble)
    if (idx < 0) return
    // Same proxy resolution as liveBubble: the raw object would not notify.
    const b = t.messages[idx]
    if (!b.streaming) return
    b.content = '⚠️ Error: ' + message
    b.streaming = false
    t.lastActive = Date.now()
    saveThreads()
    scrollDown()
  }

  /**
   * Send a user turn and open the run that answers it. `text` is the trimmed
   * composer value, `mode` the plan-mode selection. Returns false when there is
   * no thread to send into.
   */
  function send(text, mode) {
    const threadId = activeThreadId.value
    const t = threadId ? threads.value[threadId] : null
    if (!t) return false
    // The first user turn names the conversation.
    if (!t.title && t.messages.length === 0) {
      t.title = text.length > TITLE_MAX ? text.substring(0, TITLE_MAX) + '...' : text
    }
    t.messages.push({ id: newId('msg'), role: 'user', content: text })
    t.lastActive = Date.now()
    saveThreads()
    scrollDown()

    // The streaming bubble: created up front, filled by deltas, finalized by the
    // terminal event. See makeStreamHandlers for the whole lifecycle.
    const bubble = { id: newId('msg'), role: 'assistant', content: '', streaming: true }
    t.messages.push(bubble)
    runStream(threadId, bubble, (signal, handlers) =>
      chatStream(threadId, text, mode, handlers, signal))
    return true
  }

  /**
   * Submit the user's decisions for an interruption. The resumed run streams on
   * its own connection: a fresh bubble fills with the revised answer, and it may
   * interrupt again on the next risky tool call.
   */
  function submitApprovals(msg) {
    const decisions = msg.approvals.map(a => {
      const d = { toolId: a.toolId, result: a.decision }
      if (a.decision === 'EDITED' && a.editedArgs) d.editedArguments = a.editedArgs
      return d
    })
    const threadId = activeThreadId.value
    const t = threads.value[threadId]
    if (!t) return
    const bubble = { id: newId('msg'), role: 'assistant', content: '', streaming: true }
    t.messages.push(bubble)
    runStream(threadId, bubble, (signal, handlers) =>
      approveStream(threadId, decisions, handlers, signal))
  }

  return {
    loading,
    progressStage, progressRound, progressFailed, progressDetail,
    resetProgress, stopStream, send, submitApprovals
  }
}
