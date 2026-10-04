import test from 'node:test'
import assert from 'node:assert/strict'
import { createReadPolling } from './readPolling.js'

function setup() {
  const timers = new Map(), listeners = new Map(), reads = []
  let active = true, allowed = true, next = 0
  const poller = createReadPolling({ refresh: () => reads.push('GET'), active: () => active, allowed: () => allowed,
    schedule: (callback, delay) => { const id = ++next; timers.set(id, { callback, delay }); return id }, cancel: id => timers.delete(id),
    eventTarget: { addEventListener: (name, callback) => listeners.set(name, callback), removeEventListener: name => listeners.delete(name) } })
  return { poller, timers, listeners, reads, setActive: value => { active = value }, setAllowed: value => { allowed = value } }
}
test('temporary GET failures back off finitely and success restores the normal interval', () => {
  const { poller, timers } = setup()
  const delays = []
  for (let i = 0; i < 5; i++) {
    assert.equal(poller.failed(new Error('offline')), true)
    delays.push([...timers.values()][0].delay)
  }
  assert.deepEqual(delays, [5000, 10000, 20000, 40000, 60000])
  assert.equal(poller.failed(new Error('offline')), false); assert.equal(timers.size, 0)
  poller.succeeded(); assert.equal([...timers.values()][0].delay, 5000)
  assert.equal(poller.failed({ status: 503 }), true)
  poller.dispose()
})
test('online resumes reads, terminal states and permanent HTTP failures stop, dispose removes all callbacks', () => {
  const context = setup(), { poller, timers, listeners, reads } = context
  poller.succeeded(); const stale = [...timers.values()][0].callback
  poller.reset(); stale(); assert.deepEqual(reads, [])
  for (const status of [400, 401, 403, 404, 409]) { assert.equal(poller.failed({ status }), false); assert.equal(timers.size, 0) }
  context.setActive(false); poller.succeeded(); assert.equal(timers.size, 0)
  listeners.get('online')(); assert.deepEqual(reads, ['GET'])
  context.setAllowed(false); listeners.get('online')(); assert.deepEqual(reads, ['GET'])
  poller.dispose(); assert.equal(listeners.size, 0); assert.equal(timers.size, 0)
})
