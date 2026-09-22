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
        <!-- Workflow stage, streamed on the same connection: the user can tell
             "still searching / reading paper 3/6" from "stuck" while the model
             itself has nothing incremental to say. -->
        <div v-if="msg.streaming && progress && progress.stage" class="progress-stage">
          <span class="progress-stage-dot" :class="{ failed: progress.failed }"></span>
          <span class="progress-stage-label">{{ progress.stage }}</span>
          <span v-if="progress.round > 1" class="progress-stage-round">第 {{ progress.round }} 轮</span>
          <span v-if="progress.detail" class="progress-stage-detail">{{ progress.detail }}</span>
        </div>
      </div>
      <!-- The connection closed without the run's terminal event (no done /
           answer / interruption): the text above is only what got through,
           so say so rather than passing a truncated answer off as final. -->
      <div v-if="msg.incomplete" class="incomplete-note">
        <AlertTriangle :size="12" />
        <span>连接中断，回答可能不完整</span>
      </div>
      <ApprovalPanel
        v-if="msg.approvals"
        :approvals="msg.approvals"
        @submit="$emit('submit-approvals', msg)"
      />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { AlertTriangle, Bot, User } from 'lucide-vue-next'
import { renderMarkdown, handleCopyClick } from '../utils/markdown'
import ApprovalPanel from './ApprovalPanel.vue'

const props = defineProps({
  msg: { type: Object, required: true },
  // Workflow stage of the run currently streaming into this bubble, or null for
  // every other message — only the streaming bubble is ever given one.
  progress: { type: Object, default: null }
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

/* Workflow stage, streamed over SSE while a long request runs */
.progress-stage {
  display: flex; align-items: center; gap: 7px;
  padding: 0 14px 10px; font-size: 12.5px; color: var(--text2);
}
.progress-stage-dot {
  width: 6px; height: 6px; border-radius: 50%; background: var(--accent, #4f8cff);
  animation: stagePulse 1.6s ease-in-out infinite; flex: none;
}
.progress-stage-dot.failed { background: var(--danger, #e5484d); animation: none; }
@keyframes stagePulse { 0%, 100% { opacity: .35; } 50% { opacity: 1; } }
.progress-stage-round {
  margin-left: 2px; padding: 1px 6px; border-radius: 999px;
  background: var(--surface2, rgba(127,127,127,.14)); font-size: 11px; opacity: .85;
}
.progress-stage-detail { opacity: .75; }

@media (max-width: 720px) {
  .msg-body { max-width: 86%; }
}
</style>
