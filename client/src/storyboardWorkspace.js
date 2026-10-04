import { computed, ref, watch, onScopeDispose } from 'vue'
import { createSubmissionIntent } from './generationWorkspace.js'
import { createStoryboardDraftStore } from './storyboardDrafts.js'

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
export function useStoryboardWorkspace({ user, request, captureSession, storage, draftStorage = storage, newKey, digest }) {
  const projects = ref([]), project = ref(null), draft = ref(null), history = ref(null)
  const error = ref(''), notice = ref(''), busy = ref(false), loading = ref(false)
  const draftConflict = ref(null), draftStorageError = ref(''), savedLocally = ref(false)
  const dirty = computed(() => Boolean(draft.value && project.value && JSON.stringify(draft.value) !== JSON.stringify(project.value.revision.draft)))
  const totalDuration = computed(() => draft.value?.shots.reduce((sum, shot) => sum + Number(shot.desiredDurationSeconds || 0), 0) || 0)
  let epoch = 0, selection = 0, loadSequence = 0, intent = null
  const localDrafts = createStoryboardDraftStore(draftStorage)
  let draftContext = null, applying = false
  const guard = () => {
    const currentEpoch = epoch, account = user.value?.id, session = captureSession()
    return () => currentEpoch === epoch && account === user.value?.id && session()
  }
  function persistDraft() {
    if (applying || !draftContext || String(user.value?.id) !== draftContext.owner || project.value?.id !== draftContext.id || !draft.value) return
    try {
      if (dirty.value || draftConflict.value) {
        localDrafts.save(draftContext.owner, draftContext.id, draftContext.baseRevision, draft.value)
        savedLocally.value = true
      } else {
        localDrafts.remove(draftContext.owner, draftContext.id)
        savedLocally.value = false
      }
      draftStorageError.value = ''
    } catch { savedLocally.value = false; draftStorageError.value = '本机草稿保存不可用；切换页面前请保存到服务器或导出草稿。' }
  }
  function accept(data, restoreLocal = true, localOverride = null) {
    applying = true
    selection += 1; project.value = data; draft.value = clone(data.revision.draft); history.value = null
    draftContext = { owner: String(user.value.id), id: data.id, baseRevision: data.revision.number }
    draftConflict.value = null; draftStorageError.value = ''; savedLocally.value = false
    try {
      if (restoreLocal) {
        const saved = localOverride || localDrafts.read(user.value.id, data.id)
        if (saved && JSON.stringify(saved.draft) !== JSON.stringify(data.revision.draft)) {
          draft.value = clone(saved.draft); savedLocally.value = true; draftContext.baseRevision = saved.baseRevision
          if (saved.baseRevision !== data.revision.number) draftConflict.value = { baseRevision: saved.baseRevision, serverRevision: data.revision.number }
          notice.value = '已恢复本机未提交的分镜草稿，请检查后保存。'
        } else localDrafts.remove(user.value.id, data.id)
      } else localDrafts.remove(user.value.id, data.id)
    } catch (failure) { draftStorageError.value = failure.message || '无法读取或清除本机草稿。' }
    finally { applying = false }
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
      accept(saved, false); notice.value = message
      try { await historyFor() } catch { if (current()) notice.value += '；版本记录暂未加载，可刷新项目' }
    } catch (failure) {
      if (current()) {
        error.value = failure.status === 409 ? `${failure.message}。当前未保存编辑已保留，请对照最新版本后处理。` : failure.message
        if (failure.status === 409 && project.value && draft.value) {
          const id = project.value.id, pending = { baseRevision: draftContext.baseRevision, draft: clone(draft.value) }
          try {
            const latest = await request(`/generation/projects/${id}`)
            if (current()) { accept(latest, true, pending); persistDraft() }
          } catch { if (current()) error.value += ' 最新服务器版本暂未读取成功，请稍后重试。' }
        }
      }
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
    if (project.value.archived) { error.value = '项目已归档，请先恢复项目再编辑'; return }
    if (draftConflict.value) { error.value = '本机草稿与服务器版本不同，请先对照并选择处理方式'; return }
    const id = project.value.id, expectedRevision = project.value.revision.number
    return write(async () => request(`/generation/projects/${id}/revisions`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedRevision, draft: normalizeStoryboardEdit(draft.value) })
    }), '编辑稿已保存为新版本，需重新确认')
  }
  function confirm() {
    if (project.value?.archived) { error.value = '项目已归档，请先恢复项目再确认'; return }
    if (!project.value || dirty.value || draftConflict.value) { error.value = '请先保存当前编辑稿，再确认该版本'; return }
    const id = project.value.id, expectedRevision = project.value.revision.number
    return write(async () => request(`/generation/projects/${id}/confirm`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ expectedRevision }) }), '当前分镜已确认，尚未提交视频生成')
  }
  function restore(revision) { draft.value = clone(revision.draft); notice.value = `已将版本 ${revision.number} 载入编辑区；保存后创建新版本` }
  function applyLocalDraft() {
    if (!project.value || !draftContext || busy.value || loading.value) return
    draftContext.baseRevision = project.value.revision.number; draftConflict.value = null
    persistDraft(); notice.value = '已将本机草稿载入最新版本的编辑区；请对照服务器稿，检查后保存为新版本。'
  }
  async function discardDraft() {
    if (!project.value || busy.value || loading.value) return
    const id = project.value.id
    applying = true
    try { localDrafts.remove(user.value.id, id) }
    catch { draftStorageError.value = '无法清除本机草稿；浏览器可能禁止存储访问。'; return }
    finally { applying = false }
    draftConflict.value = null; draft.value = clone(project.value.revision.draft)
    if (draftContext) draftContext.baseRevision = project.value.revision.number
    savedLocally.value = false
    await select(id)
  }
  function reset() {
    persistDraft(); draftContext = null
    selection += 1; loadSequence += 1; intent?.reset(); project.value = null; draft.value = null; history.value = null; error.value = ''; notice.value = ''; loading.value = false
    draftConflict.value = null; draftStorageError.value = ''; savedLocally.value = false
  }
  watch(draft, persistDraft, { deep: true, flush: 'sync' })
  watch(() => user.value?.id, () => {
    epoch += 1; selection += 1; loadSequence += 1
    draftContext = null; draftConflict.value = null; draftStorageError.value = ''; savedLocally.value = false
    projects.value = []; project.value = null; draft.value = null; history.value = null; error.value = ''; notice.value = ''; busy.value = false; loading.value = false
    intent = user.value ? createSubmissionIntent({ storage, scope: `storyboard:${user.value.id}`, newKey, digest }) : null
    if (user.value) load()
  }, { immediate: true })
  onScopeDispose(() => { persistDraft(); epoch += 1 })
  return { projects, project, draft, history, error, notice, busy, loading, dirty, totalDuration, draftConflict, draftStorageError, savedLocally,
    load, select, create, save, confirm, restore, reset, applyLocalDraft, discardDraft }
}
