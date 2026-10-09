<template>
  <details class="research-progress" :open="expanded" @toggle="onToggle">
    <summary>
      <span>研究进度</span>
      <span v-if="active">{{ active.label || stageName(active.stage) }}</span>
      <span v-if="active?.status">{{ statusName(active.status) }}</span>
      <span v-if="active?.round > 1">第 {{ active.round }} 轮</span>
    </summary>
    <div class="progress-body">
      <p class="transport-note">{{ transportLabel }}</p>
      <section v-for="run in snapshot.runs" :key="run.runId" class="progress-run">
        <h4>{{ workflowName(run.workflow) }}<span v-if="run.topic"> · {{ run.topic }}</span></h4>
        <p>{{ run.label || stageName(run.stage) }}<span v-if="run.status"> · {{ statusName(run.status) }}</span></p>
        <p class="time-note">
          已用时 {{ duration(elapsed(run)) }}
          <span v-if="run.lastActivityAt"> · 最后活动 {{ duration(sinceActivity(run)) }}前</span>
        </p>
        <ul v-if="Object.keys(run.counts).length" class="counts-list">
          <li v-for="(value, key) in run.counts" :key="key">{{ countName(key) }}：{{ value }}</li>
        </ul>
        <p v-else class="muted">尚未收到工作流计数</p>
        <div v-for="(op, operation) in run.operations" :key="operation">
          <p>{{ operationName(operation) }}：{{ statusName(op.status) }}</p>
          <ul class="counts-list">
            <li v-for="(value, key) in op.counts" :key="key">{{ countName(key) }}：{{ value }}</li>
          </ul>
        </div>
        <details v-if="run.papers.length" class="paper-details">
          <summary>论文操作（{{ run.papers.length }}{{ run.papersTruncated ? '，列表已截断' : '' }}）</summary>
          <ul class="paper-list">
            <li v-for="paper in run.papers" :key="paper.paper">
              <strong>{{ paper.paper }}</strong>
              <ul>
                <li v-for="(op, operation) in paper.operations" :key="operation">
                  {{ operationName(operation) }}：{{ statusName(op.status) }}
                  <span v-if="op.detail" class="muted"> · {{ op.detail }}</span>
                  <span v-for="(value, key) in op.counts" :key="key" class="muted"> · {{ countName(key) }}：{{ value }}</span>
                </li>
              </ul>
            </li>
          </ul>
        </details>
        <details v-if="run.activity.length" class="activity-details">
          <summary>最近活动{{ run.activityTruncated ? '（较早活动已省略）' : '' }}</summary>
          <ol>
            <li v-for="(entry, index) in [...run.activity].reverse()" :key="index">
              <span v-if="entry.operation">{{ operationName(entry.operation) }} · </span>
              <span v-if="entry.paper">{{ entry.paper }} · </span>
              <span v-if="entry.status">{{ statusName(entry.status) }} · </span>
              {{ entry.detail || entry.label || stageName(entry.stage) }}
            </li>
          </ol>
        </details>
      </section>
    </div>
  </details>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { isProgressTerminal, sanitizeResearchProgress } from '../utils/researchProgress'

const props = defineProps({ progress: { type: Object, required: true } })
const snapshot = computed(() => sanitizeResearchProgress(props.progress))
const active = computed(() => snapshot.value?.runs.find(run => run.runId === snapshot.value.activeRunId))
const ticking = computed(() => snapshot.value?.transportStatus === 'running' && active.value && !isProgressTerminal(active.value))
const expanded = ref(!!ticking.value)
const now = ref(0) // Deterministic SSR; no browser globals/timers until mounted.
let timer = null
let mounted = false
function syncClock() {
  if (timer !== null) { clearInterval(timer); timer = null }
  if (mounted && ticking.value) {
    now.value = Date.now()
    timer = setInterval(() => { now.value = Date.now() }, 1000)
  }
}
watch(ticking, running => { if (!running) expanded.value = false; syncClock() })
watch(() => snapshot.value?.activeRunId, () => { expanded.value = !!ticking.value })
onMounted(() => { mounted = true; now.value = Date.now(); syncClock() })
onUnmounted(() => { mounted = false; if (timer !== null) clearInterval(timer) })
function onToggle(event) { expanded.value = event.target.open }
function elapsed(run) {
  const end = run.endedAt || (ticking.value && run.runId === snapshot.value.activeRunId ? now.value : run.lastActivityAt)
  return Math.max(0, (end || run.startedAt) - run.startedAt)
}
function sinceActivity(run) { return Math.max(0, (now.value || run.lastActivityAt) - run.lastActivityAt) }
function duration(ms) {
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分 ${seconds % 60} 秒`
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
}
const transportLabel = computed(() => ({
  running: '正在接收进度；工作流结果以业务状态为准',
  done: '传输已结束，不代表工作流审核通过',
  stopped: '已停止接收；后台任务可能仍在继续',
  error: '接收出现错误；已保留最后收到的进度',
  connection_lost: '连接已断开；后台任务可能仍在继续，不会自动重连',
  unknown: '历史记录：当前任务状态未知'
}[snapshot.value?.transportStatus] || '任务状态未知'))
function workflowName(value) { return ({ RESEARCH_WRITE_REVIEW: '调研写作', PAPER_DEEP_READ: '论文精读' })[value] || '研究工作流' }
function stageName(value) { return ({ RESEARCHING: '检索论文', ANALYZING: '精读论文', WRITING: '撰写文档', REVIEWING: '质量审查', REVISING: '修订文档', DONE: '工作流结束', FAILED: '工作流失败' })[value] || value || '等待阶段信息' }
function operationName(value) { return ({ WORKFLOW: '工作流', STAGE: '阶段', SEARCH: '检索', DOWNLOAD: '下载', ANALYSIS: '分析', WRITE: '写作', REVIEW: '审查', REVISE: '修订' })[value] || value }
function statusName(value) { return ({ RUNNING: '进行中', PENDING: '待处理', SUCCEEDED: '成功', COMPLETED: '完成', DONE: '结束', PASS: '审核通过', FAILED: '失败', ERROR: '错误', SKIPPED: '跳过', CACHED: '缓存命中', LIMIT_REACHED: '达到轮次上限', CANCELLED: '已取消' })[value] || value || '状态未知' }
function countName(value) { return ({ total: '总数', completed: '已处理', succeeded: '成功', failed: '失败', skipped: '跳过', active: '处理中' })[value] || value }
</script>

<style scoped>
.research-progress { margin-top: 10px; padding-top: 8px; border-top: 1px solid var(--border); font-size: 12px; overflow-wrap: anywhere; }
summary { cursor: pointer; color: var(--text2); }
.research-progress > summary span + span { margin-left: 8px; }
.progress-body { padding: 8px 0 0; }
.progress-run + .progress-run { margin-top: 12px; border-top: 1px solid var(--border); padding-top: 8px; }
h4, p { margin: 0 0 6px; }
h4 { font-size: 12px; font-weight: 600; }
.muted, .time-note, .transport-note { color: var(--text2); }
ul, ol { margin: 6px 0; padding-left: 20px; }
.counts-list { list-style: none; padding: 0; display: flex; flex-wrap: wrap; gap: 4px 12px; }
.paper-details, .activity-details { margin-top: 8px; }
.paper-list > li + li { margin-top: 8px; }
@media (prefers-reduced-motion: reduce) { * { animation: none; transition: none; } }
</style>
