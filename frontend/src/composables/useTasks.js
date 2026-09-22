import { ref } from 'vue'
import { getTodos } from '../api'

// Panel order: what is running now, then what is queued, then what needs
// attention, then history. Unknown statuses sort last.
const STATUS_RANK = { RUNNING: 0, PENDING: 1, FAILED: 2, COMPLETED: 3, CANCELLED: 4 }

/**
 * The task panel's data. Every query is scoped to a conversation threadId, so
 * switching threads re-reads rather than reusing another thread's tasks.
 */
export function useTasks({ activeThreadId }) {
  const taskList = ref([])

  function refreshTasks() {
    if (!activeThreadId.value) { taskList.value = []; return }
    getTodos(activeThreadId.value)
      .then(r => {
        taskList.value = (r.data || []).slice()
          .sort((a, b) => (STATUS_RANK[a.status] ?? 9) - (STATUS_RANK[b.status] ?? 9))
      })
      // A failed refresh leaves the last good list on screen; the panel is
      // advisory and must never interrupt the conversation with an error.
      .catch(() => {})
  }

  return { taskList, refreshTasks }
}
