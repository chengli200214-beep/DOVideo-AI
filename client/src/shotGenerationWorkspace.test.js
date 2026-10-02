import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick, ref } from 'vue'
import { initialShotIds, useShotGenerationWorkspace } from './shotGenerationWorkspace.js'

const flush = async () => { await nextTick(); await Promise.resolve(); await Promise.resolve(); await Promise.resolve() }
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
const view = id => ({ id, status: 'CONFIRMED', confirmedRevision: 1, revision: { number: 1, draft: { shots: [{ id: 'a' }, { id: 'b' }] } } })
const overview = (versions = []) => ({ budget: { usedVersions: versions.length, maxVersions: 6, reservedCost: 0, costLimit: 0 }, versions })
const version = (state = 'SUCCEEDED') => ({ id: 'v1', revision: 1, shotId: 'a', version: 1, task: { id: 'task', state, recoverable: true } })
function setup(respond) {
  const calls = [], timers = new Map(), stored = new Map(), user = ref({ id: 1 }), project = ref(view('one')), dirty = ref(false), scope = effectScope()
  let sequence = 0, timerKey = 0
  const workspace = scope.run(() => useShotGenerationWorkspace({ user, project, dirty,
    request: async (path, options) => { calls.push({ path, options }); return respond(path, options) }, captureSession: () => () => true,
    storage: { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key) },
    newKey: () => `key-${++sequence}`, digest: async value => value,
    schedule: callback => { const key = ++timerKey; timers.set(key, callback); return key }, cancel: key => timers.delete(key) }))
  return { ...workspace, user, project, dirty, calls, timers, close: () => scope.stop() }
}
function read(path) { return path.includes('generation-quote') ? { revision: 1, shots: [] } : overview() }

test('initial generation excludes existing shots only within the current storyboard revision', () => {
  assert.deepEqual(initialShotIds(view('one'), overview([version()])), ['b'])
  assert.deepEqual(initialShotIds({ ...view('one'), revision: { ...view('one').revision, number: 2 } }, overview([version()])), ['a', 'b'])
})
test('a lost submit response reuses its key; a later deliberate regeneration gets a new key', async () => {
  let posts = 0
  const workspace = setup((path, options) => {
    if (!options) return read(path)
    if (++posts === 1) throw new Error('response lost')
    return { reused: posts === 2 }
  })
  try {
    await flush(); await workspace.refresh()
    const input = { mode: 'REGENERATE', shotIds: ['a'] }
    await workspace.submit(input); assert.ok(workspace.pendingInput.value)
    await workspace.retryPending(); assert.equal(workspace.pendingInput.value, null)
    await workspace.submit(input)
    const posted = workspace.calls.filter(item => item.options)
    assert.equal(posted[0].options.headers['Idempotency-Key'], posted[1].options.headers['Idempotency-Key'])
    assert.notEqual(posted[1].options.headers['Idempotency-Key'], posted[2].options.headers['Idempotency-Key'])
    assert.deepEqual(JSON.parse(posted[2].options.body), { revision: 1, mode: 'REGENERATE', shotIds: ['a'] })
    assert.equal(workspace.calls.some(item => item.path === '/generation/tasks'), false)
  } finally { workspace.close() }
})
test('dirty or unconfirmed drafts cannot create a task; watchers only read', async () => {
  const workspace = setup(read)
  try {
    await flush(); await workspace.refresh()
    workspace.dirty.value = true
    await workspace.submit({ mode: 'INITIAL', shotIds: ['a', 'b'] })
    assert.match(workspace.error.value, /确认/)
    workspace.dirty.value = false; workspace.project.value = { ...view('one'), status: 'DRAFT', confirmedRevision: null }
    await flush(); await workspace.refresh(); await workspace.submit({ mode: 'INITIAL', shotIds: ['a'] })
    assert.equal(workspace.calls.some(item => item.options?.method === 'POST'), false)
  } finally { workspace.close() }
})

test('confirmation finishing after an overview read automatically loads the quote without submitting', async () => {
  const workspace = setup(read)
  try {
    await flush(); await workspace.refresh()
    workspace.dirty.value = true
    workspace.project.value = { ...view('one'), status: 'DRAFT', confirmedRevision: null }
    await flush(); await workspace.refresh()
    workspace.project.value = view('one')
    await flush(); await workspace.refresh()
    assert.equal(workspace.quote.value, null)
    const before = workspace.calls.filter(call => call.path.includes('generation-quote')).length
    workspace.dirty.value = false
    await flush(); await flush()
    assert.equal(workspace.quote.value.revision, 1)
    assert.equal(workspace.calls.filter(call => call.path.includes('generation-quote')).length, before + 1)
    assert.equal(workspace.calls.some(call => call.options?.method === 'POST'), false)
  } finally { workspace.close() }
})
test('stale project and account responses never replace the selected project ledger or media', async () => {
  const pending = deferred(), media = deferred()
  const workspace = setup(path => path.includes('/one/generations') ? pending.promise : path.endsWith('/artifact') ? media.promise : read(path))
  try {
    await flush(); workspace.project.value = view('two'); await flush(); await workspace.refresh()
    pending.resolve(overview([version()])); await flush()
    assert.equal(workspace.overview.value.versions.length, 0)
    const preview = workspace.preview(version().task)
    workspace.user.value = { id: 2 }; await flush()
    media.resolve('private-old-account-url'); await preview
    assert.equal(workspace.artifact.value, null)
  } finally { workspace.close() }
})
test('technical recovery and remote reconciliation use the original task with no generation submission', async () => {
  const workspace = setup((path, options) => options ? {} : read(path))
  try {
    await flush(); await workspace.refresh()
    await workspace.recover(version('FAILED').task)
    await workspace.reconcile(version('SUBMISSION_UNKNOWN').task, ' verified-id ')
    const posted = workspace.calls.filter(item => item.options)
    assert.deepEqual(posted.map(item => item.path), ['/generation/tasks/task/retry', '/generation/tasks/task/reconcile'])
    assert.deepEqual(JSON.parse(posted[1].options.body), { requestId: 'verified-id' })
  } finally { workspace.close() }
})
test('active states poll read-only; unknown states stop polling and dispose cancels the timer', async () => {
  let state = 'RUNNING'
  const workspace = setup(path => path.includes('generation-quote') ? { shots: [] } : overview([version(state)]))
  await flush(); await workspace.refresh()
  assert.equal(workspace.timers.size, 1)
  state = 'SUBMISSION_UNKNOWN'; await workspace.refresh(); assert.equal(workspace.timers.size, 0)
  state = 'RUNNING'; await workspace.refresh(); assert.equal(workspace.timers.size, 1)
  workspace.close(); assert.equal(workspace.timers.size, 0)
  assert.equal(workspace.calls.some(item => item.options?.method === 'POST'), false)
})
