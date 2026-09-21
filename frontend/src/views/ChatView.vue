<template>
  <div class="chat-layout">
    <!-- Thread sidebar -->
    <div
      v-if="!threadCollapsed"
      :class="['thread-sidebar', { resizing: threadResizing }]"
      :style="{ '--ts-width': threadWidth + 'px' }"
    >
      <button class="btn-primary new-thread-btn" @click="newThread">
        <Plus :size="16" /> New Chat
      </button>
      <div class="thread-list scrollbar">
        <div
          v-for="t in threadList"
          :key="t.id"
          :class="['thread-item', { active: t.id === activeThreadId }]"
          @click="switchThread(t.id)"
        >
          <div class="thread-item-main">
            <span class="thread-title">{{ t.title || 'New conversation' }}</span>
            <span class="thread-time">{{ formatTime(t.lastActive) }}</span>
          </div>
          <button class="thread-delete" @click.stop="deleteThread(t.id)" title="Delete">
            <Trash2 :size="13" />
          </button>
        </div>
        <div v-if="threadList.length === 0" class="no-threads">
          No conversations yet
        </div>
      </div>
      <div class="resize-handle" title="Drag to resize" @mousedown="startThreadResize"></div>
    </div>

    <!-- Chat area -->
    <div class="chat-main">
      <div class="chat-header">
        <div class="header-left">
          <button
            class="icon-btn"
            :title="threadCollapsed ? 'Show conversations' : 'Hide conversations'"
            @click="toggleThreadSidebar"
          >
            <PanelLeftOpen v-if="threadCollapsed" :size="16" />
            <PanelLeftClose v-else :size="16" />
          </button>
          <span class="thread-label">{{ activeThread ? activeThread.title || 'New conversation' : 'Select a conversation' }}</span>
        </div>
        <label :class="['plan-toggle', { on: planMode }]" title="Enable Plan Mode: when ON, the assistant may enter plan mode for complex tasks">
          <input type="checkbox" v-model="planMode" />
          <span class="toggle-label">Plan Mode</span>
          <span v-if="planMode && planActive" class="plan-active-badge">Planning</span>
        </label>
      </div>

      <div class="chat-messages scrollbar" ref="msgContainer">
        <div v-if="messages.length === 0" class="empty-state">
          <div class="empty-icon"><MessageSquare :size="28" /></div>
          <p>Start a conversation with your AI assistant</p>
          <p class="empty-hint">Supports tasks, code, research &amp; more</p>
        </div>

        <div v-for="(msg, i) in messages" :key="i" :class="['message', msg.role]">
          <div class="msg-avatar">
            <User v-if="msg.role === 'user'" :size="16" />
            <Bot v-else :size="16" />
          </div>
          <div class="msg-body">
            <div v-if="msg.role === 'user'" class="msg-text">{{ msg.content }}</div>
            <div v-else class="msg-text" :class="{ streaming: msg.streaming }">
              <!-- A streaming bubble starts empty: typing dots until the first delta,
                   then the markdown body grows in place as deltas land. -->
              <div v-if="msg.streaming && !msg.content" class="typing-dots">
                <span></span><span></span><span></span>
              </div>
              <div v-else class="stream-content" v-html="renderMarkdown(msg.content)" @click="handleCopyClick"></div>
              <!-- Workflow stage, streamed on the same connection: the user can tell
                   "still searching / reading paper 3/6" from "stuck" while the model
                   itself has nothing incremental to say. -->
              <div v-if="msg.streaming && progressStage" class="progress-stage">
                <span class="progress-stage-dot" :class="{ failed: progressFailed }"></span>
                <span class="progress-stage-label">{{ progressStage }}</span>
                <span v-if="progressRound > 1" class="progress-stage-round">第 {{ progressRound }} 轮</span>
                <span v-if="progressDetail" class="progress-stage-detail">{{ progressDetail }}</span>
              </div>
            </div>
            <!-- The connection closed without the run's terminal event (no done /
                 answer / interruption): the text above is only what got through,
                 so say so rather than passing a truncated answer off as final. -->
            <div v-if="msg.incomplete" class="incomplete-note">
              <AlertTriangle :size="12" />
              <span>连接中断，回答可能不完整</span>
            </div>
            <div v-if="msg.approvals" class="approval-panel card">
              <div class="approval-header">
                <AlertTriangle :size="16" />
                <span>Approval Required — {{ msg.approvals.length }} action(s) pending</span>
              </div>
              <div v-for="(app, j) in msg.approvals" :key="j" class="approval-item">
                <div class="approval-tool">
                  <span class="badge badge-high">{{ app.toolName }}</span>
                  <span class="approval-desc">{{ app.description }}</span>
                </div>
                <div class="approval-args">
                  <pre>{{ formatArgs(app.arguments) }}</pre>
                  <textarea v-if="app.editing" v-model="app.editedArgs" rows="3" placeholder="Edit arguments (JSON)..."></textarea>
                </div>
                <div class="approval-actions">
                  <template v-if="app.editing">
                    <!-- While editing, Confirm emits an EDITED decision so the backend
                         (HITLHelper.applyDecision) swaps in the edited arguments. -->
                    <button class="btn-success btn-sm" @click="confirmEdit(app)">Confirm Edit</button>
                    <button class="btn-ghost btn-sm" @click="cancelEdit(app)">Cancel</button>
                  </template>
                  <template v-else>
                    <button class="btn-success btn-sm" @click="decide(app, 'APPROVED')">Approve</button>
                    <button class="btn-danger btn-sm" @click="decide(app, 'REJECTED')">Reject</button>
                    <button class="btn-ghost btn-sm" @click="startEdit(app)">Edit</button>
                  </template>
                </div>
              </div>
              <button class="btn-primary" @click="submitApprovals(msg)" :disabled="!allDecided(msg)">
                Submit Approvals
              </button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="taskList.length > 0" class="task-panel">
        <button class="task-panel-header" :aria-expanded="tasksOpen" @click="tasksOpen = !tasksOpen">
          <ChevronRight :size="14" :class="['task-chevron', { open: tasksOpen }]" />
          <span class="task-eyebrow">Tasks</span>
          <span class="task-count">{{ completedCount }}/{{ taskList.length }}</span>
          <span class="task-bar"><span class="task-bar-fill" :style="{ width: progressPct + '%' }"></span></span>
          <span class="task-hint">{{ tasksOpen ? 'Hide' : 'Show' }}</span>
        </button>

        <div v-if="tasksOpen" class="task-panel-body scrollbar">
          <div v-for="t in taskList" :key="t.id" class="task-row">
            <div class="task-row-main">
              <span :class="'task-status ' + (t.status || '').toLowerCase()">{{ t.status }}</span>
              <span class="task-row-title">{{ t.title }}</span>
              <span v-if="t.priority" :class="'badge badge-' + prioClass(t.priority)">{{ t.priority }}</span>
              <span v-if="t.dueDate" class="task-row-date">{{ formatDue(t.dueDate) }}</span>
            </div>
            <p v-if="t.objective" class="task-line objective">{{ t.objective }}</p>
            <p v-if="t.outputSummary" class="task-line output">{{ t.outputSummary }}</p>
            <p v-if="t.errorMessage" class="task-line error">{{ t.errorMessage }}</p>
          </div>
        </div>
      </div>

      <div class="composer">
        <div class="composer-inner">
          <textarea
            ref="inputRef"
            v-model="input"
            @keydown="onKeydown"
            placeholder="Type a message... (Enter to send, Shift+Enter for new line)"
            :disabled="loading || !activeThreadId"
            rows="1"
          ></textarea>
          <button class="btn-primary send-btn" @click="send" :disabled="!input.trim() || loading || !activeThreadId">
            <Send :size="16" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, watch, onMounted, onUnmounted } from 'vue'
