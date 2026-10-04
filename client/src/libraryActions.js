import { onScopeDispose, ref, watch } from 'vue'

/** A stale response must not publish data, but must still release its own busy state. */
export function useLibraryAction({ user, captureSession, onInvalidate = () => {}, onBusy = () => {} }) {
  const busy = ref(false), error = ref('')
  let epoch = 0
  function invalidate(reason) { epoch++; busy.value = false; error.value = ''; onBusy(false); onInvalidate(reason) }
  async function run(work) {
    if (busy.value || !user.value) return
    const ticket = epoch, owner = user.value.id, session = captureSession()
    const selected = () => ticket === epoch && owner === user.value?.id
    const current = () => selected() && session()
    busy.value = true; error.value = ''; onBusy(true)
    try { return await work(current) }
    catch (failure) { if (current()) error.value = failure.message }
    finally {
      if (selected()) {
        busy.value = false; onBusy(false)
        if (!session()) { onInvalidate('session'); error.value = '登录会话已变化，请重新载入列表。' }
      }
    }
  }
  watch(() => user.value?.id, () => invalidate('account'), { flush: 'sync' })
  onScopeDispose(() => invalidate('dispose'))
  return { busy, error, run }
}

export function projectLibraryActionAllowed({ blocked, busy, dirty }, item) {
  return !blocked && !busy && (!dirty || item.archived)
}

// The selected editor and its local draft stay intact when the archive flag changes.
export function applyProjectArchiveState(project, change) {
  return project?.id === change.id ? { ...project, archived: change.archived } : project
}
