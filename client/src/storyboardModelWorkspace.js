import { computed, onScopeDispose, ref, watch } from 'vue'
import { createSubmissionIntent } from './generationWorkspace.js'
import { createReadPolling } from './readPolling.js'

export const planningStates = { QUEUED: '等待请求', SUBMITTING: '模型生成中', SUCCEEDED: '新分镜已保存', FAILED: '输出未通过校验', REJECTED: '服务拒绝请求', UNKNOWN: '结果待人工核对', CONFLICT: '版本已变化', BLOCKED: '调用被关闭' }
const active = task => ['QUEUED', 'SUBMITTING'].includes(task.status)

/** Automatic work is GET-only. Every paid POST requires an explicit click and checked cost acknowledgement. */
export function useStoryboardModelWorkspace({ user, project, dirty, request, captureSession, storage, newKey, digest, schedule = setTimeout, cancel = clearTimeout, eventTarget }) {
  const runtime = ref(null), tasks = ref([]), agreed = ref(false), busy = ref(false), loading = ref(false), error = ref(''), notice = ref('')
  let epoch = 0, sequence = 0, intent = null
  const hasActive = computed(() => tasks.value.some(active))
  const ready = computed(() => runtime.value?.ready && !project.value?.archived && !dirty.value && agreed.value && !busy.value && !loading.value && !hasActive.value)
  function guard() { const e = epoch, account = user.value?.id, id = project.value?.id, session = captureSession(); return () => e === epoch && account === user.value?.id && id === project.value?.id && !!account && session() }
  const polling = createReadPolling({ refresh, active: () => hasActive.value, allowed: () => !!user.value && !!project.value, schedule, cancel, eventTarget })
  const stop = polling.stop, poll = polling.succeeded
  async function refresh() {
    if (!user.value || !project.value) return
    const current = guard(), seq = ++sequence, base = `/generation/projects/${project.value.id}`
    stop(); loading.value = true
    try {
      const [config, list] = await Promise.all([request('/generation/storyboard-model/runtime'), request(`${base}/planning`)])
      if (!current() || seq !== sequence) return
      runtime.value = config; tasks.value = list; error.value = ''; poll()
    } catch (failure) { if (current() && seq === sequence) error.value = `${failure.message}；${polling.failed(failure) ? '将自动重试查询' : '请手动查询状态'}` }
    finally { if (current() && seq === sequence) loading.value = false }
  }
  async function generate(newCall = false) {
    if (!ready.value) return
    const current = guard(), id = project.value.id, expectedRevision = project.value.revision.number
    busy.value = true; error.value = ''; notice.value = ''
    try {
      if (newCall) intent.reset()
      const key = await intent.keyFor({ expectedRevision })
      if (!current()) return
      const task = await request(`/generation/projects/${id}/planning`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify({ expectedRevision }) })
      if (!current()) return
      tasks.value = [task, ...tasks.value.filter(item => item.id !== task.id)]
      agreed.value = false; notice.value = '分镜任务已记录；完成后请载入并检查新版本，尚未提交视频模型。'
      poll()
    } catch (failure) { if (current()) error.value = failure.message }
    finally { if (current()) busy.value = false }
  }
  watch(() => [user.value?.id, project.value?.id, project.value?.revision.number], () => {
    epoch += 1; sequence += 1; polling.reset(); runtime.value = null; tasks.value = []; agreed.value = false; busy.value = false; loading.value = false; error.value = ''; notice.value = ''
    intent = user.value && project.value ? createSubmissionIntent({ storage, scope: `planning:${user.value.id}:${project.value.id}`, newKey, digest }) : null
    if (intent) refresh()
  }, { immediate: true })
  onScopeDispose(() => { epoch += 1; polling.dispose() })
  return { runtime, tasks, agreed, busy, loading, error, notice, ready, hasActive, refresh, generate }
}
