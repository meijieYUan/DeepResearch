// Stable, collision-resistant ids for threads, messages and approvals.
//
// Time-prefixed and monotonic within a session, so two objects created in the
// same millisecond can't collide the way a bare Date.now() can. An id is
// generated once, at creation, and never changes: it is what the v-for keys and
// the memoised markdown in MessageItem are anchored to.
let seq = 0

export function newId(prefix = 'id') {
  seq += 1
  return `${prefix}-${Date.now().toString(36)}-${seq.toString(36)}`
}
