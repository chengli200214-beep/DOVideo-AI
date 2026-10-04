import test from 'node:test'
import assert from 'node:assert/strict'
import { collectDraftAssetIds, createStoryboardDraftStore, draftKey } from './storyboardDrafts.js'

function memory() {
  const values = new Map()
  return { values, keys: () => [...values.keys()], getItem: key => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
}
const draft = ids => ({ script: 'draft', shots: ids.map(referenceAssetId => ({ referenceAssetId })) })
test('asset cleanup protects all account drafts including projects that are not currently open', () => {
  const storage = memory(), store = createStoryboardDraftStore(storage)
  store.save(1, 'closed-project', 2, draft(['private-a', 'shared']))
  store.save(1, 'current-project', 4, draft(['private-b', 'shared', null]))
  store.save(2, 'another-account', 1, draft(['other-private']))
  assert.deepEqual(collectDraftAssetIds(storage, 1).sort(), ['private-a', 'private-b', 'shared'])
  store.remove(1, 'closed-project')
  assert.deepEqual(collectDraftAssetIds(storage, 1).sort(), ['private-b', 'shared'])
  assert.deepEqual(collectDraftAssetIds(storage, 2), ['other-private'])
})
test('unreadable or malformed own drafts fail closed while another account data is never read', () => {
  const storage = memory()
  storage.setItem(draftKey(2, 'foreign'), '{broken json')
  assert.deepEqual(collectDraftAssetIds(storage, 1), [])
  storage.setItem(draftKey(1, 'own'), '{broken json')
  assert.throws(() => collectDraftAssetIds(storage, 1))
  assert.throws(() => collectDraftAssetIds({ keys() { throw new Error('Storage blocked') } }, 1), /Storage blocked/)
  assert.throws(() => collectDraftAssetIds({ keys: () => [draftKey(1, 'own')], getItem() { throw new Error('Read blocked') } }, 1), /Read blocked/)
})