import { MessageSquare, Send, AlertTriangle, Plus, Trash2, User, Bot, ChevronRight, PanelLeftClose, PanelLeftOpen } from 'lucide-vue-next'
import { chatStream, approveStream, getTodos } from '../api'
import { renderMarkdown, handleCopyClick } from '../utils/markdown'
import { toast } from '../utils/toast'

const STORAGE_KEY = 'sa_threads'
const ACTIVE_KEY = 'sa_active_thread'

// Persistent threads store
const threads = ref({})
const activeThreadId = ref(null)
const planMode = ref(false)
const planActive = ref(false)
const taskList = ref([])
const tasksOpen = ref(false)

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

const THREAD_SIDEBAR_KEY = 'sa_thread_sidebar'
const THREAD_DEFAULT = 264
const THREAD_MIN = 200
const THREAD_MAX = 380
const threadWidth = ref(THREAD_DEFAULT)
const threadCollapsed = ref(false)
const threadResizing = ref(false)

function loadThreadSidebarPrefs() {
  try {
    const p = JSON.parse(localStorage.getItem(THREAD_SIDEBAR_KEY) || '{}')
    if (typeof p.width === 'number') threadWidth.value = clampThreadWidth(p.width)
    threadCollapsed.value = !!p.collapsed
  } catch { /* keep defaults */ }
}

