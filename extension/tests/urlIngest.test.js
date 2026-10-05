import assert from 'node:assert/strict'
import test from 'node:test'
import { MAX_WAIT_MS, waitForUrlIngest } from '../lib/urlIngest.js'

const reply = job => ({ ok: true, json: async () => job, text: async () => '' })
const noSleep = async () => {}
const failure = status => ({ ok: false, status, text: async () => '<html>bad gateway</html>', json: async () => null })
const fakeClock = () => {
  const clock = { t: 0, sleeps: [] }
  clock.now = () => clock.t
  clock.sleep = async ms => { clock.sleeps.push(ms); clock.t += ms }
  return clock
}


test('polls until the job completes and returns the media', async () => {
  const states = [{ status: 'QUEUED' }, { status: 'RUNNING' }, { status: 'COMPLETED', media: { id: 7 } }]
  const paths = []
  const media = await waitForUrlIngest('job-1', {
    request: async path => { paths.push(path); return reply(states.shift()) },
    sleep: noSleep
  })
  assert.deepEqual(media, { id: 7 })
  assert.deepEqual(paths, Array(3).fill('/media/upload-url/job-1'))
})

test('a failed job rejects with the server reason', async () => {
  await assert.rejects(
    waitForUrlIngest('job-1', {
      request: async () => reply({ status: 'FAILED', error: '不支持该平台链接' }),
      sleep: noSleep
    }),
    /不支持该平台链接/
  )
})

test('stops quietly once the upload is no longer current', async () => {
  let calls = 0
  const result = await waitForUrlIngest('job-1', {
    request: async () => { calls += 1; return reply({ status: 'RUNNING' }) },
    isCurrent: () => calls < 2,
    sleep: noSleep
  })
  assert.equal(result, null)
  assert.equal(calls, 2)
})

test('tolerates brief network failures while polling', async () => {
  let calls = 0
  const media = await waitForUrlIngest('job-1', {
    request: async () => {
      calls += 1
      if (calls <= 4) throw new Error('无法连接后端服务')
      return reply({ status: 'COMPLETED', media: { id: 9 } })
    },
    sleep: noSleep
  })
  assert.deepEqual(media, { id: 9 })
})

test('gives up after two minutes of continuous network failures', async () => {
  const clock = fakeClock()
  await assert.rejects(
    waitForUrlIngest('job-1', {
      request: async () => { throw new Error('无法连接后端服务') },
      sleep: clock.sleep,
      now: clock.now
    }),
    /无法连接后端服务/
  )
})

test('a missing job (404) fails immediately without retrying', async () => {
  let calls = 0
  await assert.rejects(
    waitForUrlIngest('job-1', {
      request: async () => {
        calls += 1
        return { ok: false, status: 404, text: async () => '链接下载任务不存在或已过期', json: async () => null }
      },
      sleep: noSleep
    }),
    /不存在或已过期/
  )
  assert.equal(calls, 1)
})

test('a completed job whose media was deleted rejects with a clear message', async () => {
  await assert.rejects(
    waitForUrlIngest('job-1', {
      request: async () => reply({ status: 'COMPLETED', media: null }),
      sleep: noSleep
    }),
    /视频已被删除/
  )
})

test('a 503 response is retried and then succeeds', async () => {
  const replies = [failure(503), failure(503), reply({ status: 'COMPLETED', media: { id: 1 } })]
  const media = await waitForUrlIngest('job-1', { request: async () => replies.shift(), sleep: noSleep })
  assert.deepEqual(media, { id: 1 })
})

test('a 429 response is retried', async () => {
  const replies = [failure(429), reply({ status: 'COMPLETED', media: { id: 2 } })]
  const media = await waitForUrlIngest('job-1', { request: async () => replies.shift(), sleep: noSleep })
  assert.deepEqual(media, { id: 2 })
})

