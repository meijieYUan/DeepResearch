import { computed, ref } from 'vue'
import { toast } from '../utils/toast'
import { newId } from '../utils/id'

// The two localStorage keys this store owns, defined once so no component has
// to know the strings.
export const THREADS_STORAGE_KEY = 'sa_threads'
export const ACTIVE_THREAD_KEY = 'sa_active_thread'

// --- structure guards -------------------------------------------------------
// Stored data is user-editable and outlives schema changes, so every entry is
// validated on the way in: a malformed thread or message is dropped rather than
// thrown on, which would take the whole transcript (and the view) down with it.

function sanitizeApproval(raw) {
  if (!raw || typeof raw !== 'object') return null
  const approval = {
    // Approvals predating the id field get one here, so the v-for key is stable.
    id: typeof raw.id === 'string' && raw.id ? raw.id : newId('app'),
    toolId: raw.toolId,
    toolName: raw.toolName,
    arguments: raw.arguments,
    description: raw.description
  }
  // Only carried when present: a freshly loaded approval has no decision yet,
  // which is what leaves its Submit button disabled until the user acts — the
  // same state a live interruption starts in.
  if (raw.decision) approval.decision = raw.decision
  return approval
}

function sanitizeMessage(raw) {
  if (!raw || typeof raw !== 'object') return null
  const role = raw.role === 'user' || raw.role === 'assistant' ? raw.role : null
  if (!role) return null
  const msg = {
    // Messages stored before ids existed get one now: every message needs a
    // stable v-for / memoisation key from the moment it is loaded.
    id: typeof raw.id === 'string' && raw.id ? raw.id : newId('msg'),
    role,
    content: typeof raw.content === 'string' ? raw.content : ''
  }
  // Keep the "may be incomplete" flag across reloads: the truncation is a
  // property of the stored answer, not of this session.
  if (raw.incomplete) msg.incomplete = true
  if (Array.isArray(raw.approvals)) {
    msg.approvals = raw.approvals.map(sanitizeApproval).filter(Boolean)
  }
  return msg
}

function sanitizeThread(raw, key) {
  if (!raw || typeof raw !== 'object') return null
  const id = (typeof raw.id === 'string' && raw.id)
    ? raw.id
    : (typeof key === 'string' && key ? key : null)
  if (!id) return null
  return {
    id,
    title: typeof raw.title === 'string' ? raw.title : '',
    lastActive: typeof raw.lastActive === 'number' ? raw.lastActive : Date.now(),
    messages: Array.isArray(raw.messages)
      ? raw.messages.map(sanitizeMessage).filter(Boolean)
      : []
  }
}

/**
 * Thread store: the conversation map, the active thread, localStorage
 * persistence and CRUD.
 *
 * Leaving or deleting a thread must also cancel its in-flight stream and
 * refresh the task panel. Those live in sibling stores, so they are injected as
 * hooks (see setHooks) rather than imported — importing them would make the
 * three composables mutually recursive.
 */
export function useThreads() {
  const h = {
    stopStream: () => {},
    resetProgress: () => {},
    clearInput: () => {},
    refreshTasks: () => {},
    scrollDown: () => {}
  }

  /** Called once by the view, after the sibling stores exist. */
  function setHooks(next) { Object.assign(h, next) }

  const threads = ref({})
  const activeThreadId = ref(null)

  const activeThread = computed(() => threads.value[activeThreadId.value] || null)
  const messages = computed(() => activeThread.value?.messages || [])
  const threadList = computed(() =>
    Object.values(threads.value).sort((a, b) => b.lastActive - a.lastActive))

  function loadThreads() {
    let parsed = null
    try {
      const raw = localStorage.getItem(THREADS_STORAGE_KEY)
      parsed = raw ? JSON.parse(raw) : null
    } catch {
      parsed = null
    }
    const clean = {}
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      for (const [key, value] of Object.entries(parsed)) {
        const thread = sanitizeThread(value, key)
        if (thread) clean[thread.id] = thread
      }
    }
    threads.value = clean
  }

  function saveThreads() {
    // Strip non-serializable reactive flags (streaming, editing, …) before saving
    const clean = {}
    for (const [id, t] of Object.entries(threads.value)) {
      clean[id] = {
        id: t.id,
        title: t.title,
        lastActive: t.lastActive,
        messages: (t.messages || []).map(m => {
          const c = { id: m.id, role: m.role, content: m.content }
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
      localStorage.setItem(THREADS_STORAGE_KEY, JSON.stringify(clean))
    } catch {
      // A full quota (or locked-down storage) must never bubble out: the stream
      // calls this mid-flight, and a throw there would skip the rest of the run
      // and leave the composer disabled for good. Warn and carry on with the
      // in-memory threads. The toast is deduped, so repeated failures don't stack.
      toast('无法保存对话到本地存储（空间已满）', 'error')
    }
  }

  // The active thread is persisted so views can follow the current chat.
  function persistActive(id) {
    if (id) localStorage.setItem(ACTIVE_THREAD_KEY, id)
    else localStorage.removeItem(ACTIVE_THREAD_KEY)
  }

  function newThread() {
    const id = newId('thr')
    threads.value[id] = { id, title: '', lastActive: Date.now(), messages: [] }
    activeThreadId.value = id
    persistActive(id)
    saveThreads()
    h.clearInput()
    h.refreshTasks()
    h.scrollDown(true)
  }

  function switchThread(id) {
    if (id !== activeThreadId.value) {
      // Leaving a conversation mid-answer cancels its stream: the reader would
      // keep running invisibly otherwise, and its bubble must not be left
      // streaming. Then drop the progress state, which describes the run that
      // was just cancelled — not the conversation being opened.
      h.stopStream(activeThreadId.value)
      h.resetProgress()
    }
    activeThreadId.value = id
    persistActive(id)
    h.clearInput()
    h.refreshTasks()
    h.scrollDown(true)
  }

  function deleteThread(id) {
    h.stopStream(id)
    delete threads.value[id]
    if (activeThreadId.value === id) {
      const remaining = threadList.value
      activeThreadId.value = remaining.length > 0 ? remaining[0].id : null
      persistActive(activeThreadId.value)
      h.refreshTasks()
    }
    saveThreads()
  }

  // Restores the persisted session on boot: the saved active thread when it
  // still exists, else the most recent one.
  function init() {
    loadThreads()
    if (threadList.value.length === 0) return
    const saved = localStorage.getItem(ACTIVE_THREAD_KEY)
    const restored = saved && threads.value[saved] ? saved : threadList.value[0].id
    activeThreadId.value = restored
    persistActive(restored)
  }

  return {
    threads, activeThreadId, activeThread, messages, threadList,
    loadThreads, saveThreads, persistActive, newThread, switchThread, deleteThread,
    init, setHooks
  }
}
