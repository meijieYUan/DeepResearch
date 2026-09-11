import axios from 'axios'
import { toast } from '../utils/toast'

const api = axios.create({ baseURL: '/api', timeout: 120000 })

api.interceptors.response.use(
  response => response,
  error => {
    const msg = error.response?.data?.message || error.message || 'Request failed'
    toast(msg, 'error')
    return Promise.reject(error)
  }
)

// Health
export const getHealth = () => api.get('/health')

// Chat — mode is 'Default' or 'PlanMode'
export const sendChat = (threadId, message, mode = 'Default') =>
  api.post(`/chat/${threadId}`, { message, mode })

export const approveChat = (threadId, decisions) =>
  api.post(`/chat/${threadId}/approve`, { decisions })

// Todos — every query is scoped to a conversation threadId
export const getTodos = (threadId) => api.get('/todos', { params: { threadId } })
export const getPendingTodos = (threadId) => api.get('/todos/pending', { params: { threadId } })
export const getOverdueTodos = (threadId) => api.get('/todos/overdue', { params: { threadId } })
export const queryTodos = (threadId, status, priority, keyword) =>
  api.post('/todos/query', { threadId, status, priority, keyword })

// Knowledge
export const uploadKnowledge = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/knowledge/upload', fd)
}