test('a 401 fails immediately after one call', async () => {
  let calls = 0
  await assert.rejects(
    waitForUrlIngest('job-1', { request: async () => { calls += 1; return failure(401) }, sleep: noSleep }),
    /bad gateway/
  )
  assert.equal(calls, 1)
})

test('non-ok retryable responses give up with a fixed message', async () => {
  const clock = fakeClock()
  await assert.rejects(
    waitForUrlIngest('job-1', { request: async () => failure(502), sleep: clock.sleep, now: clock.now }),
    /后端服务暂时不可用/
  )
})

test('the failure window resets after a successful poll', async () => {
  const clock = fakeClock()
  const plan = []
  // 100s of failures, one good poll, another 100s of failures, then completion
  let calls = 0
  const request = async () => {
    calls += 1
    const t = clock.t
    if (t < 100000) throw new Error('down')
    if (t < 100000 + 15000) return reply({ status: 'RUNNING' })
    if (t < 220000) throw new Error('down')
    plan.push(t)
    return reply({ status: 'COMPLETED', media: { id: 3 } })
  }
  const media = await waitForUrlIngest('job-1', { request, sleep: clock.sleep, now: clock.now })
  assert.deepEqual(media, { id: 3 })
})

test('null or unknown job status counts as a failure', async () => {
  for (const job of [null, { status: 'WEIRD' }]) {
    const clock = fakeClock()
    let calls = 0
    await assert.rejects(
      waitForUrlIngest('job-1', { request: async () => { calls += 1; return reply(job) }, sleep: clock.sleep, now: clock.now })
    )
    assert.ok(calls > 5)
    assert.ok(clock.t > 120000)
  }
})

test('no further request is made once the upload is not current', async () => {
  let calls = 0
  let current = true
  const result = await waitForUrlIngest('job-1', {
    request: async () => { calls += 1; current = false; return reply({ status: 'RUNNING' }) },
    isCurrent: () => current,
    sleep: noSleep
  })
  assert.equal(result, null)
  assert.equal(calls, 1)
})

test('polling backs off from 2s and caps at 15s', async () => {
  const clock = fakeClock()
  const replies = Array(10).fill({ status: 'RUNNING' }).concat({ status: 'COMPLETED', media: { id: 4 } })
  await waitForUrlIngest('job-1', { request: async () => reply(replies.shift()), sleep: clock.sleep, now: clock.now })
  assert.equal(clock.sleeps[0], 2000)
  assert.equal(clock.sleeps[1], 3000)
  assert.equal(Math.max(...clock.sleeps), 15000)
  assert.equal(clock.sleeps.at(-1), 15000)
})

test('times out after MAX_WAIT_MS and does a final poll first', async () => {
  const clock = fakeClock()
  const polledAt = []
  await assert.rejects(
    waitForUrlIngest('job-1', {
      request: async () => { polledAt.push(clock.t); return reply({ status: 'RUNNING' }) },
      sleep: clock.sleep,
      now: clock.now
    }),
    /链接下载超时/
  )
  assert.ok(polledAt.at(-1) >= MAX_WAIT_MS)
  assert.ok(polledAt.at(-2) < MAX_WAIT_MS)
})

test('a job finishing at the deadline is still returned by the final poll', async () => {
  const clock = fakeClock()
  const media = await waitForUrlIngest('job-1', {
    request: async () => reply(clock.t >= MAX_WAIT_MS ? { status: 'COMPLETED', media: { id: 5 } } : { status: 'RUNNING' }),
    sleep: clock.sleep,
    now: clock.now
  })
  assert.deepEqual(media, { id: 5 })
})

test('onStatus receives each job status', async () => {
  const states = [{ status: 'QUEUED' }, { status: 'RUNNING' }, { status: 'COMPLETED', media: { id: 6 } }]
  const seen = []
  await waitForUrlIngest('job-1', {
    request: async () => reply(states.shift()),
    sleep: noSleep,
    onStatus: status => seen.push(status)
  })
  assert.deepEqual(seen, ['QUEUED', 'RUNNING', 'COMPLETED'])
})