function saveThreadSidebarPrefs() {
  localStorage.setItem(THREAD_SIDEBAR_KEY, JSON.stringify({
    width: threadWidth.value, collapsed: threadCollapsed.value
  }))
}

function clampThreadWidth(w) {
  return Math.min(THREAD_MAX, Math.max(THREAD_MIN, Math.round(w)))
}

function toggleThreadSidebar() {
  threadCollapsed.value = !threadCollapsed.value
  saveThreadSidebarPrefs()
}

function startThreadResize(e) {
  e.preventDefault()
  const startX = e.clientX
  const startW = threadWidth.value
  threadResizing.value = true
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'

  const onMove = (ev) => { threadWidth.value = clampThreadWidth(startW + ev.clientX - startX) }
  const onUp = () => {
    threadResizing.value = false
    document.body.style.userSelect = ''
    document.body.style.cursor = ''
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    saveThreadSidebarPrefs()
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

const input = ref('')
const inputRef = ref(null)
const loading = ref(false)
const msgContainer = ref(null)

// The single in-flight stream, if any. send() and submitApprovals() register their
// AbortController and finalize hook here, so an unmount, a thread switch, or a
// delete can stop the run and settle its bubble instead of leaking the reader and
// leaving the bubble spinning. Only one run is ever in flight, because the
// composer is disabled while `loading`.
let liveStream = null

// Computed
const activeThread = computed(() => threads.value[activeThreadId.value] || null)
const messages = computed(() => {
  if (!activeThread.value) return []
  return activeThread.value.messages || []
})
const threadList = computed(() => {
  return Object.values(threads.value).sort((a, b) => b.lastActive - a.lastActive)
})
const completedCount = computed(() => taskList.value.filter(t => t.status === 'COMPLETED').length)
const progressPct = computed(() =>
  taskList.value.length ? Math.round(completedCount.value / taskList.value.length * 100) : 0)

// LocalStorage persistence
function loadThreads() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) threads.value = JSON.parse(raw)
  } catch { threads.value = {} }
}

function saveThreads() {
  // Strip non-serializable reactive flags before saving
  const clean = {}
  for (const [id, t] of Object.entries(threads.value)) {
    clean[id] = {
      id: t.id,
      title: t.title,
      lastActive: t.lastActive,
      messages: (t.messages || []).map(m => {
        const { role, content } = m
        const c = { role, content }
        // Keep the "may be incomplete" flag across reloads: the truncation is a
        // property of the stored answer, not of this session.
        if (m.incomplete) c.incomplete = true
        if (m.approvals) {
          c.approvals = m.approvals.map(a => ({
            toolId: a.toolId, toolName: a.toolName,
            arguments: a.arguments, description: a.description
          }))
        }
        return c
      })
    }
  }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(clean))
  } catch {
    // A full quota (or a locked-down storage) must never bubble out: send() calls
    // this mid-flight, and a throw there would skip the rest of send() and leave
    // the composer disabled for good. Warn and carry on with in-memory threads.
    // The toast is deduped, so repeated failures don't stack.
    toast('无法保存对话到本地存储（空间已满）', 'error')
  }
}

