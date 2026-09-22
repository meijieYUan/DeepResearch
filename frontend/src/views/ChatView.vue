<template>
  <div class="chat-layout">
    <ThreadSidebar
      v-if="!threadCollapsed"
      :thread-list="threadList"
      :active-thread-id="activeThreadId"
      :width="threadWidth"
      :resizing="threadResizing"
      @new="newThread"
      @select="switchThread"
      @delete="deleteThread"
      @resize-start="startThreadResize"
    />

    <!-- Chat area -->
    <div class="chat-main">
      <div class="chat-header">
        <div class="header-left">
          <button
            class="icon-btn"
            :title="threadCollapsed ? 'Show conversations' : 'Hide conversations'"
            @click="toggleThreadSidebar"
          >
            <PanelLeftOpen v-if="threadCollapsed" :size="16" />
            <PanelLeftClose v-else :size="16" />
          </button>
          <span class="thread-label">{{ activeThread ? activeThread.title || 'New conversation' : 'Select a conversation' }}</span>
        </div>
        <label :class="['plan-toggle', { on: planMode }]" title="Enable Plan Mode: when ON, the assistant may enter plan mode for complex tasks">
          <input type="checkbox" v-model="planMode" />
          <span class="toggle-label">Plan Mode</span>
          <span v-if="planMode && planActive" class="plan-active-badge">Planning</span>
        </label>
      </div>

      <div class="chat-messages scrollbar" ref="msgContainer" @scroll="onScroll">
        <div v-if="messages.length === 0" class="empty-state">
          <div class="empty-icon"><MessageSquare :size="28" /></div>
          <p>Start a conversation with your AI assistant</p>
          <p class="empty-hint">Supports tasks, code, research &amp; more</p>
        </div>

        <!-- Keyed by the message's own id, not its index: an id survives edits to
             the array, so a bubble is never reused for a different message. -->
        <MessageItem
          v-for="msg in messages"
          :key="msg.id"
          :msg="msg"
          :progress="msg.streaming ? progressState : null"
          @submit-approvals="onSubmitApprovals"
        />

        <!-- Shown only once the user has scrolled away from the bottom, which is
             also when following is suspended. -->
        <button v-if="!stickToBottom" class="scroll-bottom-btn" @click="scrollToBottom">
          <ArrowDown :size="14" /> 回到底部
        </button>
      </div>

      <TaskPanel
        v-if="taskList.length > 0"
        :tasks="taskList"
        :open="tasksOpen"
        @update:open="tasksOpen = $event"
      />

      <div class="composer">
        <div class="composer-inner">
          <textarea
            ref="inputRef"
            v-model="input"
            @keydown="onKeydown"
            placeholder="Type a message... (Enter to send, Shift+Enter for new line)"
            :disabled="loading || !activeThreadId"
            rows="1"
          ></textarea>
          <button class="btn-primary send-btn" @click="onSend" :disabled="!input.trim() || loading || !activeThreadId">
            <Send :size="16" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ArrowDown, MessageSquare, PanelLeftClose, PanelLeftOpen, Send } from 'lucide-vue-next'
import MessageItem from '../components/MessageItem.vue'
import TaskPanel from '../components/TaskPanel.vue'
import ThreadSidebar from '../components/ThreadSidebar.vue'
import { useChatStream } from '../composables/useChatStream'
import { useResizablePanel } from '../composables/useResizablePanel'
import { useTasks } from '../composables/useTasks'
import { useThreads } from '../composables/useThreads'

// --- thread store: conversations, persistence, CRUD --------------------------
const threadStore = useThreads()
const {
  threads, activeThreadId, activeThread, messages, threadList,
  saveThreads, newThread, switchThread, deleteThread
} = threadStore

// --- task panel -------------------------------------------------------------
const { taskList, refreshTasks } = useTasks({ activeThreadId })
// Panel collapse state: held here rather than in TaskPanel so it survives the
// panel unmounting when a thread's task list empties and fills again.
const tasksOpen = ref(false)

