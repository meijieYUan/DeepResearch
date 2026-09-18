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
            <div v-else class="msg-text" v-html="renderMarkdown(msg.content)" @click="handleCopyClick"></div>
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

        <div v-if="loading" class="message assistant">
          <div class="msg-avatar"><Bot :size="16" /></div>
          <div class="msg-body">
            <div class="typing-dots"><span></span><span></span><span></span></div>
            <!-- Long workflows (research → write → review) stream their stage here, so the
                 user can tell "still searching" from "stuck" without waiting blind. -->
            <div v-if="progressStage" class="progress-stage">
              <span class="progress-stage-dot" :class="{ failed: progressFailed }"></span>
              <span class="progress-stage-label">{{ progressStage }}</span>
              <span v-if="progressRound > 1" class="progress-stage-round">第 {{ progressRound }} 轮</span>
              <span v-if="progressDetail" class="progress-stage-detail">{{ progressDetail }}</span>
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
import { ref, computed, nextTick, watch, onMounted } from 'vue'
import { MessageSquare, Send, AlertTriangle, Plus, Trash2, User, Bot, ChevronRight, PanelLeftClose, PanelLeftOpen } from 'lucide-vue-next'
import { sendChat, approveChat, getTodos, chatStreamUrl } from '../api'
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

// Workflow progress, pushed over SSE while a long request is in flight.
const progressStage = ref('')
const progressRound = ref(1)
const progressFailed = ref(false)
const progressDetail = ref('')
let progressSource = null

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
  localStorage.setItem(STORAGE_KEY, JSON.stringify(clean))
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
  activeThreadId.value = id
  input.value = ''
  persistActive(id)
  refreshTasks()
  scrollDown()
}

function deleteThread(id) {
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
  const t = threads.value[activeThreadId.value]
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

  // Open the progress stream before the POST: a workflow can publish its first
  // stage within milliseconds of starting, and an EventSource opened afterwards
  // would miss it.
  openProgressStream(activeThreadId.value)

  sendChat(activeThreadId.value, msg, planMode.value ? 'PlanMode' : 'Default')
    .then(r => handleResponse(r.data))
    // Errors surface through the response interceptor (which toasts) — do not
    // swallow them here, or a failed run looks like a run that returned nothing.
    .catch(err => handleRequestError(err))
    .finally(() => { loading.value = false; closeProgressStream(); scrollDown() })
}

function openProgressStream(threadId) {
  closeProgressStream()
  progressStage.value = ''
  progressRound.value = 1
  progressFailed.value = false
  progressDetail.value = ''

  // EventSource reconnects automatically on a dropped connection, which for a
  // long-running workflow is what we want.
  const source = new EventSource(chatStreamUrl(threadId))
  progressSource = source

  source.addEventListener('progress', (e) => {
    try {
      const data = JSON.parse(e.data)
      progressStage.value = data.label || ''
      progressRound.value = data.round || 1
      progressFailed.value = data.stage === 'FAILED'
      progressDetail.value = data.detail || ''
      scrollDown()
    } catch { /* a malformed frame is not worth breaking the run over */ }
  })

  source.addEventListener('done', () => closeProgressStream())

  // The stream closing is normal (the server closes it when the run settles) and
  // so is a reconnect attempt. Only surface a terminal failure if we are still
  // waiting — otherwise a closed stream would report an error after every answer.
  source.onerror = () => {
    if (source.readyState === EventSource.CLOSED) closeProgressStream()
  }
}

function closeProgressStream() {
  if (progressSource) {
    progressSource.close()
    progressSource = null
  }
}

function handleRequestError(err) {
  const t = threads.value[activeThreadId.value]
  if (!t) return
  // The interceptor already toasted the message; record it in the transcript too,
  // so a failure is still visible after a refresh.
  const msg = err?.response?.data?.message || err?.message || 'Request failed'
  t.messages.push({ role: 'assistant', content: '⚠️ Error: ' + msg })
  t.lastActive = Date.now()
  saveThreads()
}

function handleResponse(data) {
  const t = threads.value[activeThreadId.value]
  if (!t) return
  if (data.planEnabled !== undefined) { planMode.value = data.planEnabled }
  if (data.planActive !== undefined) { planActive.value = data.planActive }
  refreshTasks()
  if (data.type === 'ANSWER') {
    const text = extractText(data.response || data)
    t.messages.push({ role: 'assistant', content: text })
  } else if (data.type === 'INTERRUPTED') {
    const approvals = (data.pendingApprovals || []).map(a => ({
      ...a, decision: null, editing: false, editedArgs: a.arguments
    }))
    t.messages.push({ role: 'assistant', content: data.message || '', approvals, threadId: data.threadId })
  } else if (data.type === 'ERROR') {
    t.messages.push({ role: 'assistant', content: '\u26a0\ufe0f Error: ' + (data.message || 'Unknown error') })
  }
  t.lastActive = Date.now()
  saveThreads()
}

// ANSWER type always contains response as either plain text or NodeOutput{state={...}} string
// Extract just the last assistant message text for display
function extractText(res) {
  if (!res) return ''
  if (typeof res === 'string') {
    const m = res.match(/state=(\{.*\}),?\s*subGraph/)
    if (m) {
      try {
        const s = JSON.parse(m[1])
        const msgs = (s.OverAllState || s).data?.messages || []
        for (let i = msgs.length - 1; i >= 0; i--) if (msgs[i].text) return msgs[i].text
      } catch {}
    }
    return res
  }
  return res.text || JSON.stringify(res)
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
  loading.value = true
  // The run resumes on the same thread, so the progress stream reopens with it.
  openProgressStream(activeThreadId.value)
  approveChat(activeThreadId.value, decisions)
    .then(r => handleResponse(r.data))
    .catch(err => handleRequestError(err))
    .finally(() => { loading.value = false; closeProgressStream(); scrollDown() })
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
</script>

<style scoped>
/* Reading column: fluid up to --chat-max, then centered. Keeps ultrawide
   displays readable while still filling the space freed by collapsing a sidebar. */
.chat-layout { display: flex; height: 100%; --chat-max: 1400px; }

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

.chat-main { flex: 1; display: flex; flex-direction: column; min-width: 0; background: transparent; }
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

.message { display: flex; gap: 12px; max-width: var(--chat-max); margin: 0 auto 22px; animation: msgIn .28s ease; }
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
  display: flex; gap: 8px; align-items: flex-end; max-width: var(--chat-max); margin: 0 auto;
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
  .chat-layout { position: relative; }
  .thread-sidebar { display: none; }
  .header-left .icon-btn { display: none; }
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
  width: calc(100% - 48px); max-width: var(--chat-max); margin: 0 auto 10px;
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