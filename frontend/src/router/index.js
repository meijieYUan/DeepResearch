import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/ChatView.vue'
import KnowledgeView from '../views/KnowledgeView.vue'

const routes = [
  { path: '/', name: 'chat', component: ChatView },
  { path: '/knowledge', name: 'knowledge', component: KnowledgeView }
]

export default createRouter({ history: createWebHistory(), routes })