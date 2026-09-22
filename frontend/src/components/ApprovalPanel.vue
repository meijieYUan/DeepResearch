<template>
  <div class="approval-panel card">
    <div class="approval-header">
      <AlertTriangle :size="16" />
      <span>Approval Required — {{ approvals.length }} action(s) pending</span>
    </div>
    <div v-for="app in approvals" :key="app.id" class="approval-item">
      <div class="approval-tool">
        <span class="badge badge-high">{{ app.toolName }}</span>
        <span class="approval-desc">{{ app.description }}</span>
      </div>
      <div class="approval-args">
        <pre>{{ formatArgs(app.arguments) }}</pre>
        <textarea v-if="app.editing" v-model="app.editedArgs" rows="3" placeholder="Edit arguments (JSON)..."></textarea>
      </div>
      <div class="approval-actions">
        <template v-if="app.editing">
          <!-- While editing, Confirm emits an EDITED decision so the backend
               (HITLHelper.applyDecision) swaps in the edited arguments. -->
          <button class="btn-success btn-sm" @click="confirmEdit(app)">Confirm Edit</button>
          <button class="btn-ghost btn-sm" @click="cancelEdit(app)">Cancel</button>
        </template>
        <template v-else>
          <button class="btn-success btn-sm" @click="decide(app, 'APPROVED')">Approve</button>
          <button class="btn-danger btn-sm" @click="decide(app, 'REJECTED')">Reject</button>
          <button class="btn-ghost btn-sm" @click="startEdit(app)">Edit</button>
        </template>
      </div>
    </div>
    <button class="btn-primary" @click="$emit('submit')" :disabled="!allDecided">
      Submit Approvals
    </button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { AlertTriangle } from 'lucide-vue-next'
import { toast } from '../utils/toast'

const props = defineProps({
  approvals: { type: Array, required: true }
})
defineEmits(['submit'])

const allDecided = computed(() => props.approvals.every(a => a.decision))

function decide(app, result) { app.decision = result }

// Edit flow: entering edit mode re-seeds the draft from the original arguments
// each time, so cancel-and-reopen always starts clean. Confirm validates the
// draft as JSON (tool arguments are JSON strings) and only then records an
// EDITED decision, which is what makes the submit send editedArguments upstream.
function startEdit(app) {
  app.editedArgs = app.arguments
  app.editing = true
}
function cancelEdit(app) {
  app.editing = false
}
function confirmEdit(app) {
  try { JSON.parse(app.editedArgs) }
  catch { toast('Edited arguments must be valid JSON', 'error'); return }
  app.decision = 'EDITED'
  app.editing = false
}

function formatArgs(args) {
  try { return JSON.stringify(JSON.parse(args), null, 2) } catch { return args }
}
</script>

<style scoped>
.approval-panel { margin-top: 10px; }
.approval-header { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; color: var(--yellow); font-weight: 600; font-size: 13px; }
.approval-item { margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px solid var(--border); }
.approval-tool { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.approval-desc { font-size: 12px; color: var(--text2); }
.approval-args { margin-bottom: 8px; }
.approval-args pre { font-size: 11px; background: var(--bg); padding: 8px; border-radius: 4px; overflow-x: auto; margin-bottom: 6px; }
.approval-args textarea { width: 100%; }
.approval-actions { display: flex; gap: 6px; }
</style>
