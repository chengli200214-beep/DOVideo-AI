import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, ref } from 'vue'
import { applyProjectArchiveState, projectLibraryActionAllowed, useLibraryAction } from './libraryActions.js'

const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
function setup() {
  const scope = effectScope(), user = ref({ id: 1 }), busyEvents = [], invalidated = []
  let session = 1
  const action = scope.run(() => useLibraryAction({ user, captureSession: () => { const saved = session; return () => saved === session },
    onBusy: value => busyEvents.push(value), onInvalidate: reason => invalidated.push(reason) }))
  return { ...action, user, busyEvents, invalidated, changeSession: () => { session++ }, close: () => scope.stop() }
}
test('a token change drops late operation data but releases the parent write lock', async () => {
  const action = setup(), reply = deferred(), published = []
  try {
    const operation = action.run(async current => { const result = await reply.promise; if (current()) published.push(result) })
    assert.equal(action.busy.value, true); assert.deepEqual(action.busyEvents, [true])
    action.changeSession(); reply.resolve('private old-session result'); await operation
    assert.deepEqual(published, []); assert.equal(action.busy.value, false)
    assert.deepEqual(action.busyEvents, [true, false]); assert.deepEqual(action.invalidated, ['session']); assert.match(action.error.value, /会话已变化/)
  } finally { action.close() }
})
test('an old account operation cannot release a new account operation lock or publish its result', async () => {
  const action = setup(), old = deferred(), fresh = deferred(), published = []
  try {
    const one = action.run(async current => { await old.promise; if (current()) published.push('old') })
    action.user.value = { id: 2 }
    assert.equal(action.busy.value, false)
    const two = action.run(async current => { await fresh.promise; if (current()) published.push('new') })
    old.resolve(); await one; assert.equal(action.busy.value, true); assert.deepEqual(published, [])
    fresh.resolve(); await two; assert.equal(action.busy.value, false); assert.deepEqual(published, ['new'])
  } finally { action.close() }
})
test('restoring an archived project with a local draft is permitted and changes no revision or editor data', () => {
  const state = { blocked: false, busy: false, dirty: true }, archived = { id: 'one', archived: true, revision: { number: 3, draft: { script: 'saved' } } }
  assert.equal(projectLibraryActionAllowed(state, archived), true)
  assert.equal(projectLibraryActionAllowed(state, { ...archived, archived: false }), false)
  assert.equal(projectLibraryActionAllowed({ ...state, busy: true }, archived), false)
  assert.equal(projectLibraryActionAllowed({ ...state, blocked: true }, archived), false)
  const restored = applyProjectArchiveState(archived, { id: 'one', archived: false })
  assert.equal(restored.archived, false); assert.equal(restored.revision, archived.revision)
  assert.equal(archived.archived, true)
  assert.equal(applyProjectArchiveState(restored, { id: 'another', archived: true }), restored)
})
test('failed actions and disposal release busy and never apply an unmounted result', async () => {
  const action = setup(), reply = deferred(), published = []
  await action.run(async () => { throw new Error('request rejected') })
  assert.equal(action.busy.value, false); assert.match(action.error.value, /request rejected/)
  const pending = action.run(async current => { await reply.promise; if (current()) published.push('late') })
  action.close(); reply.resolve(); await pending
  assert.equal(action.busy.value, false); assert.deepEqual(published, [])
})
