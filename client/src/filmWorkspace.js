import { computed, onScopeDispose, ref, watch } from 'vue'
import { createSubmissionIntent } from './generationWorkspace.js'

export function completeFilmSelection(project, versions, choices) {
  return project.revision.draft.shots.map(shot => {
    const id = choices[shot.id]
    if (!(versions || []).some(v => v.id === id && v.shotId === shot.id && v.revision === project.revision.number && v.task.state === 'SUCCEEDED')) throw new Error('请为每个镜头选择一个已完成的当前分镜版本')
    return id
  })
}
export function useFilmWorkspace({ user, project, dirty, versions, request, captureSession, storage, newKey, digest, schedule = setTimeout, cancel = clearTimeout }) {
  const films = ref(null), report = ref(null), cases = ref([]), runtime = ref(null), choices = ref({}), burnCaptions = ref(true)
  const busy = ref(false), loading = ref(false), error = ref(''), notice = ref(''), artifact = ref(null)
  let epoch = 0, sequence = 0, timer = null, intent = null
  const confirmed = computed(() => !dirty.value && project.value?.status === 'CONFIRMED' && project.value.confirmedRevision === project.value.revision.number)
  const selectionDirty = computed(() => {
    const saved = films.value?.selection
    return !saved || saved.snapshot.revision !== project.value.revision.number || JSON.stringify(saved.snapshot.clips.map(c => c.versionId)) !== JSON.stringify(project.value.revision.draft.shots.map(s => choices.value[s.id]))
  })
  const ready = computed(() => {
    try { completeFilmSelection(project.value, versions.value, choices.value); return confirmed.value } catch { return false }
  })
  function guard() { const revision = epoch, owner = user.value?.id, id = project.value?.id, session = captureSession(); return () => revision === epoch && owner === user.value?.id && id === project.value?.id && !!owner && session() }
  function stop() { if (timer !== null) cancel(timer); timer = null }
  function poll() { stop(); if (films.value?.tasks.some(t => ['QUEUED', 'RENDERING'].includes(t.state))) timer = schedule(() => { timer = null; refresh(false) }, 5000) }
  async function refresh(full = true) {
    if (!user.value || !project.value) return
    const current = guard(), seq = ++sequence, base = `/generation/projects/${project.value.id}`
    stop(); loading.value = true
    try {
      const result = await request(`${base}/films`)
      if (!current() || seq !== sequence) return
      films.value = result
      if (!Object.keys(choices.value).length && result.selection?.snapshot.revision === project.value.revision.number) for (const clip of result.selection.snapshot.clips) choices.value[clip.shotId] = clip.versionId
      defaults()
      if (full) {
        const result = await request(`${base}/evaluation-report`)
        if (!current() || seq !== sequence) return
        report.value = result
        if (!runtime.value) {
          const [fixed, tools] = await Promise.all([request('/generation/evaluation-cases'), request('/generation/runtime')])
          if (!current() || seq !== sequence) return
          cases.value = fixed; runtime.value = tools
        }
      }
      if (current() && seq === sequence) poll()
    } catch (failure) { if (current() && seq === sequence) error.value = `${failure.message}；可手动刷新` }
    finally { if (current() && seq === sequence) loading.value = false }
  }
  function defaults() {
    for (const shot of project.value?.revision.draft.shots || []) if (!choices.value[shot.id]) {
      const options = (versions.value || []).filter(v => v.revision === project.value.revision.number && v.shotId === shot.id && v.task.state === 'SUCCEEDED').sort((a, b) => b.version - a.version)
      if (options.length) choices.value[shot.id] = options[0].id
    }
  }
  async function action(work, message) {
    if (busy.value || loading.value || !user.value || !project.value) return
    const current = guard(); sequence += 1; busy.value = true; stop(); error.value = ''; notice.value = ''
    try { const result = await work(current); if (!current()) return; notice.value = message; await refresh(); return result }
    catch (failure) { if (current()) { error.value = failure.message; poll() } }
    finally { if (current()) busy.value = false }
  }
  function saveSelection() {
    if (!confirmed.value) { error.value = '请先保存并确认当前分镜'; return }
    const id = project.value.id, revision = project.value.revision.number
    return action(() => request(`/generation/projects/${id}/selections`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
      revision, expectedSelectionVersion: films.value?.selection?.number || 0, versionIds: completeFilmSelection(project.value, versions.value, choices.value)
    }) }), '镜头选择已保存为不可变版本')
  }
  function compose() {
    if (!confirmed.value || selectionDirty.value) { error.value = '请先确认分镜并保存镜头选择'; return }
    const id = project.value.id, input = { selectionVersion: films.value.selection.number, burnCaptions: burnCaptions.value }
    return action(async current => {
      const key = await intent.keyFor(input); if (!current()) return
      const result = await request(`/generation/projects/${id}/films`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify(input) })
      if (current()) intent.reset()
      return result
    }, '合成任务已提交，不会重新调用视频模型')
  }
  function retry(task) { const id = project.value.id; return action(() => request(`/generation/projects/${id}/films/${task.id}/retry`, { method: 'POST' }), '已恢复原合成任务') }
  function review(versionId, input) { const id = project.value.id; return action(() => request(`/generation/projects/${id}/reviews/${versionId}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) }), '人工质量评价已保存') }
  async function preview(task) {
    const current = guard(), id = project.value.id; error.value = ''
    try {
      const [url, detail] = await Promise.all([request(`/generation/projects/${id}/films/${task.id}/artifact`), request(`/generation/projects/${id}/films/${task.id}`)])
      if (current()) artifact.value = { id: task.id, url, detail }
    } catch (failure) { if (current()) error.value = failure.message }
  }
  watch(versions, defaults, { deep: true })
  watch(() => [user.value?.id, project.value?.id, project.value?.revision.number], () => {
    epoch += 1; sequence += 1; stop(); films.value = null; report.value = null; cases.value = []; runtime.value = null; choices.value = {}; artifact.value = null
    busy.value = false; loading.value = false; error.value = ''; notice.value = ''
    intent = user.value && project.value ? createSubmissionIntent({ storage, scope: `film:${user.value.id}:${project.value.id}`, newKey, digest }) : null
    if (user.value && project.value) refresh()
  }, { immediate: true })
  onScopeDispose(() => { epoch += 1; stop() })
  return { films, report, cases, runtime, choices, burnCaptions, busy, loading, error, notice, artifact, confirmed, selectionDirty, ready, refresh, saveSelection, compose, retry, review, preview }
}
