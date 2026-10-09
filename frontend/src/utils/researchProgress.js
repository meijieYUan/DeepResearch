// Bounded, serializable progress snapshots. Never infer counts from prose or
// conflate an SSE connection ending with a workflow passing its review.
export const PROGRESS_LIMITS = Object.freeze({ runs: 3, papers: 100, activity: 40, bytes: 64 * 1024 })
const STAGES = new Set(['RESEARCHING', 'ANALYZING', 'WRITING', 'REVIEWING', 'REVISING', 'DONE', 'FAILED'])
const TRANSPORTS = new Set(['running', 'done', 'stopped', 'error', 'connection_lost', 'unknown'])
const TERMINAL = new Set(['DONE', 'FAILED', 'PASS', 'PASSED', 'SUCCEEDED', 'COMPLETED', 'LIMIT_REACHED', 'CANCELLED', 'ERROR', 'SKIPPED'])
const COUNT_KEYS = ['total', 'completed', 'succeeded', 'failed', 'skipped', 'active']
const object = value => value && typeof value === 'object' && !Array.isArray(value) ? value : {}
const text = (value, max = 512) => typeof value === 'string' ? value.slice(0, max) : ''
const code = value => text(value, 48)
const time = value => Number.isFinite(value) && value >= 0 ? Math.min(value, 8640000000000000) : 0
const count = value => Number.isSafeInteger(value) && value >= 0 ? Math.min(value, 1000000000) : null
const counts = value => {
  const clean = {}
  for (const key of COUNT_KEYS) {
    const n = count(object(value)[key])
    if (n !== null) clean[key] = n
  }
  return clean
}
const isTerminal = run => TERMINAL.has(run.status) || run.stage === 'DONE' || run.stage === 'FAILED'
export const isProgressTerminal = isTerminal

function cleanActivity(value) {
  const raw = object(value)
  return {
    timestamp: time(raw.timestamp), stage: code(raw.stage),
    status: code(raw.status), detail: text(raw.detail), label: text(raw.label, 160),
    paper: text(raw.paper, 160), operation: code(raw.operation)
  }
}
function cleanOperations(value) {
  const raw = object(value)
  const operations = {}
  for (const [key, value] of Object.entries(raw).slice(0, 8)) {
    const operation = code(key)
    if (!operation || ['__PROTO__', 'CONSTRUCTOR', 'PROTOTYPE'].includes(operation)) continue
    const op = object(value)
    operations[operation] = { status: code(op.status), detail: text(op.detail, 256), timestamp: time(op.timestamp), counts: counts(op.counts) }
  }
  return operations
}
function cleanPaper(value) {
  const raw = object(value)
  const paper = text(raw.paper, 160)
  if (!paper) return null
  return { paper, operations: cleanOperations(raw.operations) }
}
function cleanRun(value) {
  const raw = object(value)
  const runId = text(raw.runId, 160)
  if (!runId) return null
  return {
    runId, workflow: code(raw.workflow), topic: text(raw.topic, 256),
    legacy: raw.legacy === true, stage: STAGES.has(code(raw.stage)) ? code(raw.stage) : '',
    label: text(raw.label, 160), round: count(raw.round) ?? 0, status: code(raw.status),
    startedAt: time(raw.startedAt), lastActivityAt: time(raw.lastActivityAt),
    stageUpdatedAt: time(raw.stageUpdatedAt), endedAt: time(raw.endedAt),
    counts: counts(raw.counts), operations: cleanOperations(raw.operations),
    papers: (Array.isArray(raw.papers) ? raw.papers : []).slice(0, PROGRESS_LIMITS.papers).map(cleanPaper).filter(Boolean),
    activity: (Array.isArray(raw.activity) ? raw.activity : []).slice(-PROGRESS_LIMITS.activity).map(cleanActivity),
    papersTruncated: raw.papersTruncated === true || (Array.isArray(raw.papers) && raw.papers.length > PROGRESS_LIMITS.papers),
    activityTruncated: raw.activityTruncated === true || (Array.isArray(raw.activity) && raw.activity.length > PROGRESS_LIMITS.activity)
  }
}
function byteSize(value) { return new TextEncoder().encode(JSON.stringify(value)).length }
function bound(snapshot) {
  // Retain the active summary and real counts even when long text fills storage.
  while (byteSize(snapshot) > PROGRESS_LIMITS.bytes) {
    if (snapshot.runs.length > 1) { snapshot.runs.shift(); continue }
    const run = snapshot.runs[0]
    if (!run) break
    if (run.activity.length) { run.activity.shift(); run.activityTruncated = true; continue }
    if (run.papers.length) { run.papers.shift(); run.papersTruncated = true; continue }
    break
  }
  return snapshot
}

export function createProgressSnapshot(transportStatus = 'running') {
  return { version: 1, activeRunId: '', runs: [], transportStatus: TRANSPORTS.has(transportStatus) ? transportStatus : 'unknown' }
}

