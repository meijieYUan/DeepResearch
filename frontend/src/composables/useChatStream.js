import { getCurrentScope, onScopeDispose, ref, watch } from 'vue'
import { chatStream, approveStream } from '../api'
import { newId } from '../utils/id'
import { reduceResearchProgress, setProgressTransport } from '../utils/researchProgress'

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
  saveThreads, refreshTasks, scrollDown,
  requestChat = chatStream, requestApproval = approveStream
}) {
  // A run is in flight: the composer stays disabled until the stream settles.
  const loading = ref(false)

  let liveStream = null
  let generation = 0

  function stopStream(threadId = null) {
    const slot = liveStream
    if (!slot || (threadId !== null && slot.threadId !== threadId)) return
    // Settle before detaching: queued handlers, RAFs and the old finally then
    // lose ownership, even if the user goes A -> B -> A synchronously.
    slot.finalize('stopped', true)
    slot.controller.abort()
    if (liveStream === slot) {
      liveStream = null
      generation++
      loading.value = false
    }
  }

  watch(activeThreadId, () => stopStream(), { flush: 'sync' })
  if (getCurrentScope()) onScopeDispose(() => stopStream())

  function runStream(threadId, messageId, launch) {
    const slot = {
      threadId, messageId, generation: ++generation,
      controller: new AbortController(), finalized: false, receivedAnswer: false,
      pendingDelta: '', raf: null, finalize: null
    }
    liveStream = slot
    loading.value = true
    const ownsSlot = () => liveStream === slot && generation === slot.generation
    const liveThread = () => threads.value[threadId] || null
    // IDs survive loadThreads replacing all reactive message objects.
    const liveBubble = () => liveThread()?.messages?.find(m => m.id === messageId) || null
    const accepts = () => ownsSlot() && !slot.finalized && activeThreadId.value === threadId && !!liveBubble()
    const cancelFrame = () => {
      if (slot.raf !== null) globalThis.cancelAnimationFrame?.(slot.raf)
      slot.raf = null
    }
    const flushDeltas = () => {
      const b = liveBubble()
      if (b && slot.pendingDelta) b.content += slot.pendingDelta
      slot.pendingDelta = ''
    }
    const persist = () => {
      const t = liveThread()
      if (t) t.lastActive = Date.now()
      saveThreads()
      scrollDown()
    }
    const setTransport = (b, status) => {
      b.transportStatus = status
      if (b.progress) b.progress = setProgressTransport(b.progress, status)
    }
    slot.finalize = (status, incomplete = false, error = '') => {
      if (!ownsSlot() || slot.finalized) return
      cancelFrame()
      flushDeltas()
      slot.finalized = true
      const b = liveBubble()
      if (!b) return
      b.streaming = false
      b.incomplete = incomplete
      if (error) b.error = String(error).slice(0, 1024)
      setTransport(b, status)
      persist()
    }
    const reconcilePlan = p => {
      if (p.planEnabled !== undefined) planMode.value = p.planEnabled
      if (p.planActive !== undefined) planActive.value = p.planActive
    }
    const handlers = {
      onProgress(p) {
        if (!accepts()) return
        const b = liveBubble()
        b.progress = reduceResearchProgress(b.progress, p)
        b.progress = setProgressTransport(b.progress, b.transportStatus)
        persist()
      },
      onDelta(d) {
        if (!accepts() || slot.receivedAnswer) return
        slot.pendingDelta += typeof d?.text === 'string' ? d.text : ''
        if (slot.raf === null) {
          if (typeof globalThis.requestAnimationFrame !== 'function') { flushDeltas(); scrollDown(); return }
          slot.raf = globalThis.requestAnimationFrame(() => {
            slot.raf = null
            if (!accepts()) return
            flushDeltas()
            scrollDown()
          })
        }
      },
      onAnswer(p) {
        if (!accepts()) return
        reconcilePlan(p)
        cancelFrame()
        const b = liveBubble()
        if (typeof p.text === 'string' && p.text) { slot.pendingDelta = ''; b.content = p.text }
        else flushDeltas()
        b.streaming = false
        b.incomplete = false
        slot.receivedAnswer = true
        persist()
        refreshTasks()
      },
      onInterruption(p) {
        if (!accepts()) return
        reconcilePlan(p)
        cancelFrame()
        flushDeltas()
        // Reuse the bubble, keeping its id, partial text and progress snapshot.
        const b = liveBubble()
        if (typeof p.message === 'string' && p.message) b.content += (b.content ? '\n\n' : '') + p.message
        b.approvals = (Array.isArray(p.pendingApprovals) ? p.pendingApprovals : []).map(a => ({
          ...a, id: newId('app'), decision: null, editing: false, editedArgs: a.arguments
        }))
        b.streaming = false
        b.incomplete = false
        slot.receivedAnswer = true
        persist()
        refreshTasks()
      },
      onError(p) {
        if (!accepts()) return
        slot.finalize('error', true, p?.message || 'Unknown error')
      },
      onDone() {
        if (!accepts()) return
        // Transport done is NOT business PASS. Preserve the workflow's status.
        slot.finalize('done', !slot.receivedAnswer)
      }
    }
    Promise.resolve().then(() => {
      if (!accepts()) return
      return launch(slot.controller.signal, handlers)
    }).catch(err => {
      if (!accepts()) return
      slot.finalize(err?.name === 'AbortError' ? 'stopped' : 'error', true, err?.name === 'AbortError' ? '' : err?.message || 'Request failed')
    }).finally(() => {
      if (!ownsSlot()) return
      if (!slot.finalized) slot.finalize('connection_lost', true)
      // An old finally must never release a newer request's loading flag.
      if (ownsSlot()) { liveStream = null; loading.value = false }
    })
  }

  /**
   * Send a user turn and open the run that answers it. `text` is the trimmed
   * composer value, `mode` the plan-mode selection. Returns false when there is
   * no thread to send into.
   */
  function send(text, mode) {
    const threadId = activeThreadId.value
    const t = threadId ? threads.value[threadId] : null
    if (!t || loading.value || liveStream) return false
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
    const bubble = { id: newId('msg'), role: 'assistant', content: '', streaming: true, transportStatus: 'running' }
    t.messages.push(bubble)
    runStream(threadId, bubble.id, (signal, handlers) =>
      requestChat(threadId, text, mode, handlers, signal))
    return true
  }

  /**
   * Submit the user's decisions for an interruption. The resumed run streams on
   * its own connection: a fresh bubble fills with the revised answer, and it may
   * interrupt again on the next risky tool call.
   */
  function submitApprovals(msg) {
    if (loading.value || liveStream) return false
    const threadId = activeThreadId.value
    const t = threads.value[threadId]
    const source = t?.messages?.find(m => m.id === msg?.id)
    if (!source || source.approvalsSubmitted || !source.approvals?.length) return false
    if (!source.approvals.every(a => !a.editing && ['APPROVED', 'REJECTED', 'EDITED'].includes(a.decision))) return false
    const decisions = source.approvals.map(a => {
      const d = { toolId: a.toolId, result: a.decision }
      if (a.decision === 'EDITED' && a.editedArgs) d.editedArguments = a.editedArgs
      return d
    })
    // An approval group is single-flight and cannot be replayed after completion.
    source.approvalsSubmitted = true
    const bubble = { id: newId('msg'), role: 'assistant', content: '', streaming: true, transportStatus: 'running' }
    t.messages.push(bubble)
    saveThreads()
    runStream(threadId, bubble.id, (signal, handlers) =>
      requestApproval(threadId, decisions, handlers, signal))
    return true
  }

  return { loading, stopStream, send, submitApprovals }
}
