<template>
  <div class="task-panel">
    <button class="task-panel-header" :aria-expanded="open" @click="$emit('update:open', !open)">
      <ChevronRight :size="14" :class="['task-chevron', { open }]" />
      <span class="task-eyebrow">Tasks</span>
      <span class="task-count">{{ completedCount }}/{{ tasks.length }}</span>
      <span class="task-bar"><span class="task-bar-fill" :style="{ width: progressPct + '%' }"></span></span>
      <span class="task-hint">{{ open ? 'Hide' : 'Show' }}</span>
    </button>

    <div v-if="open" class="task-panel-body scrollbar">
      <div v-for="t in tasks" :key="t.id" class="task-row">
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
</template>

<script setup>
import { computed } from 'vue'
import { ChevronRight } from 'lucide-vue-next'

const props = defineProps({
  tasks: { type: Array, required: true },
  open: { type: Boolean, default: false }
})
defineEmits(['update:open'])

const completedCount = computed(() => props.tasks.filter(t => t.status === 'COMPLETED').length)
const progressPct = computed(() =>
  props.tasks.length ? Math.round(completedCount.value / props.tasks.length * 100) : 0)

function prioClass(p) {
  return { URGENT: 'high', HIGH: 'high', MEDIUM: 'medium', LOW: 'low' }[p] || 'low'
}
function formatDue(d) {
  const s = String(d)
  return s.length > 10 ? s.slice(0, 10) : s
}
</script>

<style scoped>
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

@media (max-width: 720px) {
  .task-panel { width: calc(100% - 28px); }
}
</style>