export function sanitizeResearchProgress(value, { restored = false } = {}) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const raw = value
  const snapshot = createProgressSnapshot(raw.transportStatus)
  const ids = new Set()
  for (const value of (Array.isArray(raw.runs) ? raw.runs : []).slice(-PROGRESS_LIMITS.runs)) {
    const run = cleanRun(value)
    if (run && !ids.has(run.runId)) { ids.add(run.runId); snapshot.runs.push(run) }
  }
  snapshot.activeRunId = ids.has(text(raw.activeRunId, 160)) ? text(raw.activeRunId, 160) : snapshot.runs.at(-1)?.runId || ''
  if (restored && snapshot.transportStatus === 'running') snapshot.transportStatus = 'unknown'
  return bound(snapshot)
}

export function setProgressTransport(value, status) {
  const snapshot = sanitizeResearchProgress(value) || createProgressSnapshot()
  snapshot.transportStatus = TRANSPORTS.has(status) ? status : 'unknown'
  return snapshot
}

export function reduceResearchProgress(value, payload, now = Date.now()) {
  const current = sanitizeResearchProgress(value) || createProgressSnapshot()
  // New servers append a nested payload; the five legacy fields stay at the
  // event root. Flatten only known semantics, never replace the root timestamp.
  const event = object(payload)
  const nested = object(event.payload)
  const p = { ...event, ...nested, timestamp: event.timestamp }
  const operation = code(p.operation)
  const runId = text(p.runId, 160)
  const status = code(p.status)
  const timestamp = time(p.timestamp) || time(now)
  let run = current.runs.find(r => r.runId === current.activeRunId)
  const modern = !!(operation || runId)
  if (modern) {
    // Only an explicit workflow start may introduce/switch run IDs. Late tools
    // and late summaries from any non-active run are discarded, not resurrected.
    if (!runId) return current
    if (runId !== current.activeRunId) {
      if (operation !== 'WORKFLOW' || status !== 'RUNNING') return current
      if (current.runs.some(r => r.runId === runId) || (run && timestamp < run.startedAt)) return current
      run = cleanRun({ runId, workflow: p.workflow, topic: p.topic, startedAt: timestamp })
      current.runs.push(run)
      current.runs = current.runs.slice(-PROGRESS_LIMITS.runs)
      current.activeRunId = runId
    }
  } else if (run && !run.legacy) {
    return current // Unscoped legacy frames cannot modify an active modern run.
  } else if (!run) {
    // Old servers only provide stage/label/round/detail/timestamp. Their prose
    // remains an activity entry, never parsed for paper IDs, counts or status.
    run = cleanRun({ runId: 'legacy', legacy: true, startedAt: timestamp })
    current.runs.push(run)
    current.activeRunId = run.runId
  }
  const stage = code(p.stage)
  const workflow = !modern || operation === 'WORKFLOW' || operation === 'STAGE'
  if (workflow && timestamp >= run.stageUpdatedAt && !isTerminal(run)) {
    if (STAGES.has(stage)) run.stage = stage
    if (p.label !== undefined) run.label = text(p.label, 160)
    if (count(p.round) !== null) run.round = count(p.round)
    if (modern && operation === 'WORKFLOW' && status) run.status = status
    run.stageUpdatedAt = timestamp
    if (isTerminal(run)) run.endedAt = timestamp
  }
  if (modern && workflow && timestamp >= run.stageUpdatedAt) run.counts = { ...run.counts, ...counts(p.counts) }
  if (modern && !workflow) {
    // Batch operation counts are scoped to the operation, never merged into
    // workflow counts (analysis total is not a download/workflow total).
    const previous = run.operations[operation]
    if (operation && Object.keys(counts(p.counts)).length && (!previous || timestamp >= previous.timestamp)) {
      if (Object.keys(run.operations).length < 8 || previous) {
        run.operations[operation] = { status, timestamp, detail: text(p.detail, 256), counts: { ...previous?.counts, ...counts(p.counts) } }
      }
    }
    const paperName = text(p.paper, 160)
    if (paperName && operation && !['__PROTO__', 'CONSTRUCTOR', 'PROTOTYPE'].includes(operation)) {
      let paper = run.papers.find(item => item.paper === paperName)
      if (!paper && run.papers.length < PROGRESS_LIMITS.papers) {
        paper = cleanPaper({ paper: paperName })
        run.papers.push(paper)
      } else if (!paper) run.papersTruncated = true
      if (paper) {
        const prev = paper.operations[operation]
        if ((!prev || timestamp >= prev.timestamp) && (!prev || !TERMINAL.has(prev.status))) {
          if (Object.keys(paper.operations).length < 8 || prev) {
            paper.operations[operation] = { status, detail: text(p.detail, 256), timestamp, counts: { ...prev?.counts, ...counts(p.counts) } }
          }
        }
      }
    }
  }
  run.lastActivityAt = Math.max(run.lastActivityAt, timestamp)
  run.activity.push(cleanActivity({ ...p, timestamp }))
  if (run.activity.length > PROGRESS_LIMITS.activity) {
    run.activity = run.activity.slice(-PROGRESS_LIMITS.activity)
    run.activityTruncated = true
  }
  return bound(current)
}