// --- thread sidebar: drag + persisted prefs, shared with App.vue -------------
const {
  width: threadWidth, collapsed: threadCollapsed, resizing: threadResizing,
  load: loadThreadSidebarPrefs, toggle: toggleThreadSidebar, startResize: startThreadResize
} = useResizablePanel({ storageKey: 'sa_thread_sidebar', defaultWidth: 264, min: 200, max: 380 })

// --- plan mode --------------------------------------------------------------
// The header renders these, but the server also reconciles them from the
// answer / interruption frames, so the stream writes them too.
const planMode = ref(false)
const planActive = ref(false)

// --- stream -----------------------------------------------------------------
const stream = useChatStream({
  threads, activeThreadId, planMode, planActive,
  saveThreads, refreshTasks, scrollDown
})
const {
  loading,
  progressStage, progressRound, progressFailed, progressDetail,
  resetProgress, stopStream, send, submitApprovals
} = stream

// The streaming bubble's stage, bundled for MessageItem. Only the streaming
// message is handed one, so a settled transcript never re-renders for progress.
const progressState = computed(() => ({
  stage: progressStage.value,
  round: progressRound.value,
  failed: progressFailed.value,
  detail: progressDetail.value
}))

// --- composer ---------------------------------------------------------------
const input = ref('')
const inputRef = ref(null)

function clearInput() { input.value = '' }

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    onSend()
  }
}

function adjustTextarea() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

watch(input, adjustTextarea)

function onSend() {
  const text = input.value.trim()
  if (!text || loading.value || !activeThreadId.value) return
  // Sending is an explicit "I'm following this" signal: re-pin to the bottom
  // even if the user had scrolled up to read earlier in the conversation.
  stickToBottom.value = true
  if (send(text, planMode.value ? 'PlanMode' : 'Default')) {
    clearInput()
    nextTick(adjustTextarea)
  }
}

function onSubmitApprovals(msg) {
  stickToBottom.value = true
  submitApprovals(msg)
}

// --- scroll -----------------------------------------------------------------
const msgContainer = ref(null)
// Whether new content should drag the viewport along. False as soon as the user
// scrolls away from the bottom, so streaming cannot yank them back mid-read.
const stickToBottom = ref(true)
const NEAR_BOTTOM_PX = 80

function isNearBottom() {
  const el = msgContainer.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_PX
}

function onScroll() { stickToBottom.value = isNearBottom() }

