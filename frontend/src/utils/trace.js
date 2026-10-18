/** Bounded in-memory trace kept on the streaming bubble and in localStorage. */
export const TRACE_LIMIT = 200
export const TRACE_PERSIST_LIMIT = 100

const CATEGORY_LABELS = {
  RUN: '运行',
  MODEL: '模型',
  TOOL: '工具',
  WORKFLOW: '工作流',
  ERROR: '错误'
}

const STATUS_LABELS = {
  STARTED: '进行中',
  SUCCEEDED: '完成',
  FAILED: '失败',
  INFO: '信息'
}

// Tool names are terse and English; the timeline is for humans. Unknown names
// fall through unchanged so custom tools still show up.
const NAME_LABELS = {
  'model-call': '模型生成',
  run: '运行',
  researchWriteReview: '文献调研工作流',
  deepReadPaper: '单篇精读工作流',
  downloadPaper: '下载论文',
  listDownloadedPapers: '列出已下载论文',
  extractPaperText: '读取论文正文',
  extractPaperFigures: '截取论文图片',
  listPaperFigures: '列出论文图片',
  analyzePapers: '批量精读',
  reanalyzePaper: '重做单篇精读',
  readPaperAnalysis: '读取精读结果',
  writePaperAnalysis: '保存精读结果',
  writeResearchDocument: '保存调研文档',
  readSkillResource: '读取技能规程',
  webSearch: '网页搜索',
  webCrawl: '抓取网页',
  readFile: '读取文件',
  writeFile: '写入文件',
  createFile: '创建文件',
  deleteFile: '删除文件',
  listFiles: '列出文件',
  executeCommand: '执行命令',
  sendEmail: '发送邮件',
  sendEmailBatch: '批量发送邮件',
  remember: '写入记忆',
  recall: '检索记忆',
  ragAgent: '知识库检索',
  rag: '知识库检索'
}

export function sanitizeTraceEvent(raw) {
  if (!raw || typeof raw !== 'object') return null
  const timestamp = Number(raw.timestamp)
  const seq = Number(raw.seq)
  return {
    traceId: typeof raw.traceId === 'string' ? raw.traceId.slice(0, 64) : undefined,
    seq: Number.isFinite(seq) ? seq : 0,
    timestamp: Number.isFinite(timestamp) ? timestamp : Date.now(),
    category: typeof raw.category === 'string' ? raw.category.slice(0, 32) : 'RUN',
    name: typeof raw.name === 'string' ? raw.name.slice(0, 120) : 'event',
    status: typeof raw.status === 'string' ? raw.status.slice(0, 24) : 'INFO',
    durationMs: Number.isFinite(Number(raw.durationMs)) ? Math.max(0, Number(raw.durationMs)) : 0,
    detail: typeof raw.detail === 'string' ? raw.detail.slice(0, 500) : ''
  }
}

export function traceCategoryLabel(value) {
  return CATEGORY_LABELS[value] || value || '事件'
}

export function traceStatusLabel(value) {
  return STATUS_LABELS[value] || value || ''
}

export function traceNameLabel(value) {
  if (!value) return '事件'
  return NAME_LABELS[value] || value
}

export function traceDuration(ms) {
  const value = Number(ms) || 0
  if (value < 1000) return `${value} ms`
  if (value < 60_000) return `${(value / 1000).toFixed(1)} 秒`
  return `${Math.floor(value / 60000)} 分 ${Math.round((value % 60000) / 1000)} 秒`
}

export function traceClock(timestamp) {
  const date = new Date(Number(timestamp) || Date.now())
  const pad = n => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
