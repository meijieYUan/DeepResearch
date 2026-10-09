import { ref, watch } from 'vue'
import { getTodos } from '../api'

// Panel order: what is running now, then what is queued, then what needs
// attention, then history. Unknown statuses sort last.
const STATUS_RANK = { RUNNING: 0, PENDING: 1, FAILED: 2, COMPLETED: 3, CANCELLED: 4 }

/**
 * The task panel's data. Every query is scoped to a conversation threadId, so
 * switching threads re-reads rather than reusing another thread's tasks.
 */
export function useTasks({ activeThreadId, request = getTodos }) {
  const taskList = ref([])
  let generation = 0

  // Invalidate even an A -> B -> A switch before the caller starts a refresh.
  // The thread store already requests fresh tasks, so do not request twice here.
  watch(activeThreadId, () => {
    generation++
    taskList.value = []
  }, { flush: 'sync' })

  async function refreshTasks() {
    const currentGeneration = ++generation
    const threadId = activeThreadId.value
    if (!threadId) { taskList.value = []; return }
    try {
      const r = await request(threadId)
      if (currentGeneration !== generation || threadId !== activeThreadId.value) return
      taskList.value = (r.data || []).slice()
        .sort((a, b) => (STATUS_RANK[a.status] ?? 9) - (STATUS_RANK[b.status] ?? 9))
    } catch {
      // Same-thread failures preserve the last good list. A thread switch has
      // already cleared it; stale failures have no effect and must not toast.
    }
  }

  return { taskList, refreshTasks }
}