function ensureThread() {
  if (!activeThreadId.value) {
    newThread()
  }
}

function newThread() {
  const id = 'thr-' + Date.now().toString(36)
  threads.value[id] = { id, title: '', lastActive: Date.now(), messages: [] }
  activeThreadId.value = id
  input.value = ''
  persistActive(id)
  saveThreads()
  refreshTasks()
  scrollDown()
}

function switchThread(id) {
  // Leaving a conversation mid-answer cancels its stream: the reader would keep
  // running invisibly otherwise, and its bubble must not be left streaming.
  if (id !== activeThreadId.value) stopStream(activeThreadId.value)
  activeThreadId.value = id
  input.value = ''
  persistActive(id)
  refreshTasks()
  scrollDown()
}

function deleteThread(id) {
  stopStream(id)
  delete threads.value[id]
  if (activeThreadId.value === id) {
    const remaining = threadList.value
    activeThreadId.value = remaining.length > 0 ? remaining[0].id : null
    persistActive(activeThreadId.value)
    refreshTasks()
  }
  saveThreads()
}

// The active thread is persisted so views can follow the current chat.
function persistActive(id) {
  if (id) localStorage.setItem(ACTIVE_KEY, id)
  else localStorage.removeItem(ACTIVE_KEY)
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

function adjustTextarea() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

watch(input, adjustTextarea)

function send() {
  if (!input.value.trim() || loading.value || !activeThreadId.value) return
  const msg = input.value.trim()
  const threadId = activeThreadId.value
  const t = threads.value[threadId]
  if (!t) return
  if (!t.title && t.messages.length === 0) {
    t.title = msg.length > 40 ? msg.substring(0, 40) + '...' : msg
  }
  t.messages.push({ role: 'user', content: msg })
  t.lastActive = Date.now()
  input.value = ''
  nextTick(adjustTextarea)
  loading.value = true
  saveThreads()
  scrollDown()

  // The streaming bubble: created up front, filled by deltas, finalized by the
  // terminal event. See makeStreamHandlers for the whole lifecycle.
  const bubble = { role: 'assistant', content: '', streaming: true }
  t.messages.push(bubble)
  runStream(threadId, bubble, (signal, handlers) =>
    chatStream(threadId, msg, planMode.value ? 'PlanMode' : 'Default', handlers, signal))
}

// Stops the in-flight stream, optionally only when it belongs to `threadId` (an
// unrelated switch must not kill a run in another conversation). Aborting first
// stops the reader from delivering more frames; finalize then settles the bubble
// so it can never be left stuck in the streaming state. No-op when idle, and the
// finalize hook is idempotent, so a double call is harmless.
function stopStream(threadId = null) {
  const s = liveStream
  if (!s || (threadId !== null && s.threadId !== threadId)) return
  liveStream = null
  s.controller.abort()
  s.finalize({ incomplete: true })
}

// The single entry point for a streamed run. It owns the AbortController and the
// end-of-stream bookkeeping, so however the transport ends — terminal event,
// abrupt close, network failure, or abort — the bubble is settled and `loading`
// is released. `launch` receives the handlers and the abort signal.
function runStream(threadId, bubble, launch) {
  const controller = new AbortController()
  const handlers = makeStreamHandlers(threadId, bubble)
  const slot = { threadId, controller, finalize: handlers.finalize }
  liveStream = slot

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

// Everything one streamed run does to the transcript. The target is addressed by
// threadId and re-resolved from threads.value on every event: loadThreads() can
// swap in a fresh object graph, and a handler that had closed over the old thread
// (or bubble) object would then write into an orphan that no view renders — the
// answer would silently vanish. If the thread or bubble is gone, the event is
// dropped rather than resurrecting stale state.
//
// `finalize` (attached to the returned handlers) is the one settle path, shared by
// the server's `done` event and the transport's fallback in runStream.
function makeStreamHandlers(threadId, bubble) {
  resetProgress()
  let pendingDelta = ''
  let flushScheduled = false
  let finalized = false

  const liveThread = () => threads.value[threadId] || null
  const liveBubble = () => {
    const t = liveThread()
    return t && (t.messages || []).includes(bubble) ? bubble : null
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
        ...a, decision: null, editing: false, editedArgs: a.arguments
      }))
      t.messages.push({ role: 'assistant', content: p.message || '', approvals, threadId: p.threadId })
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
  const b = t && (t.messages || []).includes(bubble) ? bubble : null
  if (!b || !b.streaming) return
  b.content = '⚠️ Error: ' + message
  b.streaming = false
  t.lastActive = Date.now()
  saveThreads()
  scrollDown()
}