// `force` pins the view to the bottom and resumes following — used when the
// conversation itself changes (send, thread switch, new thread), where the user
// expects to land on the newest content. Without it, following only continues
// while the user is already near the bottom.
function scrollDown(force = false) {
  if (force) stickToBottom.value = true
  else if (!stickToBottom.value) return
  nextTick(() => {
    const el = msgContainer.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function scrollToBottom() { scrollDown(true) }

// The thread store's side effects that live in this view / its siblings.
threadStore.setHooks({
  stopStream,
  resetProgress,
  clearInput,
  refreshTasks,
  scrollDown
})

onMounted(() => {
  loadThreadSidebarPrefs()
  threadStore.init()
  refreshTasks()
})

// A route change unmounts the view while a run may still be streaming: abort it,
// or the reader lives on holding a dead component and the run never settles.
onUnmounted(() => stopStream())
</script>

<style scoped>
/* The chat area is a fixed-width card centred in the space beside the thread
   sidebar. The sidebar stays flush and full-height; the card floats on the
   ambient background, so an ultrawide monitor widens the margin rather than
   stretching the reading column. `gap` guarantees the card never butts up
   against the sidebar once the auto margins have collapsed. */
.chat-layout { display: flex; height: 100%; --chat-width: 1100px; gap: 14px; }

.chat-main {
  /* Anchors the floating "back to bottom" affordance. */
  position: relative;
  flex: 0 1 var(--chat-width); width: 100%; max-width: var(--chat-width);
  /* Centred by auto margins; `flex: 0 1` keeps the card from stretching once the
     sidebar leaves more room than the card needs. */
  margin: 14px auto; min-width: 0; min-height: 0;
  display: flex; flex-direction: column;
  background: var(--bg2); border: 1px solid var(--border);
  border-radius: var(--radius); box-shadow: var(--shadow-lg);
  overflow: hidden;
}
.chat-header {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  height: 58px; padding: 0 24px; flex-shrink: 0;
  background: var(--bg2); border-bottom: 1px solid var(--border);
}
.header-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.icon-btn {
  display: flex; align-items: center; justify-content: center; padding: 6px;
  background: transparent; color: var(--text2); border-radius: 7px; flex-shrink: 0;
  transition: all .15s;
}
.icon-btn:hover { background: var(--bg3); color: var(--text); }
.thread-label {
  font-size: 14px; font-weight: 600; letter-spacing: -.015em; color: var(--text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.chat-messages { flex: 1; overflow-y: auto; padding: 30px 24px 6px; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; gap: 10px; color: var(--text2); }
.empty-icon {
  width: 64px; height: 64px; border-radius: 18px; margin-bottom: 6px;
  background: linear-gradient(160deg, var(--accent-soft-2), var(--accent-soft));
  border: 1px solid var(--border); color: var(--accent);
  display: flex; align-items: center; justify-content: center;
}
.empty-state p { font-size: 14.5px; font-weight: 500; color: var(--text); }
.empty-state .empty-hint { font-size: 12.5px; color: var(--text2); font-weight: 400; opacity: .75; }

/* Sticky rather than absolute: pinned to the bottom of the scrollport while the
   user is away from it, and it settles into the flow at the very end of the
   transcript instead of overlapping the task panel or the composer. */
.scroll-bottom-btn {
  position: sticky; bottom: 10px;
  display: flex; align-items: center; gap: 6px;
  width: fit-content; margin-left: auto; margin-right: 2px;
  padding: 7px 13px; border-radius: 999px;
  background: var(--bg2); border: 1px solid var(--border-strong);
  color: var(--text); font-size: 12px; font-weight: 600;
  box-shadow: var(--shadow-lg);
  animation: scrollBtnIn .18s ease;
}
/* Local keyframes: scoped styles rename @keyframes, so MessageItem's msgIn is
   not visible from here. */
@keyframes scrollBtnIn { from { opacity: 0; transform: translateY(6px); } }

.composer { flex-shrink: 0; padding: 14px 24px 20px; background: var(--bg2); border-top: 1px solid var(--border); }
.composer-inner {
  display: flex; gap: 8px; align-items: flex-end; max-width: 100%; margin: 0 auto;
  background: var(--bg); border: 1px solid var(--border-strong); border-radius: 16px;
  padding: 6px 6px 6px 14px;
  transition: border-color .15s, box-shadow .15s;
}
.composer-inner:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft-2); }
.composer-inner textarea {
  flex: 1; background: transparent; border: none; border-radius: 0; box-shadow: none;
  color: var(--text); padding: 9px 0; font-size: 13.5px; outline: none; resize: none;
  line-height: 1.55; font-family: inherit; min-height: 38px; max-height: 200px;
}
.send-btn { padding: 9px; border-radius: 11px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }

.plan-toggle {
  display: flex; align-items: center; gap: 7px; cursor: pointer; font-size: 12px;
  color: var(--text2); user-select: none; padding: 5px 11px; border-radius: 999px;
  border: 1px solid var(--border); background: var(--bg); transition: all .15s;
}
.plan-toggle:hover { border-color: var(--border-strong); color: var(--text); }
.plan-toggle.on {
  background: var(--accent-soft); border-color: transparent; color: var(--accent);
}
.plan-toggle.on:hover { border-color: transparent; color: var(--accent); }
.plan-toggle.on .toggle-label { font-weight: 600; }
.plan-toggle input { accent-color: var(--accent); cursor: pointer; margin: 0; }
.toggle-label { font-weight: 550; }
.plan-active-badge { padding: 2px 9px; border-radius: 999px; font-size: 10px; font-weight: 600; background: var(--accent-soft-2); color: var(--accent); }

@media (max-width: 720px) {
  .chat-layout { position: relative; gap: 0; }
  .header-left .icon-btn { display: none; }
  /* Below the card width the floating shell is all margin and no content: drop
     it so the chat uses the full viewport instead of shrinking into a strip. */
  .chat-main { margin: 0; border: none; border-radius: 0; box-shadow: none; }
  .chat-messages, .composer { padding-left: 14px; padding-right: 14px; }
}
</style>
