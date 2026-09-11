<template>
  <div class="toast-host">
    <TransitionGroup name="toast">
      <div v-for="t in toasts" :key="t.id" :class="['toast', 'toast-' + t.type]">
        <CheckCircle v-if="t.type === 'success'" :size="15" />
        <AlertCircle v-else-if="t.type === 'error'" :size="15" />
        <Info v-else :size="15" />
        <span>{{ t.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup>
import { CheckCircle, AlertCircle, Info } from 'lucide-vue-next'
import { toasts } from '../utils/toast'
</script>

<style scoped>
.toast-host {
  position: fixed; bottom: 24px; right: 24px; z-index: 1000;
  display: flex; flex-direction: column; gap: 8px; pointer-events: none;
}
.toast {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px; border-radius: var(--radius); font-size: 13px;
  background: var(--bg2); border: 1px solid var(--border);
  box-shadow: var(--shadow); max-width: 360px; pointer-events: auto;
}
.toast-error { border-color: rgba(220, 38, 38, .35); color: var(--red); }
.toast-success { border-color: rgba(22, 163, 74, .35); color: var(--green); }
.toast-info { border-color: rgba(79, 70, 229, .35); color: var(--accent); }
.toast-enter-active, .toast-leave-active { transition: all .25s ease; }
.toast-enter-from { opacity: 0; transform: translateX(20px); }
.toast-leave-to { opacity: 0; transform: translateY(8px); }
</style>
