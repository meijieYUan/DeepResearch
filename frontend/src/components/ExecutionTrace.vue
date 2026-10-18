<template>
  <details class="exec-trace" :open="expanded" @toggle="onToggle">
    <summary>
      <span>执行轨迹</span>
      <span v-if="latest" class="summary-active">
        {{ traceCategoryLabel(latest.category) }} · {{ traceNameLabel(latest.name) }}
      </span>
      <span v-if="latest" class="summary-status">{{ traceStatusLabel(latest.status) }}</span>
      <span v-if="traceId" class="trace-id" :title="traceId">{{ shortTraceId }}</span>
    </summary>
    <div class="trace-body">
      <p class="trace-note">
        时间倒序。trace id 可用来在 <code>logs/app.log</code> 中定位同一次运行。
      </p>
      <ol class="trace-list">
        <li v-for="event in visible" :key="`${event.seq}-${event.timestamp}`"
            :class="['trace-row', event.status.toLowerCase()]">
          <span class="clock">{{ traceClock(event.timestamp) }}</span>
          <span class="category">{{ traceCategoryLabel(event.category) }}</span>
          <span class="name">{{ traceNameLabel(event.name) }}</span>
          <span class="status">{{ traceStatusLabel(event.status) }}</span>
          <span v-if="event.durationMs" class="duration">{{ traceDuration(event.durationMs) }}</span>
          <span v-if="event.detail" class="detail" :title="event.detail">{{ event.detail }}</span>
        </li>
      </ol>
    </div>
  </details>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import {
  traceCategoryLabel, traceClock, traceDuration, traceNameLabel, traceStatusLabel
} from '../utils/trace'

const props = defineProps({
  trace: { type: Array, required: true },
  streaming: { type: Boolean, default: false },
  traceId: { type: String, default: '' }
})

const latest = computed(() => props.trace.at(-1) || null)
// Newest first: the thing running right now is what the user is looking for.
const visible = computed(() => [...props.trace].reverse().slice(0, 100))
const shortTraceId = computed(() =>
  props.traceId ? props.traceId.replace(/^tr-/, '').slice(0, 8) : '')
const expanded = ref(props.streaming || (latest.value && latest.value.status === 'STARTED'))

watch(() => props.streaming, running => {
  if (running) expanded.value = true
})
watch(() => props.trace.length, () => {
  if (props.streaming) expanded.value = true
})

function onToggle(event) { expanded.value = event.target.open }
</script>

<style scoped>
.exec-trace {
  margin-top: 10px; padding-top: 8px; border-top: 1px solid var(--border);
  font-size: 12px; overflow-wrap: anywhere;
}
summary { cursor: pointer; color: var(--text2); }
summary span + span { margin-left: 8px; }
.summary-active { color: var(--text); }
.summary-status { color: var(--text2); }
.trace-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px; color: var(--text2); opacity: .8;
}
.trace-body { padding: 8px 0 0; }
.trace-note { margin: 0 0 6px; color: var(--text2); }
.trace-note code { font-size: 11px; }
.trace-list { list-style: none; margin: 0; padding: 0; }
.trace-row {
  display: flex; flex-wrap: wrap; gap: 4px 10px; padding: 4px 0;
  border-bottom: 1px dashed var(--border);
}
.trace-row:last-child { border-bottom: 0; }
.clock { color: var(--text2); font-variant-numeric: tabular-nums; }
.category { color: var(--accent); font-weight: 600; }
.name { font-weight: 600; }
.status { color: var(--text2); }
.trace-row.failed .status, .trace-row.failed .name { color: var(--red, #dc2626); }
.trace-row.started .status { color: var(--yellow, #b45309); }
.duration { color: var(--text2); font-variant-numeric: tabular-nums; }
.detail {
  flex-basis: 100%; color: var(--text2); white-space: nowrap; overflow: hidden;
  text-overflow: ellipsis;
}
@media (prefers-reduced-motion: reduce) { * { animation: none; transition: none; } }
</style>
