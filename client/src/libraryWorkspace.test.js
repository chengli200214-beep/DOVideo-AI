import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick, ref } from 'vue'
import { useCursorLibrary } from './libraryWorkspace.js'

const flush = async () => { await nextTick(); for (let i = 0; i < 6; i++) await Promise.resolve() }
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
function setup(respond, initialQuery = {}) {
  const user = ref({ id: 1 }), query = ref(initialQuery), calls = [], scope = effectScope()
  let session = 0
  const workspace = scope.run(() => useCursorLibrary({ user, query, path: '/library', request: async path => { calls.push(path); return respond(path) },
    captureSession: () => { const captured = session; return () => captured === session } }))
  return { ...workspace, user, query, calls, changeSession: () => { session++ }, close: () => scope.stop() }
}
test('default empty query reads once and pagination preserves search, cursor, and unique items', async () => {
  const workspace = setup(path => new URL(path, 'http://local').searchParams.has('beforeId')
    ? { items: [{ id: 'a', title: 'updated' }, { id: 'b' }], nextCursor: null }
    : { items: [{ id: 'a', title: 'original' }], nextCursor: { updatedAt: 1234, id: 'a' } })
  try {
    await flush(); assert.equal(workspace.calls.length, 1); assert.equal(new URL(workspace.calls[0], 'http://local').searchParams.get('limit'), '20')
    workspace.query.value = {}; await flush(); assert.equal(workspace.calls.length, 1)
    await workspace.load(true)
    const params = new URL(workspace.calls[1], 'http://local').searchParams
    assert.equal(params.get('beforeUpdatedAt'), '1234'); assert.equal(params.get('beforeId'), 'a')
    assert.deepEqual(workspace.items.value, [{ id: 'a', title: 'updated' }, { id: 'b' }]); assert.equal(workspace.nextCursor.value, null)
    await workspace.load(true); assert.equal(workspace.calls.length, 2)
  } finally { workspace.close() }
})
test('search and archive filter changes reset pagination once and discard an earlier in-flight page', async () => {
  const old = deferred()
  const workspace = setup(path => new URL(path, 'http://local').searchParams.has('beforeId') ? old.promise : {
    items: [{ id: new URL(path, 'http://local').searchParams.get('q') || 'all' }], nextCursor: { updatedAt: 10, id: 'cursor' }
  }, { q: '', archived: 'false' })
  try {
    await flush(); const loadingOld = workspace.load(true)
    workspace.query.value = { q: '水杯 & 台灯', archived: 'true' }
    assert.deepEqual(workspace.items.value, []); assert.equal(workspace.nextCursor.value, null)
    await flush(); old.resolve({ items: [{ id: 'old-page' }], nextCursor: null }); await loadingOld
    assert.equal(workspace.calls.length, 3); assert.deepEqual(workspace.items.value.map(item => item.id), ['水杯 & 台灯'])
    const params = new URL(workspace.calls.at(-1), 'http://local').searchParams
    assert.equal(params.get('q'), '水杯 & 台灯'); assert.equal(params.get('archived'), 'true'); assert.equal(params.has('beforeId'), false)
    workspace.query.value = { archived: 'true', q: '水杯 & 台灯' }; await flush(); assert.equal(workspace.calls.length, 3)
  } finally { workspace.close() }
})
test('account change, logout, changed auth session, and unmount discard private late responses', async () => {
  const replies = []
  const workspace = setup(() => { const reply = deferred(); replies.push(reply); return reply.promise })
  await flush(); workspace.user.value = { id: 2 }; await flush()
  replies[0].resolve({ items: [{ id: 'account-one-private' }], nextCursor: null }); await flush()
  assert.deepEqual(workspace.items.value, [])
  replies[1].resolve({ items: [{ id: 'account-two' }], nextCursor: null }); await flush()
  assert.deepEqual(workspace.items.value.map(item => item.id), ['account-two'])
  const reload = workspace.load(); workspace.changeSession(); replies[2].resolve({ items: [{ id: 'stale-token-private' }], nextCursor: null }); await reload
  assert.deepEqual(workspace.items.value, []); assert.equal(workspace.loading.value, false)
  const last = workspace.load(); workspace.user.value = null; replies[3].resolve({ items: [{ id: 'logged-out-private' }], nextCursor: null }); await last
  assert.deepEqual(workspace.items.value, []); assert.equal(workspace.loading.value, false)
  workspace.user.value = { id: 3 }; workspace.close(); replies[4].resolve({ items: [{ id: 'unmounted' }], nextCursor: null }); await flush()
  assert.deepEqual(workspace.items.value, [])
})
test('asset cursors use creation time and a failed page can be retried without dropping previous results', async () => {
  let failure = true
  const workspace = setup(path => {
    const params = new URL(path, 'http://local').searchParams
    if (!params.has('beforeId')) return { items: [{ asset: { id: 'asset-a' } }], nextCursor: { createdAt: 0, id: 'asset-a' } }
    if (failure) { failure = false; throw new Error('temporary failure') }
    return { items: [{ asset: { id: 'asset-b' } }], nextCursor: null }
  })
  try {
    await flush(); await workspace.load(true)
    assert.match(workspace.error.value, /temporary/); assert.equal(workspace.loading.value, false); assert.equal(workspace.items.value.length, 1)
    await workspace.load(true)
    assert.deepEqual(workspace.items.value.map(item => item.asset.id), ['asset-a', 'asset-b']); assert.equal(workspace.error.value, '')
    assert.equal(new URL(workspace.calls.at(-1), 'http://local').searchParams.get('beforeCreatedAt'), '0')
  } finally { workspace.close() }
})
