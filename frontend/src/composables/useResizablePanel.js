import { ref } from 'vue'

/**
 * Drag-to-resize for a side panel, with width + collapsed state persisted per
 * panel under its own localStorage key.
 *
 * App.vue (nav sidebar) and ChatView.vue (thread sidebar) want exactly this
 * behaviour, so it lives here once instead of being copied into both.
 */
export function useResizablePanel({ storageKey, defaultWidth, min, max }) {
  const width = ref(defaultWidth)
  const collapsed = ref(false)
  const resizing = ref(false)

  const clamp = (w) => Math.min(max, Math.max(min, Math.round(w)))

  function load() {
    try {
      const p = JSON.parse(localStorage.getItem(storageKey) || '{}')
      if (typeof p.width === 'number') width.value = clamp(p.width)
      collapsed.value = !!p.collapsed
    } catch { /* unreadable prefs: keep the defaults */ }
  }

  function save() {
    localStorage.setItem(storageKey, JSON.stringify({
      width: width.value, collapsed: collapsed.value
    }))
  }

  function toggle() {
    collapsed.value = !collapsed.value
    save()
  }

  function startResize(e) {
    e.preventDefault()
    const startX = e.clientX
    const startW = width.value
    resizing.value = true
    document.body.style.userSelect = 'none'
    document.body.style.cursor = 'col-resize'

    const onMove = (ev) => { width.value = clamp(startW + ev.clientX - startX) }
    const onUp = () => {
      resizing.value = false
      document.body.style.userSelect = ''
      document.body.style.cursor = ''
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      save()
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  return { width, collapsed, resizing, load, save, toggle, startResize }
}
