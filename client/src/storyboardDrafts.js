const copy = value => JSON.parse(JSON.stringify(value))
export const draftKey = (owner, projectId) => `storyboard-draft:${owner}:${projectId}`

/** Local drafts may reference images that have not been saved into a server revision yet. */
export function collectDraftAssetIds(storage, owner) {
  const prefix = `storyboard-draft:${owner}:`, result = new Set(), drafts = createStoryboardDraftStore(storage)
  if (!storage.keys) throw new Error('无法列出本机草稿')
  for (const key of storage.keys()) {
    if (!key.startsWith(prefix)) continue
    const saved = drafts.read(owner, key.slice(prefix.length))
    for (const shot of saved?.draft.shots || []) if (shot.referenceAssetId) {
      if (typeof shot.referenceAssetId !== 'string') throw new Error('本机草稿素材引用格式异常')
      result.add(shot.referenceAssetId)
    }
  }
  return [...result]
}

/** Only editable storyboard data is stored, scoped to the authenticated account and project. */
export function createStoryboardDraftStore(storage) {
  return {
    read(owner, projectId) {
      const raw = storage.getItem(draftKey(owner, projectId))
      if (!raw) return null
      const saved = JSON.parse(raw)
      if (String(saved.owner) !== String(owner) || saved.projectId !== projectId || !Number.isInteger(saved.baseRevision)
        || !saved.draft || typeof saved.draft.script !== 'string' || !Array.isArray(saved.draft.shots)) {
        throw new Error('本机草稿格式异常，请导出当前内容或明确丢弃后再编辑')
      }
      return copy(saved)
    },
    save(owner, projectId, baseRevision, draft) {
      storage.setItem(draftKey(owner, projectId), JSON.stringify({ owner: String(owner), projectId, baseRevision, draft, savedAt: new Date().toISOString() }))
    },
    remove(owner, projectId) { storage.removeItem(draftKey(owner, projectId)) }
  }
}
