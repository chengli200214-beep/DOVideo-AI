import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick, ref } from 'vue'
import { completeFilmSelection, useFilmWorkspace } from './filmWorkspace.js'
const flush = async () => { await nextTick(); await Promise.resolve(); await Promise.resolve(); await Promise.resolve() }
const pending = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
const projectView = id => ({ id, status: 'CONFIRMED', confirmedRevision: 1, revision: { number: 1, draft: { shots: [{ id: 'a' }, { id: 'b' }] } } })
const clipVersions = () => [{ id: 'a1', shotId: 'a', revision: 1, version: 1, task: { state: 'SUCCEEDED' } }, { id: 'b1', shotId: 'b', revision: 1, version: 1, task: { state: 'SUCCEEDED' } }]
const selection = { number: 1, snapshot: { revision: 1, clips: [{ shotId: 'a', versionId: 'a1' }, { shotId: 'b', versionId: 'b1' }] } }
function setup(reply) {
  const scope = effectScope(), user = ref({ id: 1 }), project = ref(projectView('one')), dirty = ref(false), versions = ref(clipVersions()), stored = new Map(), calls = [], timers = new Map()
  let count = 0, timer = 0
  const workspace = scope.run(() => useFilmWorkspace({ user, project, dirty, versions, request: async (path, options) => { calls.push({ path, options }); return reply(path, options) }, captureSession: () => () => true,
    storage: { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key) }, digest: async v => v, newKey: () => `key-${++count}`,
    schedule: fn => { const id = ++timer; timers.set(id, fn); return id }, cancel: id => timers.delete(id) }))
  return { ...workspace, user, project, dirty, versions, timers, calls, close: () => scope.stop() }
}
function read(path) { if (path.endsWith('/evaluation-report')) return { rows: [], summary: {} }; if (path.endsWith('/evaluation-cases')) return []; if (path.endsWith('/runtime')) return { available: true, captionsAvailable: true }; return { selection, tasks: [] } }
test('film selection must cover current completed shots and keeps storyboard ordering', () => {
  assert.deepEqual(completeFilmSelection(projectView('one'), clipVersions(), { b: 'b1', a: 'a1' }), ['a1', 'b1'])
  assert.throws(() => completeFilmSelection(projectView('one'), clipVersions(), { a: 'a1', b: 'a1' }), /每个镜头/)
  assert.throws(() => completeFilmSelection(projectView('one'), [{ ...clipVersions()[0], revision: 2 }, clipVersions()[1]], { a: 'a1', b: 'b1' }), /每个镜头/)
})
test('lost film response reuses the same key and never submits a video model task', async () => {
  let writes = 0
  const workspace = setup((path, options) => { if (!options) return read(path); if (++writes === 1) throw new Error('lost response'); return { reused: true } })
  try {
    await flush(); await workspace.refresh()
    await workspace.compose(); await workspace.compose()
    const posts = workspace.calls.filter(c => c.options)
    assert.equal(posts[0].options.headers['Idempotency-Key'], posts[1].options.headers['Idempotency-Key'])
    assert.deepEqual(JSON.parse(posts[0].options.body), { selectionVersion: 1, burnCaptions: true })
    assert.equal(workspace.calls.some(c => c.path === '/generation/tasks'), false)
  } finally { workspace.close() }
})
test('dirty draft and changed selections block composition until explicitly saved', async () => {
  const workspace = setup(read)
  try {
    await flush(); await workspace.refresh(); workspace.dirty.value = true
    await workspace.compose(); assert.match(workspace.error.value, /确认/)
    workspace.dirty.value = false; workspace.choices.value.a = 'a2'
    await workspace.compose(); assert.match(workspace.error.value, /镜头选择/)
    assert.equal(workspace.calls.some(c => c.options), false)
  } finally { workspace.close() }
})
test('refresh never overwrites unsaved choices or accepts previous-account artifacts', async () => {
  const media = pending()
  const workspace = setup(path => path.endsWith('/artifact') ? media.promise : path.includes('/films/job') ? { input: {} } : read(path))
  try {
    await flush(); await workspace.refresh(); workspace.choices.value.a = 'unsaved'; await workspace.refresh()
    assert.equal(workspace.choices.value.a, 'unsaved')
    const preview = workspace.preview({ id: 'job' }); workspace.user.value = { id: 2 }; await flush()
    media.resolve('old-private-url'); await preview; assert.equal(workspace.artifact.value, null)
  } finally { workspace.close() }
})
test('old project reports and tasks cannot replace a newly selected project', async () => {
  const old = pending()
  const workspace = setup(path => path.startsWith('/generation/projects/one') ? old.promise : read(path))
  try {
    await flush(); workspace.project.value = projectView('two'); await flush(); await workspace.refresh()
    old.resolve({ tasks: [{ id: 'old' }], selection: null }); await flush()
    assert.deepEqual(workspace.films.value.tasks, [])
  } finally { workspace.close() }
})
test('composition polling and task recovery are independent from generation and cancel on dispose', async () => {
  const workspace = setup((path, options) => options ? {} : path.endsWith('/films') ? { selection, tasks: [{ id: 'job', state: 'RENDERING' }] } : read(path))
  await flush(); await workspace.refresh(); assert.equal(workspace.timers.size, 1)
  await workspace.retry({ id: 'job' })
  assert.equal(workspace.calls.filter(c => c.options).length, 1)
  assert.equal(workspace.calls.find(c => c.options).path, '/generation/projects/one/films/job/retry')
  workspace.close(); assert.equal(workspace.timers.size, 0)
})
