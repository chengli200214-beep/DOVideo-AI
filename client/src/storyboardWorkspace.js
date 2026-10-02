import { computed, ref, watch, onScopeDispose } from 'vue'
import { createSubmissionIntent } from './generationWorkspace.js'

const clone = value => JSON.parse(JSON.stringify(value))
export const projectStates = { DRAFT: '待确认', CONFIRMED: '已确认', NEEDS_EDIT: '需人工补充' }
export function composeShotPrompt(shot, style) {
  return [shot.subject, shot.action, shot.setting, shot.camera, style].filter(value => value?.trim()).map(value => value.trim()).join('，')
}
export function normalizeStoryboardEdit(input) {
  const draft = clone(input)
  if (!draft.script?.trim() || !Array.isArray(draft.shots) || draft.shots.length < 2 || draft.shots.length > 8) throw new Error('请填写脚本并保留 2–8 个镜头')
  draft.shots.forEach((shot, index) => {
    shot.sequence = index + 1
    shot.parameters ||= { negativePrompt: null, seed: null }
    const value = shot.parameters.seed
    shot.parameters.seed = value === '' || value == null ? null : Number(value)
    if (shot.parameters.seed != null && (!Number.isSafeInteger(shot.parameters.seed) || shot.parameters.seed < 0)) throw new Error('随机种子需为非负整数')
    shot.referenceAssetId ||= null
  })
  return draft
}
export function emptyShot(sequence, ratio, id = crypto.randomUUID()) {
  return { id, sequence, title: `镜头 ${sequence}`, subject: '', action: '', setting: '', camera: '', referenceAssetId: null,
    desiredDurationSeconds: 5, frameRatio: ratio, caption: '', narration: '', prompt: '', promptVersion: 1,
    parameters: { negativePrompt: null, seed: null } }
}
export function storyboardApi(apiRequest) {
  return async (path, options) => {
    const response = await apiRequest(path, options)
    if (!response.ok) {
      const error = new Error(await response.text() || '请求失败')
      error.status = response.status
      throw error
    }
    return response.json()
  }
}

/** Account and project guards keep late responses away from the currently edited draft. */
export function useStoryboardWorkspace({ user, request, captureSession, storage, newKey, digest }) {
  const projects = ref([]), project = ref(null), draft = ref(null), history = ref(null)
  const error = ref(''), notice = ref(''), busy = ref(false), loading = ref(false)
  const dirty = computed(() => Boolean(draft.value && project.value && JSON.stringify(draft.value) !== JSON.stringify(project.value.revision.draft)))
  const totalDuration = computed(() => draft.value?.shots.reduce((sum, shot) => sum + Number(shot.desiredDurationSeconds || 0), 0) || 0)
  let epoch = 0, selection = 0, loadSequence = 0, intent = null
  const guard = () => {
    const currentEpoch = epoch, account = user.value?.id, session = captureSession()
    return () => currentEpoch === epoch && account === user.value?.id && session()
  }
  function accept(data) {
    selection += 1; project.value = data; draft.value = clone(data.revision.draft); history.value = null
    projects.value = [{ id: data.id, title: data.brief.title, status: data.status, revision: data.revision.number, updatedAt: data.updatedAt }, ...projects.value.filter(item => item.id !== data.id)]
  }
  async function historyFor() {
    if (!project.value) return
    const current = guard(), selected = selection, id = project.value.id
    const result = await request(`/generation/projects/${id}/revisions`)
    if (current() && selected === selection) history.value = result
  }
  async function load() {
    if (!user.value) return
    const current = guard(), sequence = ++loadSequence, selected = selection
    loading.value = true
    try {
      const recent = await request('/generation/projects')
      if (!current() || sequence !== loadSequence) return
      projects.value = recent
      if (!project.value && !busy.value && selected === selection && recent.length) await select(recent[0].id)
    } catch (failure) { if (current() && sequence === loadSequence) error.value = failure.message }
    finally { if (current() && sequence === loadSequence && selected === selection) loading.value = false }
  }
  async function select(id) {
    if (busy.value) return
    const current = guard(), selected = ++selection
    let accepted = false
    loading.value = true; error.value = ''; notice.value = ''
    try {
      const [view, versions] = await Promise.all([request(`/generation/projects/${id}`), request(`/generation/projects/${id}/revisions`)])
      if (!current() || selected !== selection) return
      accept(view); accepted = true; history.value = versions
    } catch (failure) { if (current() && selected === selection) error.value = failure.message }
    finally { if (current() && (selected === selection || (accepted && selection === selected + 1))) loading.value = false }
  }
  async function write(work, message) {
    if (busy.value || loading.value || !user.value) return
    const current = guard()
    busy.value = true; error.value = ''; notice.value = ''
    try {
      const saved = await work(current)
      if (!current() || !saved) return
      accept(saved); notice.value = message
      try { await historyFor() } catch { if (current()) notice.value += '；版本记录暂未加载，可刷新项目' }
    } catch (failure) {
      if (current()) error.value = failure.status === 409 ? `${failure.message}。当前未保存编辑已保留，请对照最新版本后处理。` : failure.message
    } finally { if (current()) busy.value = false }
  }
  function create(brief) {
    return write(async current => {
      const key = await intent.keyFor(brief)
      if (!current()) return null
      const response = await request('/generation/projects', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify(brief) })
      return response.project
    }, '模板草稿已保存，请逐镜头检查后确认')
  }
  function save() {
    if (!project.value || !draft.value) return
    const id = project.value.id, expectedRevision = project.value.revision.number
    return write(async () => request(`/generation/projects/${id}/revisions`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedRevision, draft: normalizeStoryboardEdit(draft.value) })
    }), '编辑稿已保存为新版本，需重新确认')
  }
  function confirm() {
    if (!project.value || dirty.value) { error.value = '请先保存当前编辑稿，再确认该版本'; return }
    const id = project.value.id, expectedRevision = project.value.revision.number
    return write(async () => request(`/generation/projects/${id}/confirm`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ expectedRevision }) }), '当前分镜已确认，尚未提交视频生成')
  }
  function restore(revision) { draft.value = clone(revision.draft); notice.value = `已将版本 ${revision.number} 载入编辑区；保存后创建新版本` }
  function reset() {
    selection += 1; loadSequence += 1; intent?.reset(); project.value = null; draft.value = null; history.value = null; error.value = ''; notice.value = ''; loading.value = false
  }
  watch(() => user.value?.id, () => {
    epoch += 1; selection += 1; loadSequence += 1
    projects.value = []; project.value = null; draft.value = null; history.value = null; error.value = ''; notice.value = ''; busy.value = false; loading.value = false
    intent = user.value ? createSubmissionIntent({ storage, scope: `storyboard:${user.value.id}`, newKey, digest }) : null
    if (user.value) load()
  }, { immediate: true })
  onScopeDispose(() => { epoch += 1 })
  return { projects, project, draft, history, error, notice, busy, loading, dirty, totalDuration, load, select, create, save, confirm, restore, reset }
}
