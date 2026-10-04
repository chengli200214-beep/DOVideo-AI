const POLL_INTERVAL_MS = 2000
const MAX_WAIT_MS = 35 * 60 * 1000
const MAX_CONSECUTIVE_FAILURES = 5

const wait = ms => new Promise(resolve => setTimeout(resolve, ms))

/**
 * 链接下载在服务端异步执行：提交后拿到任务 ID，在这里轮询直到完成或失败。
 * isCurrent 返回 false（切换账号或发起了新上传）时静默停止并返回 null。
 * 短暂断网或 5xx 不会中断等待，服务端任务仍在继续；连续失败 5 次才放弃。
 * 4xx（如任务不存在）属于确定性错误，立即抛出。
 */
export async function waitForUrlIngest(jobId, { request, isCurrent = () => true, sleep = wait, now = Date.now } = {}) {
  const deadline = now() + MAX_WAIT_MS
  let failures = 0
  while (now() < deadline) {
    if (!isCurrent()) return null
    let job
    try {
      const res = await request(`/media/upload-url/${encodeURIComponent(jobId)}`)
      if (!res.ok) {
        const error = new Error(await res.text())
        error.fatal = res.status >= 400 && res.status < 500
        throw error
      }
      job = await res.json()
      failures = 0
    } catch (error) {
      failures += 1
      if (error.fatal || failures >= MAX_CONSECUTIVE_FAILURES) throw error
    }
    if (job?.status === 'COMPLETED') {
      if (!job.media) throw new Error('视频已入库，但该视频已被删除')
      return job.media
    }
    if (job?.status === 'FAILED') throw new Error(job.error || '链接下载失败')
    await sleep(POLL_INTERVAL_MS)
  }
  throw new Error('链接下载超时，请稍后在视频列表中查看')
}
