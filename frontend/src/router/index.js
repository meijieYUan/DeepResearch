import { createRouter, createWebHistory } from 'vue-router'

// Lazily imported: each view becomes its own chunk, so the knowledge base (and
// its upload code) is only fetched when that route is actually visited.
const routes = [
  { path: '/', name: 'chat', component: () => import('../views/ChatView.vue') },
  { path: '/knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue') }
]

export default createRouter({ history: createWebHistory(), routes })
