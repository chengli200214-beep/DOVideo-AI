import { onScopeDispose, ref, watch } from 'vue'

const queryString = query => new URLSearchParams(Object.entries(query || {}).filter(([, value]) => value != null).sort(([a], [b]) => a.localeCompare(b))).toString()

export function useCursorLibrary({ user, query, path, request, captureSession }) {
  const items = ref([]), nextCursor = ref(null), loading = ref(false), error = ref('')
  let epoch = 0, sequence = 0
  function clear() { epoch++; sequence++; items.value = []; nextCursor.value = null; loading.value = false; error.value = '' }
  async function load(more = false) {
    if (!user.value || (more && (!nextCursor.value || loading.value))) return
    const currentEpoch = epoch, ticket = ++sequence, session = captureSession(), owner = user.value.id, filter = queryString(query.value)
    const selected = () => epoch === currentEpoch && sequence === ticket && owner === user.value?.id && filter === queryString(query.value)
    const current = () => selected() && session()
    const params = new URLSearchParams(filter)
    params.set('limit', '20')
    if (more) {
      const cursor = nextCursor.value
      params.set(cursor.updatedAt !== undefined ? 'beforeUpdatedAt' : 'beforeCreatedAt', cursor.updatedAt ?? cursor.createdAt)
      params.set('beforeId', cursor.id)
    }
    loading.value = true; error.value = ''
    try {
      const page = await request(`${path}?${params}`)
      if (!current()) return
      if (!Array.isArray(page?.items)) throw new Error('列表响应格式异常，请重试')
      items.value = [...new Map([...(more ? items.value : []), ...page.items].map(item => [item.id ?? item.asset.id, item])).values()]
      nextCursor.value = page.nextCursor
    } catch (failure) { if (current()) error.value = failure.message }
    finally {
      if (selected()) {
        loading.value = false
        if (!session()) { items.value = []; nextCursor.value = null; error.value = '' }
      }
    }
  }
  watch([() => user.value?.id, () => queryString(query.value)], () => {
    clear()
    if (user.value) load()
  }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { epoch++; sequence++ })
  return { items, nextCursor, loading, error, load, clear }
}
