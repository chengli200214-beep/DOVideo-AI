// 服务端 UrlIngestService 会把排队超过 3 小时、运行超过 60 分钟的任务判为中断（FAILED），
// 客户端等待上限取两者之和再加余量，避免比服务端更早放弃。
const SERVER_QUEUED_LIMIT_MS = 180 * 60 * 1000
const SERVER_RUNNING_LIMIT_MS = 60 * 60 * 1000
const MARGIN_MS = 15 * 60 * 1000
export const MAX_WAIT_MS = SERVER_QUEUED_LIMIT_MS + SERVER_RUNNING_LIMIT_MS + MARGIN_MS

const INITIAL_INTERVAL_MS = 2000
const MAX_INTERVAL_MS = 15000
const BACKOFF_FACTOR = 1.5
const MAX_FAILURE_MS = 2 * 60 * 1000
const KNOWN_STATUSES = ['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED']

const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
const isFatalStatus = status => status >= 400 && status < 500 && status !== 408 && status !== 429

/**
 * 链接下载在服务端异步执行：提交后拿到任务 ID，在这里轮询直到完成或失败。
 * isCurrent 返回 false（切换账号或发起了新上传）时静默停止并返回 null。
 * 轮询间隔从 2s 退避到 15s。网络错误、5xx、408/429、无法识别的响应都视为暂时故障，
 * 连续故障超过 2 分钟才放弃（抛出最后一次错误）；其他 4xx（如任务不存在）立即抛出。
 * 到达等待上限后会再轮询一次，避免后台标签页被节流时把已完成的任务误报为超时。
 */
export async function waitForUrlIngest(jobId, { request, isCurrent = () => true, sleep = wait, now = Date.now, onStatus } = {}) {
  const deadline = now() + MAX_WAIT_MS
  let interval = INITIAL_INTERVAL_MS
  let failingSince = null
  let finalPoll = false
  while (true) {
    if (!isCurrent()) return null
    try {
      const res = await request(`/media/upload-url/${encodeURIComponent(jobId)}`)
      if (!res.ok) {
        const error = new Error(isFatalStatus(res.status) ? await res.text() : '后端服务暂时不可用')
        error.fatal = isFatalStatus(res.status)
        throw error
      }
      const job = await res.json()
      if (!KNOWN_STATUSES.includes(job?.status)) throw new Error('后端返回了无法识别的任务状态')
      failingSince = null
      onStatus?.(job.status)
      if (job.status === 'COMPLETED') {
        if (!job.media) throw Object.assign(new Error('视频已入库，但该视频已被删除'), { fatal: true })
        return job.media
      }
      if (job.status === 'FAILED') throw Object.assign(new Error(job.error || '链接下载失败'), { fatal: true })
    } catch (error) {
      failingSince ??= now()
      if (error.fatal || now() - failingSince > MAX_FAILURE_MS) throw error
    }
    if (finalPoll) throw new Error('链接下载超时，请稍后在视频列表中查看')
    await sleep(interval)
    interval = Math.min(interval * BACKOFF_FACTOR, MAX_INTERVAL_MS)
    finalPoll = now() >= deadline
  }
}