function decide(app, result) { app.decision = result }
function allDecided(msg) { return msg.approvals && msg.approvals.every(a => a.decision) }

// Edit flow: Entering edit mode re-seeds the draft from the original arguments each
// time, so cancel-and-reopen always starts clean. Confirm validates the draft as JSON
// (tool arguments are JSON strings) and only then records an EDITED decision, which is
// what makes submitApprovals send editedArguments to the backend.
function startEdit(app) {
  app.editedArgs = app.arguments
  app.editing = true
}
function cancelEdit(app) {
  app.editing = false
}
function confirmEdit(app) {
  try { JSON.parse(app.editedArgs) }
  catch { toast('Edited arguments must be valid JSON', 'error'); return }
  app.decision = 'EDITED'
  app.editing = false
}

function submitApprovals(msg) {
  const decisions = msg.approvals.map(a => {
    const d = { toolId: a.toolId, result: a.decision }
    if (a.decision === 'EDITED' && a.editedArgs) d.editedArguments = a.editedArgs
    return d
  })
  const threadId = activeThreadId.value
  const t = threads.value[threadId]
  if (!t) return
  loading.value = true
  // The resumed run streams on its own connection: a fresh bubble fills with the
  // revised answer, and it may interrupt again on the next risky tool call.
  const bubble = { role: 'assistant', content: '', streaming: true }
  t.messages.push(bubble)
  runStream(threadId, bubble, (signal, handlers) =>
    approveStream(threadId, decisions, handlers, signal))
}

function formatArgs(args) {
  try { return JSON.stringify(JSON.parse(args), null, 2) } catch { return args }
}
function prioClass(p) {
  return { URGENT: 'high', HIGH: 'high', MEDIUM: 'medium', LOW: 'low' }[p] || 'low'
}
function formatDue(d) {
  const s = String(d)
  return s.length > 10 ? s.slice(0, 10) : s
}
function refreshTasks() {
  if (!activeThreadId.value) { taskList.value = []; return }
  getTodos(activeThreadId.value)
    .then(r => {
      const rank = { RUNNING: 0, PENDING: 1, FAILED: 2, COMPLETED: 3, CANCELLED: 4 }
      taskList.value = (r.data || []).slice()
        .sort((a, b) => (rank[a.status] ?? 9) - (rank[b.status] ?? 9))
    })
    .catch(() => {})
}
function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const diff = now - d
  if (diff < 60000) return 'Just now'
  if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago'
  if (diff < 86400000) return Math.floor(diff / 3600000) + 'h ago'
  return d.toLocaleDateString()
}
function scrollDown() {
  nextTick(() => {
    if (msgContainer.value) msgContainer.value.scrollTop = msgContainer.value.scrollHeight
  })
}

onMounted(() => {
  loadThreadSidebarPrefs()
  loadThreads()
  if (threadList.value.length > 0) {
    const saved = localStorage.getItem(ACTIVE_KEY)
    const restored = saved && threads.value[saved] ? saved : threadList.value[0].id
    activeThreadId.value = restored
    persistActive(restored)
    refreshTasks()
  }
})

// A route change unmounts the view while a run may still be streaming: abort it,
// or the reader lives on holding a dead component and the run never settles.
onUnmounted(() => stopStream())
</script>

