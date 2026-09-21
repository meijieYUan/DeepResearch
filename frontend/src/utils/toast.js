import { reactive } from 'vue'

let seq = 0
export const toasts = reactive([])

function removeToast(id) {
  const i = toasts.findIndex(t => t.id === id)
  if (i >= 0) toasts.splice(i, 1)
}

export function toast(message, type = 'info', duration = 3500) {
  const text = String(message)
  // Dedupe on message+type: a failure that repeats on a timer (a dead backend
  // polled every few seconds) or on every keystroke should read as one toast
  // that keeps its lifetime ticking, not a stack that floods the screen.
  const existing = toasts.find(t => t.message === text && t.type === type)
  if (existing) {
    clearTimeout(existing.timeout)
    existing.timeout = setTimeout(() => removeToast(existing.id), duration)
    return existing.id
  }
  const id = ++seq
  const item = { id, message: text, type }
  item.timeout = setTimeout(() => removeToast(id), duration)
  toasts.push(item)
  return id
}
