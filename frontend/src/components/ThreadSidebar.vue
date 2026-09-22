<template>
  <div :class="['thread-sidebar', { resizing }]" :style="{ '--ts-width': width + 'px' }">
    <button class="btn-primary new-thread-btn" @click="$emit('new')">
      <Plus :size="16" /> New Chat
    </button>
    <div class="thread-list scrollbar">
      <div
        v-for="t in threadList"
        :key="t.id"
        :class="['thread-item', { active: t.id === activeThreadId }]"
        @click="$emit('select', t.id)"
      >
        <div class="thread-item-main">
          <span class="thread-title">{{ t.title || 'New conversation' }}</span>
          <span class="thread-time">{{ formatTime(t.lastActive) }}</span>
        </div>
        <button class="thread-delete" @click.stop="$emit('delete', t.id)" title="Delete">
          <Trash2 :size="13" />
        </button>
      </div>
      <div v-if="threadList.length === 0" class="no-threads">
        No conversations yet
      </div>
    </div>
    <div class="resize-handle" title="Drag to resize" @mousedown="$emit('resize-start', $event)"></div>
  </div>
</template>

<script setup>
import { Plus, Trash2 } from 'lucide-vue-next'

defineProps({
  threadList: { type: Array, required: true },
  activeThreadId: { type: String, default: null },
  width: { type: Number, required: true },
  resizing: { type: Boolean, default: false }
})
defineEmits(['new', 'select', 'delete', 'resize-start'])

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const diff = new Date() - d
  if (diff < 60000) return 'Just now'
  if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago'
  if (diff < 86400000) return Math.floor(diff / 3600000) + 'h ago'
  return d.toLocaleDateString()
}
</script>

<style scoped>
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

@media (max-width: 720px) {
  /* Narrow viewports drop the thread list entirely: the chat card needs the room. */
  .thread-sidebar { display: none; }
}
</style>
