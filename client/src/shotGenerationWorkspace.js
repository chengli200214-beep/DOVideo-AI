import { computed, onScopeDispose, ref, watch } from 'vue'
import { createSubmissionIntent, generationIsActive } from './generationWorkspace.js'
import { createReadPolling } from './readPolling.js'

export function initialShotIds(project, overview) {
  const generated = new Set((overview?.versions || []).map(item => item.shotId))
  return (project?.revision.draft.shots || []).filter(shot => !generated.has(shot.id)).map(shot => shot.id)
}
export function initialShotsAvailable(ids, quote) {
  return ids.length > 0 && ids.every(id => quote?.shots.some(shot => shot.shotId === id && shot.available))
}
export function canRegenerateShot(version, project, versions) {
  if (!project?.revision.draft.shots.some(shot => shot.id === version.shotId)) return false
  const history = (versions || []).filter(item => item.shotId === version.shotId)
  if (history.some(item => generationIsActive(item.task.state) || item.task.state === 'SUBMISSION_UNKNOWN')) return false
  const latest = [...history].sort((a, b) => b.revision - a.revision || b.version - a.version)[0]
  return latest?.id === version.id && ['SUCCEEDED', 'FAILED'].includes(version.task.state)
}
export function useShotGenerationWorkspace({ user, project, dirty, request, captureSession, storage,
  newKey, digest, schedule = setTimeout, cancel = clearTimeout, eventTarget }) {
  const overview = ref(null), quote = ref(null), error = ref(''), notice = ref(''), busy = ref(false), loading = ref(false)
  const pendingInput = ref(null), artifact = ref(null)
  let epoch = 0, reads = 0, intent = null
  const cleanConfirmed = computed(() => !!project.value && !project.value.archived && !dirty.value && project.value.status === 'CONFIRMED'
    && project.value.confirmedRevision === project.value.revision.number)
  const initialIds = computed(() => initialShotIds(project.value, overview.value))
  function guard() {
    const version = epoch, id = project.value?.id, owner = user.value?.id, session = captureSession()
    return () => version === epoch && id === project.value?.id && owner === user.value?.id && !!owner && session()
  }
  const polling = createReadPolling({ refresh, active: () => overview.value?.versions.some(item => generationIsActive(item.task.state)), allowed: () => !!user.value && !!project.value, schedule, cancel, eventTarget })
  const stopTimer = polling.stop, poll = polling.succeeded
  async function refresh() {
    if (!project.value || !user.value) return
    const current = guard(), sequence = ++reads, id = project.value.id
    loading.value = true; stopTimer()
    try {
      const result = await request(`/generation/projects/${id}/generations`)
      if (!current() || sequence !== reads) return
      overview.value = result
      error.value = ''
      quote.value = null
      if (cleanConfirmed.value) {
        const revision = project.value.revision.number
        const planned = await request(`/generation/projects/${id}/generation-quote?revision=${revision}`)
        if (!current() || sequence !== reads) return
        quote.value = planned
      }
      if (current() && sequence === reads) poll()
    } catch (failure) { if (current() && sequence === reads) error.value = `状态刷新失败：${failure.message}；${polling.failed(failure) ? '将自动重试查询' : '请手动刷新'}` }
    finally { if (current() && sequence === reads) loading.value = false }
  }
  async function action(work, message) {
    if (busy.value || loading.value || !project.value || !user.value) return
    if (project.value.archived) { error.value = '项目已归档，请先恢复项目再修改或恢复任务'; return }
    const current = guard()
    reads += 1; busy.value = true; error.value = ''; notice.value = ''; stopTimer()
    try {
      await work(current)
      if (!current()) return
      notice.value = message
      await refresh()
    } catch (failure) { if (current()) { error.value = failure.message; poll() } }
    finally { if (current()) busy.value = false }
  }
  function configure(input) {
    const id = project.value?.id
    return action(() => request(`/generation/projects/${id}/budget`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) }), '项目额度已保存')
  }
  function submit(input) {
    if (!cleanConfirmed.value) { error.value = '请先保存并确认当前分镜'; return }
    const id = project.value.id
    const body = { revision: project.value.revision.number, mode: input.mode, shotIds: [...input.shotIds].sort() }
    return action(async current => {
      const key = await intent.keyFor(body)
      if (!current()) return
      pendingInput.value = body
      await request(`/generation/projects/${id}/generations`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify(body) })
      if (current()) { pendingInput.value = null; intent.reset() }
    }, input.mode === 'INITIAL' ? '镜头任务已提交，可在下方查询进度' : '已为目标镜头创建新的生成版本')
  }
  function retryPending() {
    if (pendingInput.value?.revision !== project.value?.revision.number) { error.value = '分镜版本已变化，请刷新并核对原提交结果'; return }
    return submit(pendingInput.value)
  }
  function recover(task) {
    return action(() => request(`/generation/tasks/${task.id}/retry`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }), '已恢复原任务的查询或归档，生成版本保持不变')
  }
  function reconcile(task, remoteId) {
    if (!remoteId?.trim()) { error.value = '请填写已核实的模型 requestId'; return }
    return action(() => request(`/generation/tasks/${task.id}/reconcile`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId: remoteId.trim() }) }), '已补录 requestId，继续查询原任务')
  }
  async function preview(task) {
    const current = guard(), taskId = task.id
    error.value = ''
    try {
      const url = await request(`/generation/tasks/${taskId}/artifact`)
      if (current()) artifact.value = { taskId, url }
    } catch (failure) { if (current()) error.value = failure.message }
  }
  watch(() => [user.value?.id, project.value?.id, project.value?.revision.number, project.value?.status], () => {
    epoch += 1; reads += 1; polling.reset()
    overview.value = null; quote.value = null; error.value = ''; notice.value = ''; busy.value = false; loading.value = false
    pendingInput.value = null; artifact.value = null
    intent = user.value && project.value ? createSubmissionIntent({ storage, scope: `shots:${user.value.id}:${project.value.id}`, newKey, digest }) : null
    if (user.value && project.value) refresh()
  }, { immediate: true })
  // Parent confirmation/save can finish after the first overview read. Fetch the
  // quote once the editor becomes clean, rather than requiring a manual refresh.
  watch(cleanConfirmed, value => { if (value) refresh() })
  onScopeDispose(() => { epoch += 1; polling.dispose() })
  return { overview, quote, error, notice, busy, loading, pendingInput, artifact, cleanConfirmed, initialIds,
    refresh, configure, submit, retryPending, recover, reconcile, preview }
}