<style scoped>
/* The chat area is a fixed-width card centred in the space beside the thread
   sidebar. The sidebar stays flush and full-height; the card floats on the
   ambient background, so an ultrawide monitor widens the margin rather than
   stretching the reading column. `gap` guarantees the card never butts up
   against the sidebar once the auto margins have collapsed. */
.chat-layout { display: flex; height: 100%; --chat-width: 1100px; gap: 14px; }

.thread-sidebar {
  position: relative;
  width: var(--ts-width, 264px); flex-shrink: 0; border-right: 1px solid var(--border);
  display: flex; flex-direction: column; gap: 14px; padding: 16px 14px;
  background: var(--bg2);
}
.resize-handle {
  position: absolute; top: 0; right: -3px; width: 7px; height: 100%;
  cursor: col-resize; z-index: 20;
}
.resize-handle::after {
  content: ''; position: absolute; top: 0; left: 2px; width: 2px; height: 100%;
  background: transparent; transition: background .15s;
}
.resize-handle:hover::after, .resize-handle:active::after { background: var(--accent); }
.new-thread-btn { width: 100%; display: flex; align-items: center; justify-content: center; gap: 7px; padding: 10px; }
.thread-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 3px; margin: 0 -4px; padding: 0 4px; }
.thread-item {
  display: flex; align-items: center; gap: 8px; padding: 9px 11px; border-radius: var(--radius-sm);
  cursor: pointer; transition: all .13s ease; position: relative;
}
.thread-item:hover { background: var(--bg3); }
.thread-item.active { background: var(--accent-soft); box-shadow: inset 2px 0 0 var(--accent); }
.thread-item-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.thread-title { font-size: 13px; font-weight: 500; letter-spacing: -.005em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.thread-item.active .thread-title { color: var(--accent); font-weight: 600; }
.thread-time { font-size: 11px; color: var(--text2); font-variant-numeric: tabular-nums; }
.thread-delete {
  opacity: 0; padding: 4px; border-radius: 5px; background: transparent; color: var(--red);
  transition: opacity .13s;
}
.thread-item:hover .thread-delete { opacity: 1; }
.thread-delete:hover { background: rgba(220,38,38,.10); }
.no-threads { padding: 24px 12px; font-size: 12.5px; color: var(--text2); text-align: center; }

.chat-main {
  flex: 0 1 var(--chat-width); width: 100%; max-width: var(--chat-width);
  /* Centred by auto margins; `flex: 0 1` keeps the card from stretching once the
     sidebar leaves more room than the card needs. */
  margin: 14px auto; min-width: 0; min-height: 0;
  display: flex; flex-direction: column;
  background: var(--bg2); border: 1px solid var(--border);
  border-radius: var(--radius); box-shadow: var(--shadow-lg);
  overflow: hidden;
}
.chat-header {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  height: 58px; padding: 0 24px; flex-shrink: 0;
  background: var(--bg2); border-bottom: 1px solid var(--border);
}
.header-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.icon-btn {
  display: flex; align-items: center; justify-content: center; padding: 6px;
  background: transparent; color: var(--text2); border-radius: 7px; flex-shrink: 0;
  transition: all .15s;
}
.icon-btn:hover { background: var(--bg3); color: var(--text); }
.thread-label {
  font-size: 14px; font-weight: 600; letter-spacing: -.015em; color: var(--text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.chat-messages { flex: 1; overflow-y: auto; padding: 30px 24px 6px; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; gap: 10px; color: var(--text2); }
.empty-icon {
  width: 64px; height: 64px; border-radius: 18px; margin-bottom: 6px;
  background: linear-gradient(160deg, var(--accent-soft-2), var(--accent-soft));
  border: 1px solid var(--border); color: var(--accent);
  display: flex; align-items: center; justify-content: center;
}
.empty-state p { font-size: 14.5px; font-weight: 500; color: var(--text); }
.empty-state .empty-hint { font-size: 12.5px; color: var(--text2); font-weight: 400; opacity: .75; }

/* Typing indicator */
.typing-dots { display: flex; gap: 4px; padding: 10px 14px; }
.typing-dots span {
  width: 7px; height: 7px; border-radius: 50%; background: var(--text2);
  animation: dotBounce 1.4s ease-in-out infinite;
}
.typing-dots span:nth-child(2) { animation-delay: .16s; }
.typing-dots span:nth-child(3) { animation-delay: .32s; }
@keyframes dotBounce { 0%, 60%, 100% { opacity: .3; transform: scale(.8); } 30% { opacity: 1; transform: scale(1); } }

/* Workflow stage, streamed over SSE while a long request runs */
.progress-stage {
  display: flex; align-items: center; gap: 7px;
  padding: 0 14px 10px; font-size: 12.5px; color: var(--text2);
}
.progress-stage-dot {
  width: 6px; height: 6px; border-radius: 50%; background: var(--accent, #4f8cff);
  animation: stagePulse 1.6s ease-in-out infinite; flex: none;
}
.progress-stage-dot.failed { background: var(--danger, #e5484d); animation: none; }
@keyframes stagePulse { 0%, 100% { opacity: .35; } 50% { opacity: 1; } }
.progress-stage-round {
  margin-left: 2px; padding: 1px 6px; border-radius: 999px;
  background: var(--surface2, rgba(127,127,127,.14)); font-size: 11px; opacity: .85;
}
.progress-stage-detail { opacity: .75; }

.message { display: flex; gap: 12px; max-width: 100%; margin: 0 auto 22px; animation: msgIn .28s ease; }
@keyframes msgIn { from { opacity: 0; transform: translateY(8px); } }
.message.user { flex-direction: row-reverse; }
.msg-avatar {
  width: 32px; height: 32px; border-radius: 10px; display: flex;
  align-items: center; justify-content: center; flex-shrink: 0;
}
.message.assistant .msg-avatar { background: linear-gradient(135deg, var(--accent), var(--accent2)); color: #fff; box-shadow: 0 2px 8px rgba(79,70,229,.28); }
.message.user .msg-avatar { background: var(--bg3); color: var(--text2); border: 1px solid var(--border); }
.msg-body { max-width: 78%; min-width: 0; }
.msg-text { padding: 11px 15px; border-radius: 14px; font-size: 13.5px; line-height: 1.7; }
.message.assistant .msg-text { background: var(--bg2); border: 1px solid var(--border); box-shadow: var(--shadow); border-top-left-radius: 5px; }
.message.user .msg-text { background: var(--accent); color: #fff; border-top-right-radius: 5px; white-space: pre-wrap; word-break: break-word; }
/* Shown when a stream ended without its terminal event: the bubble above holds
   only what arrived, so it must not read as a finished answer. */
.incomplete-note {
  display: flex; align-items: center; gap: 6px; margin-top: 6px; padding: 0 4px;
  font-size: 11.5px; color: var(--yellow, #b45309);
}
.approval-panel { margin-top: 10px; }
.approval-header { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; color: var(--yellow); font-weight: 600; font-size: 13px; }
.approval-item { margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px solid var(--border); }
.approval-tool { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.approval-desc { font-size: 12px; color: var(--text2); }
.approval-args { margin-bottom: 8px; }
.approval-args pre { font-size: 11px; background: var(--bg); padding: 8px; border-radius: 4px; overflow-x: auto; margin-bottom: 6px; }
.approval-args textarea { width: 100%; }
.approval-actions { display: flex; gap: 6px; }
.composer { flex-shrink: 0; padding: 14px 24px 20px; background: var(--bg2); border-top: 1px solid var(--border); }
.composer-inner {
  display: flex; gap: 8px; align-items: flex-end; max-width: 100%; margin: 0 auto;
  background: var(--bg); border: 1px solid var(--border-strong); border-radius: 16px;
  padding: 6px 6px 6px 14px;
  transition: border-color .15s, box-shadow .15s;
}
.composer-inner:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft-2); }
.composer-inner textarea {
  flex: 1; background: transparent; border: none; border-radius: 0; box-shadow: none;
  color: var(--text); padding: 9px 0; font-size: 13.5px; outline: none; resize: none;
  line-height: 1.55; font-family: inherit; min-height: 38px; max-height: 200px;
}
.send-btn { padding: 9px; border-radius: 11px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }

@media (max-width: 720px) {
  .chat-layout { position: relative; gap: 0; }
  .thread-sidebar { display: none; }
  .header-left .icon-btn { display: none; }
  /* Below the card width the floating shell is all margin and no content: drop
     it so the chat uses the full viewport instead of shrinking into a strip. */
  .chat-main { margin: 0; border: none; border-radius: 0; box-shadow: none; }
  .chat-messages, .composer { padding-left: 14px; padding-right: 14px; }
  .task-panel { width: calc(100% - 28px); }
  .msg-body { max-width: 86%; }
}

.plan-toggle {
  display: flex; align-items: center; gap: 7px; cursor: pointer; font-size: 12px;
  color: var(--text2); user-select: none; padding: 5px 11px; border-radius: 999px;
  border: 1px solid var(--border); background: var(--bg); transition: all .15s;
}
.plan-toggle:hover { border-color: var(--border-strong); color: var(--text); }
.plan-toggle.on {
  background: var(--accent-soft); border-color: transparent; color: var(--accent);
}
.plan-toggle.on:hover { border-color: transparent; color: var(--accent); }
.plan-toggle.on .toggle-label { font-weight: 600; }
.plan-toggle input { accent-color: var(--accent); cursor: pointer; margin: 0; }
.toggle-label { font-weight: 550; }

/* Collapsible task panel */
.task-panel {
  width: calc(100% - 48px); max-width: 100%; margin: 0 auto 10px;
  background: var(--bg2); border: 1px solid var(--border); border-radius: var(--radius);
  box-shadow: var(--shadow); overflow: hidden;
}
.task-panel-header {
  display: flex; align-items: center; gap: 9px; width: 100%;
  padding: 11px 15px; background: transparent; border-radius: 0;
  color: var(--text2); font-weight: 500; text-align: left;
}
.task-panel-header:hover { background: var(--bg3); }
.task-chevron { flex-shrink: 0; transition: transform .18s ease; }
.task-chevron.open { transform: rotate(90deg); }
.task-eyebrow { font-size: 10.5px; font-weight: 700; text-transform: uppercase; letter-spacing: .08em; color: var(--text2); }
.task-count { font-size: 11.5px; font-weight: 600; color: var(--text2); font-variant-numeric: tabular-nums; }
.task-bar { flex: 1; height: 3px; border-radius: 2px; background: var(--bg3); overflow: hidden; }
.task-bar-fill { display: block; height: 100%; border-radius: 2px; background: var(--accent); transition: width .25s ease; }
.task-hint { font-size: 11px; color: var(--text2); opacity: .8; }

.task-panel-body { max-height: 260px; overflow-y: auto; padding: 4px 15px 12px; border-top: 1px solid var(--border); }
.task-row { padding: 9px 0; border-bottom: 1px solid var(--border); }
.task-row:last-child { border-bottom: none; }
.task-row-main { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
.task-row-title { font-size: 13px; font-weight: 600; letter-spacing: -.005em; }
.task-row-date { font-size: 11px; color: var(--text2); font-variant-numeric: tabular-nums; }
.task-line { font-size: 12px; margin-top: 4px; line-height: 1.5; }
.task-line.objective { color: var(--accent); }
.task-line.output { color: var(--green); }
.task-line.error { color: var(--red); }
.task-status { padding: 2px 7px; border-radius: 5px; font-size: 9.5px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; flex-shrink: 0; }
.task-status.completed { background: rgba(22,163,74,.12); color: var(--green); }
.task-status.running { background: var(--accent-soft-2); color: var(--accent); }
.task-status.pending { background: var(--bg3); color: var(--text2); }
.task-status.failed { background: rgba(220,38,38,.12); color: var(--red); }
.task-status.cancelled { background: var(--bg3); color: var(--text2); }
.plan-active-badge { padding: 2px 9px; border-radius: 999px; font-size: 10px; font-weight: 600; background: var(--accent-soft-2); color: var(--accent); }

</style>