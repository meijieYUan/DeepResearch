<template>
  <div class="app-shell">
    <aside
      :class="['sidebar', { collapsed, resizing }]"
      :style="{ '--sb-width': sidebarWidth + 'px' }"
    >
      <div class="sidebar-header">
        <span class="logo">S</span>
        <span class="brand">DeepResearchAgent</span>
        <button
          class="collapse-btn"
          :title="collapsed ? 'Expand sidebar' : 'Collapse sidebar'"
          @click="toggleCollapse"
        >
          <PanelLeftOpen v-if="collapsed" :size="15" />
          <PanelLeftClose v-else :size="15" />
        </button>
      </div>
      <nav>
        <router-link to="/" class="nav-item" title="Chat">
          <MessageSquare :size="18" /><span class="nav-label">Chat</span>
        </router-link>
        <router-link to="/knowledge" class="nav-item" title="Knowledge">
          <Upload :size="18" /><span class="nav-label">Knowledge</span>
        </router-link>
      </nav>
      <div class="sidebar-footer">
        <span class="status-chip">
          <span :class="['status-dot', backendUp ? 'up' : 'down']"></span>
          <span class="status-text">{{ backendUp ? 'Backend online' : 'Backend offline' }}</span>
        </span>
      </div>
      <div v-if="!collapsed" class="resize-handle" title="Drag to resize" @mousedown="startResize"></div>
    </aside>
    <main :class="['main-content', 'scrollbar', { 'is-chat': isChat }]">
      <router-view />
    </main>
    <ToastHost />
  </div>
</template>

<script setup>
import { MessageSquare, Upload, PanelLeftClose, PanelLeftOpen } from 'lucide-vue-next'
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import ToastHost from './components/ToastHost.vue'
import { getHealth } from './api'
import { useResizablePanel } from './composables/useResizablePanel'

const route = useRoute()
const isChat = computed(() => route.path === '/')

const backendUp = ref(true)
let timer = null

// Drag-to-resize + collapse, persisted under 'sa_sidebar'. ChatView's thread
// sidebar uses the same composable under its own key.
const {
  width: sidebarWidth, collapsed, resizing,
  load: loadSidebarPrefs, toggle: toggleCollapse, startResize
} = useResizablePanel({ storageKey: 'sa_sidebar', defaultWidth: 232, min: 180, max: 420 })

// Recursive setTimeout rather than setInterval: the next check is armed only once
// this one settles, so a backend that hangs can't stack overlapping requests and
// the poll interval stays honest. The request itself is silent (see api/index.js).
async function checkHealth() {
  try {
    await getHealth()
    backendUp.value = true
  } catch {
    backendUp.value = false
  } finally {
    timer = setTimeout(checkHealth, 10000)
  }
}

onMounted(() => {
  loadSidebarPrefs()
  checkHealth()
})
onUnmounted(() => clearTimeout(timer))
</script>

<style scoped>
/* `flex: 1` is load-bearing: #app is a flex container, so without it the shell
   shrink-wraps to its content and the chat card's fixed width has nothing to
   centre within. */
.app-shell { flex: 1; display: flex; height: 100%; min-width: 0; }
.sidebar {
  position: relative;
  width: var(--sb-width, 232px);
  background: var(--bg2); border-right: 1px solid var(--border);
  display: flex; flex-direction: column; flex-shrink: 0;
}
/* Animate width only for the collapse toggle — a transition here would also
   fight the resize drag and make the sidebar lag behind the cursor. */
.sidebar:not(.resizing) { transition: width .2s ease; }
.sidebar.collapsed { width: 60px; }
.sidebar-header {
  display: flex; align-items: center; gap: 10px; padding: 18px 16px;
  border-bottom: 1px solid var(--border);
  white-space: nowrap; overflow: hidden;
}
.brand { font-size: 14.5px; font-weight: 650; letter-spacing: -.02em; }
.collapse-btn {
  margin-left: auto; padding: 5px; display: flex; align-items: center;
  background: transparent; color: var(--text2); border-radius: 6px;
  opacity: 0; transition: all .15s;
}
.sidebar:hover .collapse-btn { opacity: 1; }
.collapse-btn:hover { background: var(--bg3); color: var(--text); }
.resize-handle {
  position: absolute; top: 0; right: -3px; width: 7px; height: 100%;
  cursor: col-resize; z-index: 20;
}
.resize-handle::after {
  content: ''; position: absolute; top: 0; left: 2px; width: 2px; height: 100%;
  background: transparent; transition: background .15s;
}
.resize-handle:hover::after, .resize-handle:active::after { background: var(--accent); }
.logo {
  width: 30px; height: 30px; flex-shrink: 0;
  background: linear-gradient(135deg, var(--accent), var(--accent2)); color: #fff;
  border-radius: 9px; display: flex; align-items: center; justify-content: center;
  font-weight: 700; font-size: 15px;
  box-shadow: 0 2px 10px rgba(79, 70, 229, .28);
}
nav { flex: 1; padding: 10px; display: flex; flex-direction: column; gap: 2px; }
.nav-item {
  display: flex; align-items: center; gap: 11px; padding: 9px 11px;
  border-radius: var(--radius-sm); color: var(--text2); font-size: 13px; font-weight: 500;
  transition: all .15s;
  white-space: nowrap; overflow: hidden;
}
.nav-item svg { flex-shrink: 0; }
.nav-item:hover { background: var(--bg3); color: var(--text); }
.nav-item.router-link-active {
  background: var(--accent-soft); color: var(--accent); font-weight: 600;
  box-shadow: inset 2px 0 0 var(--accent);
}
.sidebar-footer { padding: 12px; border-top: 1px solid var(--border); }
.status-chip {
  display: flex; align-items: center; gap: 8px; padding: 7px 11px;
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  background: var(--bg); white-space: nowrap; overflow: hidden;
}
.status-dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.status-dot.up { background: var(--green); box-shadow: 0 0 0 3px rgba(22, 163, 74, .16); }
.status-dot.down { background: var(--red); box-shadow: 0 0 0 3px rgba(220, 38, 38, .16); }
.status-text { font-size: 11.5px; color: var(--text2); }
.main-content { flex: 1; overflow-y: auto; padding: 28px 32px; }
.main-content.is-chat { padding: 0; overflow: hidden; }

/* Collapsed rail: icon-only. Shared by the toggle and the narrow viewport. */
.sidebar.collapsed .brand,
.sidebar.collapsed .nav-label,
.sidebar.collapsed .status-text { display: none; }
.sidebar.collapsed .sidebar-header {
  flex-direction: column; gap: 10px; justify-content: center; padding: 14px 0;
}
.sidebar.collapsed .collapse-btn { margin-left: 0; opacity: 1; }
.sidebar.collapsed nav { padding: 8px; }
.sidebar.collapsed .nav-item { justify-content: center; padding: 10px 0; }
.sidebar.collapsed .sidebar-footer { padding: 12px 8px; }
.sidebar.collapsed .status-chip { justify-content: center; padding: 7px 0; }

@media (max-width: 900px) {
  .sidebar { width: 60px; }
  .brand, .nav-label, .status-text { display: none; }
  .sidebar-header { flex-direction: column; gap: 10px; justify-content: center; padding: 14px 0; }
  .nav-item { justify-content: center; padding: 10px 0; }
  .sidebar-footer { padding: 12px 8px; }
  .status-chip { justify-content: center; padding: 7px 0; }
  .main-content { padding: 16px; }
}
</style>
