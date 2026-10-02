import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick, ref } from 'vue'
import { useStoryboardModelWorkspace } from './storyboardModelWorkspace.js'

const flush = async () => { await nextTick(); for (let i = 0; i < 8; i++) await Promise.resolve() }
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
function setup(respond) {
  const user = ref({ id: 1 }), project = ref({ id: 'one', revision: { number: 1 } }), dirty = ref(false), calls = [], stored = new Map(), scheduled = []
  let key = 0
  const scope = effectScope()
  const workspace = scope.run(() => useStoryboardModelWorkspace({ user, project, dirty,
    request: async (path, options) => { calls.push({ path, options }); return respond(path, options) }, captureSession: () => () => true,
    storage: { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key) },
    newKey: () => `key-${++key}`, digest: async value => value,
    schedule: callback => { scheduled.push(callback); return scheduled.length }, cancel: () => {} }))
  return { ...workspace, user, project, dirty, calls, scheduled, close: () => scope.stop() }
}
const config = { ready: true, model: 'test-model', reservationPerCall: 1, currency: 'CNY' }

test('mount, refresh and polling only query; submitting needs ready gate, saved draft and cost acknowledgement', async () => {
  const workspace = setup((path, options) => path.endsWith('/runtime') ? config : options?.method === 'POST' ? { id: 'task', status: 'QUEUED' } : [])
  try {
    await flush(); await workspace.generate(); assert.equal(workspace.calls.filter(call => call.options).length, 0)
    workspace.agreed.value = true; workspace.dirty.value = true; await workspace.generate(); assert.equal(workspace.calls.filter(call => call.options).length, 0)
    workspace.dirty.value = false; await workspace.generate(); assert.equal(workspace.calls.filter(call => call.options).length, 1)
    assert.equal(workspace.agreed.value, false); assert.equal(workspace.hasActive.value, true)
    await workspace.scheduled[0](); await flush(); assert.equal(workspace.calls.filter(call => call.options).length, 1)
  } finally { workspace.close() }
})

test('lost POST response keeps its idempotency key and an explicit new call obtains a new key', async () => {
  let attempts = 0
  const workspace = setup((path, options) => {
    if (path.endsWith('/runtime')) return config
    if (options?.method === 'POST') { if (++attempts === 1) throw new Error('network lost'); return { id: 'done', status: 'FAILED', expectedRevision: 1 } }
    return []
  })
  try {
    await flush(); workspace.agreed.value = true; await workspace.generate(); await workspace.generate()
    workspace.agreed.value = true; await workspace.generate(true)
    const posts = workspace.calls.filter(call => call.options)
    assert.equal(posts[0].options.headers['Idempotency-Key'], posts[1].options.headers['Idempotency-Key'])
    assert.notEqual(posts[1].options.headers['Idempotency-Key'], posts[2].options.headers['Idempotency-Key'])
    assert.deepEqual(JSON.parse(posts[0].options.body), { expectedRevision: 1 })
  } finally { workspace.close() }
})

test('unknown and failed tasks do not schedule recovery submissions or polling', async () => {
  const workspace = setup(path => path.endsWith('/runtime') ? config : [{ id: 'unknown', status: 'UNKNOWN' }, { id: 'failed', status: 'FAILED' }])
  try { await flush(); assert.equal(workspace.scheduled.length, 0); assert.equal(workspace.calls.filter(call => call.options).length, 0) }
  finally { workspace.close() }
})

test('late account and project responses cannot reveal previous task records or enable a paid call', async () => {
  const pending = deferred()
  const workspace = setup(path => path.endsWith('/runtime') ? config : path.includes('/one/') ? pending.promise : [])
  try {
    await flush(); workspace.project.value = { id: 'two', revision: { number: 1 } }; workspace.user.value = { id: 2 }; await flush()
    pending.resolve([{ id: 'private-task', status: 'SUCCEEDED' }]); await flush()
    assert.deepEqual(workspace.tasks.value, []); assert.equal(workspace.agreed.value, false); assert.equal(workspace.ready.value, false)
  } finally { workspace.close() }
})

test('disabled runtime cannot POST even if the checkbox was previously selected', async () => {
  const workspace = setup(path => path.endsWith('/runtime') ? { ...config, ready: false } : [])
  try { await flush(); workspace.agreed.value = true; await workspace.generate(); assert.equal(workspace.calls.filter(call => call.options).length, 0) }
  finally { workspace.close() }
})

test('reading a successful new revision keeps the parent draft unchanged until user loads it', async () => {
  const workspace = setup(path => path.endsWith('/runtime') ? config : [{ id: 'done', status: 'SUCCEEDED', savedRevision: 2 }])
  try {
    await flush(); assert.equal(workspace.project.value.revision.number, 1); assert.equal(workspace.tasks.value[0].savedRevision, 2)
    assert.equal(workspace.calls.filter(call => call.options).length, 0)
  } finally { workspace.close() }
})
