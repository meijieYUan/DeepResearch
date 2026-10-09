<template>
  <div :class="['message', msg.role]">
    <div class="msg-avatar">
      <User v-if="msg.role === 'user'" :size="16" />
      <Bot v-else :size="16" />
    </div>
    <div class="msg-body">
      <div v-if="msg.role === 'user'" class="msg-text">{{ msg.content }}</div>
      <div v-else class="msg-text" :class="{ streaming: msg.streaming }">
        <!-- A streaming bubble starts empty: typing dots until the first delta,
             then the markdown body grows in place as deltas land. -->
        <div v-if="msg.streaming && !msg.content" class="typing-dots">
          <span></span><span></span><span></span>
        </div>
        <div v-else class="stream-content" v-html="html" @click="handleCopyClick"></div>
        <ResearchProgress v-if="msg.progress?.runs?.length" :progress="msg.progress" />
      </div>
      <!-- The connection closed without the run's terminal event (no done /
           answer / interruption): the text above is only what got through,
           so say so rather than passing a truncated answer off as final. -->
      <div v-if="msg.incomplete" class="incomplete-note">
        <AlertTriangle :size="12" />
        <span>{{ incompleteLabel }}</span>
      </div>
      <div v-if="msg.error" class="incomplete-note" role="alert">{{ msg.error }}</div>
      <fieldset v-if="msg.approvals" class="approval-gate" :disabled="approvalDisabled">
        <ApprovalPanel
          :approvals="msg.approvals"
          @submit="!approvalDisabled && $emit('submit-approvals', msg)"
        />
        <p v-if="msg.approvalsSubmitted" class="approval-note">审批已提交</p>
      </fieldset>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { AlertTriangle, Bot, User } from 'lucide-vue-next'
import { renderMarkdown, handleCopyClick } from '../utils/markdown'
import ApprovalPanel from './ApprovalPanel.vue'
import ResearchProgress from './ResearchProgress.vue'

const props = defineProps({
  msg: { type: Object, required: true },
  approvalDisabled: { type: Boolean, default: false }
})
defineEmits(['submit-approvals'])

// Memoised markdown. A computed re-runs only when its reactive dependencies
// change, and a message is rendered by its own component instance, so a settled
// message — whose content never changes again — parses marked + highlight +
// DOMPurify exactly once, and only the streaming bubble (the sole message whose
// content mutates per frame) is recomputed. This replaces calling
// renderMarkdown(msg.content) inline in the v-for, which re-parsed every
// historical message on every frame of a stream.
const html = computed(() => (props.msg.role === 'assistant' ? renderMarkdown(props.msg.content) : ''))
const incompleteLabel = computed(() => {
  switch (props.msg.transportStatus) {
    case 'connection_lost': return '连接已断开，回答可能不完整；后台任务可能仍在继续'
    case 'stopped': return '已停止接收，回答可能不完整；后台任务可能仍在继续'
    case 'unknown': return '已恢复历史记录，任务状态未知；回答可能不完整'
    case 'done': return '传输已结束，但未收到完整回答'
    case 'error': return '传输出现错误，已保留收到的部分回答'
    default: return '连接中断，回答可能不完整'
  }
})
</script>

<style scoped>
.message { display: flex; gap: 12px; max-width: 100%; margin: 0 auto 22px; animation: msgIn .28s ease; }
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
/* Shown when a stream ended without its terminal event: the bubble above holds
   only what arrived, so it must not read as a finished answer. */
.incomplete-note {
  display: flex; align-items: center; gap: 6px; margin-top: 6px; padding: 0 4px;
  font-size: 11.5px; color: var(--yellow, #b45309);
}

/* Typing indicator */
.typing-dots { display: flex; gap: 4px; padding: 10px 14px; }
.typing-dots span {
  width: 7px; height: 7px; border-radius: 50%; background: var(--text2);
  animation: dotBounce 1.4s ease-in-out infinite;
}
.typing-dots span:nth-child(2) { animation-delay: .16s; }
.typing-dots span:nth-child(3) { animation-delay: .32s; }
@keyframes dotBounce { 0%, 60%, 100% { opacity: .3; transform: scale(.8); } 30% { opacity: 1; transform: scale(1); } }

.approval-gate { margin: 0; padding: 0; border: 0; min-width: 0; }
.approval-gate:disabled { opacity: .7; }
.approval-note { font-size: 12px; color: var(--text2); margin-top: 6px; }
@media (prefers-reduced-motion: reduce) {
  .message, .typing-dots span { animation: none; }
}

@media (max-width: 720px) {
  .msg-body { max-width: 86%; }
}
</style>
