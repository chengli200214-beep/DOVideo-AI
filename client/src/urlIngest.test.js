import assert from 'node:assert/strict'
import test from 'node:test'
import { waitForUrlIngest } from './urlIngest.js'

const reply = job => ({ ok: true, json: async () => job, text: async () => '' })
const noSleep = async () => {}

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

test('gives up after repeated network failures', async () => {
  await assert.rejects(
    waitForUrlIngest('job-1', {
      request: async () => { throw new Error('无法连接后端服务') },
      sleep: noSleep
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

test('times out when the job never finishes', async () => {
  let clock = 0
  await assert.rejects(
    waitForUrlIngest('job-1', {
      request: async () => reply({ status: 'RUNNING' }),
      sleep: async () => { clock += 12 * 60 * 1000 },
      now: () => clock
    }),
    /链接下载超时/
  )
})
