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
//
// timeout: 0 disables axios's own deadline. The research workflow legitimately runs
// for many minutes (search → download → read → write → review → revise), so the
// global 2-minute default would abort every such request while the server keeps
// working. The request is not abandoned: progress arrives over the SSE stream
// (chatStreamUrl below), and the browser's own connection is what bounds it.
export const sendChat = (threadId, message, mode = 'Default') =>
  api.post(`/chat/${threadId}`, { message, mode }, { timeout: 0 })

export const approveChat = (threadId, decisions) =>
  api.post(`/chat/${threadId}/approve`, { decisions }, { timeout: 0 })

// SSE endpoint for workflow progress. Opened alongside the POST, not instead of it:
// the POST's response is still the authoritative answer.
export const chatStreamUrl = (threadId) => `/api/chat/${threadId}/stream`

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