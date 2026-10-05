import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick, ref } from 'vue'
import { apiRequest, bindAuthSession, captureAuthSession } from './api.js'
import { listenForAccountChanges } from './authAccountSync.js'
import { emptyShot, storyboardApi, useStoryboardWorkspace } from './storyboardWorkspace.js'

function storageFixture() {
  const values = new Map()
  return { values, getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
}
const flush = async () => { await nextTick(); await new Promise(resolve => setImmediate(resolve)) }
const settled = async workspace => {
  for (let i = 0; i < 100; i++) {
    await flush()
    if (!workspace.loading.value && !workspace.busy.value) return
  }
  assert.fail('workspace did not finish fixture requests')
}

test('an account change before its storage event cannot post or put another account project into the old draft cache', async () => {
  const storage = storageFixture(); globalThis.localStorage = storage; globalThis.window = new EventTarget()
  storage.setItem('authToken', 'user-1'); storage.setItem('user', JSON.stringify({ id: 1 })); bindAuthSession()
  const user = ref({ id: 1 }), scope = effectScope(), calls = []
  const stop = listenForAccountChanges({ target: window, storage, onChange: value => { user.value = value } })
  const saved = { id: 'project-user-2', status: 'DRAFT', brief: { title: 'fixture', desiredDurationSeconds: 10 },
    revision: { number: 1, draft: { script: 'saved', shots: [emptyShot(1, '9:16', 'one'), emptyShot(2, '9:16', 'two')] } } }
  globalThis.fetch = async (url, options) => {
    calls.push({ url, method: options.method || 'GET', token: options.headers.get('Authorization') })
    return Response.json({ code: 0, message: 'ok', data: url.endsWith('/revisions') ? { revisions: [], confirmations: [] }
      : options.method === 'POST' ? { project: saved } : [] })
  }
  const workspace = scope.run(() => useStoryboardWorkspace({ user, request: storyboardApi(apiRequest), captureSession: captureAuthSession,
    storage, draftStorage: storage, newKey: () => 'fixture-key', digest: async value => value }))
  try {
    await settled(workspace)
    storage.setItem('authToken', 'user-2'); storage.setItem('user', JSON.stringify({ id: 2 }))
    // No storage event yet: this click begins in account 1's component.
    await workspace.create({ title: 'fixture' }); await settled(workspace)
    assert.equal(calls.filter(call => call.method === 'POST').length, 0)
    assert.equal(user.value.id, 2); assert.equal(workspace.project.value, null)
    await workspace.create({ title: 'fixture' }); await settled(workspace)
    assert.equal(calls.filter(call => call.method === 'POST').length, 1)
    assert.equal(calls.find(call => call.method === 'POST').token, 'Bearer user-2')
    workspace.draft.value.script = 'edited by user 2'
    assert.equal(storage.values.has('storyboard-draft:1:project-user-2'), false)
    assert.equal(storage.values.has('storyboard-draft:2:project-user-2'), true)
  } finally { scope.stop(); stop() }
})

test('storage login/logout changes synchronize identity, ignore unrelated keys and dispose listeners', () => {
  const storage = storageFixture(); globalThis.localStorage = storage; const target = new EventTarget(); const users = []
  bindAuthSession()
  const stop = listenForAccountChanges({ target, storage, onChange: value => users.push(value) })
  const dispatch = key => { const event = new Event('storage'); event.key = key; event.storageArea = storage; target.dispatchEvent(event) }
  storage.setItem('authToken', 'user-2'); storage.setItem('user', JSON.stringify({ id: 2 }))
  dispatch('storyboard-draft:2:project'); assert.equal(users.length, 0)
  dispatch('authToken'); assert.equal(users.at(-1).id, 2)
  const old = captureAuthSession(); storage.removeItem('authToken'); dispatch('authToken')
  assert.equal(users.at(-1), null); assert.equal(old(), false)
  storage.setItem('authToken', 'invalid-user'); storage.setItem('user', '{bad'); dispatch('user')
  assert.equal(users.at(-1), null)
  const count = users.length; stop(); dispatch(null); assert.equal(users.length, count)
})
