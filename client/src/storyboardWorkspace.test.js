import test from 'node:test'
import assert from 'node:assert/strict'
import { effectScope, nextTick, ref } from 'vue'
import { composeShotPrompt, emptyShot, normalizeStoryboardEdit, useStoryboardWorkspace } from './storyboardWorkspace.js'

const flush = async () => { await nextTick(); await Promise.resolve(); await Promise.resolve() }
const pending = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function view(id = 'project', number = 1) {
  return { id, status: 'DRAFT', brief: { title: '产品视频', style: '自然光', desiredDurationSeconds: 10, frameRatio: '9:16' }, updatedAt: 1,
    revision: { number, draft: { script: '产品介绍', shots: [emptyShot(1, '9:16', 'one'), emptyShot(2, '9:16', 'two')] } } }
}
function setup(respond, options = {}) {
  const calls = [], user = ref({ id: 1 }), scope = effectScope(), stored = options.stored || new Map()
  let sequence = 0
  const request = async (path, options) => { calls.push({ path, options }); return respond(path, options) }
  const workspace = scope.run(() => useStoryboardWorkspace({ user, request, captureSession: () => () => true,
    storage: { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key) },
    draftStorage: options.draftStorage,
    newKey: () => `key-${++sequence}`, digest: async value => value }))
  return { ...workspace, calls, user, stored, close: () => scope.stop() }
}
const history = { revisions: [], attempts: [], confirmations: [] }

test('normalizes editor-only values while keeping editorial duration out of model parameters', () => {
  const input = view().revision.draft
  input.shots[0].parameters.seed = ''
  input.shots[0].sequence = 3
  const normalized = normalizeStoryboardEdit(input)
  assert.equal(normalized.shots[0].sequence, 1)
  assert.equal(normalized.shots[0].parameters.seed, null)
  assert.equal(normalized.shots[0].desiredDurationSeconds, 5)
  assert.equal('duration' in normalized.shots[0].parameters, false)
  assert.equal(input.shots[0].sequence, 3)
  input.shots[0].parameters.seed = '1.2'
  assert.throws(() => normalizeStoryboardEdit(input), /非负整数/)
  assert.equal(composeShotPrompt({ subject: ' 鞋子 ', action: '缓慢旋转', camera: '推进' }, '自然光'), '鞋子，缓慢旋转，推进，自然光')
})

test('unsaved storyboard edits survive disposal and restore only under their account and project', async () => {
  const stored = new Map(), respond = path => path === '/generation/projects' ? [] : path.endsWith('/revisions') ? history : view(path.split('/').at(-1))
  const first = setup(respond, { stored })
  await flush(); await first.select('project'); first.draft.value.script = '本机未提交脚本'; first.draft.value.shots[0].caption = '未提交字幕'
  assert.equal(first.savedLocally.value, true); first.close()
  const second = setup(respond, { stored })
  try {
    await flush(); await second.select('project')
    assert.equal(second.draft.value.script, '本机未提交脚本'); assert.equal(second.draft.value.shots[0].caption, '未提交字幕')
    await second.select('another'); assert.equal(second.draft.value.script, '产品介绍')
    second.user.value = { id: 2 }; await flush(); await second.select('project')
    assert.equal(second.draft.value.script, '产品介绍')
    second.user.value = { id: 1 }; await flush(); await second.select('project')
    assert.equal(second.draft.value.script, '本机未提交脚本')
  } finally { second.close() }
})

test('a newer server revision preserves the local draft and requires explicit conflict resolution', async () => {
  const stored = new Map()
  const first = setup(path => path === '/generation/projects' ? [] : path.endsWith('/revisions') ? history : view(), { stored })
  await flush(); await first.select('project'); first.draft.value.script = '本机脚本'; first.close()
  let body
  const second = setup((path, options) => {
    if (path === '/generation/projects') return []
    if (options) { body = JSON.parse(options.body); return { ...view('project', 3), revision: { number: 3, draft: body.draft } } }
    return path.endsWith('/revisions') ? history : view('project', 2)
  }, { stored })
  try {
    await flush(); await second.select('project')
    assert.deepEqual(second.draftConflict.value, { baseRevision: 1, serverRevision: 2 })
    await second.save(); assert.equal(body, undefined)
    second.applyLocalDraft(); await second.save()
    assert.equal(body.expectedRevision, 2); assert.equal(body.draft.script, '本机脚本')
    assert.equal(second.dirty.value, false); assert.equal(stored.has('storyboard-draft:1:project'), false)
  } finally { second.close() }
})

test('explicit discard removes local edits and blocked browser storage leaves a visible recoverable error', async () => {
  const respond = path => path === '/generation/projects' ? [] : path.endsWith('/revisions') ? history : view()
  const workspace = setup(respond)
  try {
    await flush(); await workspace.select('project'); workspace.draft.value.script = '丢弃内容'; await workspace.discardDraft()
    assert.equal(workspace.draft.value.script, '产品介绍'); assert.equal(workspace.stored.has('storyboard-draft:1:project'), false)
  } finally { workspace.close() }
  const blocked = setup(respond, { draftStorage: { getItem() { throw new Error('Storage blocked') }, setItem() { throw new Error('Quota exceeded') }, removeItem() { throw new Error('Storage blocked') } } })
  try {
    await flush(); await blocked.select('project'); blocked.draft.value.script = '仍能编辑'
    assert.equal(blocked.dirty.value, true); assert.equal(blocked.savedLocally.value, false); assert.match(blocked.draftStorageError.value, /不可用/)
  } finally { blocked.close() }
})

test('a lost creation response reuses its key, and confirmation never submits a video task', async () => {
  let createCount = 0
  const workspace = setup((path, options) => {
    if (path === '/generation/projects' && !options) return []
    if (path.endsWith('/revisions')) return history
    if (path.endsWith('/confirm')) return { ...view(), status: 'CONFIRMED' }
    if (options?.method === 'POST') { if (++createCount === 1) throw new Error('network interrupted'); return { project: view() } }
    throw new Error(`Unexpected path: ${path}`)
  })
  try {
    await flush()
    const brief = { title: 'same', goal: 'demo' }
    await workspace.create(brief)
    assert.match(workspace.error.value, /network/)
    await workspace.create(brief)
    const creates = workspace.calls.filter(item => item.path === '/generation/projects' && item.options)
    assert.equal(creates[0].options.headers['Idempotency-Key'], creates[1].options.headers['Idempotency-Key'])
    await workspace.confirm()
    assert.equal(workspace.project.value.status, 'CONFIRMED')
    assert.equal(workspace.calls.some(item => item.path.startsWith('/generation/tasks')), false)
  } finally { workspace.close() }
})

test('saving a conflict preserves the unsaved draft and blocks confirmation until saved', async () => {
  const workspace = setup((path, options) => {
    if (path === '/generation/projects') return []
    if (options?.method === 'POST') throw Object.assign(new Error('版本已变化'), { status: 409 })
    return path.endsWith('/revisions') ? history : view()
  })
  try {
    await flush(); await workspace.select('project')
    workspace.draft.value.script = '未保存脚本'
    await workspace.save()
    assert.equal(workspace.draft.value.script, '未保存脚本')
    assert.match(workspace.error.value, /已保留/)
    const calls = workspace.calls.length
    workspace.confirm()
    assert.equal(workspace.calls.length, calls)
    assert.match(workspace.error.value, /先保存/)
  } finally { workspace.close() }
})

test('late account responses cannot restore a previous users project', async () => {
  const response = pending()
  const workspace = setup((path, options) => path === '/generation/projects' && !options ? [] : path.endsWith('/revisions') ? history : response.promise)
  try {
    await flush(); const selecting = workspace.select('private')
    workspace.user.value = { id: 2 }; await flush()
    response.resolve(view('private'))
    await selecting
    assert.equal(workspace.project.value, null)
    assert.equal(workspace.draft.value, null)
    assert.equal(workspace.busy.value, false)
  } finally { workspace.close() }
})

test('late project reads cannot overwrite a newer selected project or its draft', async () => {
  const response = pending()
  const workspace = setup(path => path === '/generation/projects' ? [] : path.endsWith('/revisions') ? history : path.endsWith('/old') ? response.promise : view('new'))
  try {
    await flush(); const first = workspace.select('old')
    await workspace.select('new')
    workspace.draft.value.script = '新项目编辑中'
    response.resolve(view('old')); await first
    assert.equal(workspace.project.value.id, 'new')
    assert.equal(workspace.draft.value.script, '新项目编辑中')
    assert.equal(workspace.loading.value, false)
  } finally { workspace.close() }
})

test('loading an old revision edits a copy while retaining the current save parent', async () => {
  let savedBody
  const workspace = setup((path, options) => {
    if (path === '/generation/projects') return []
    if (options?.method === 'POST') { savedBody = JSON.parse(options.body); return view('project', 4) }
    return path.endsWith('/revisions') ? history : view('project', 3)
  })
  try {
    await flush(); await workspace.select('project')
    const old = { number: 1, draft: view().revision.draft }
    old.draft.script = '旧脚本'
    workspace.restore(old)
    workspace.draft.value.shots[0].subject = '新修改'
    assert.equal(old.draft.shots[0].subject, '')
    await workspace.save()
    assert.equal(savedBody.expectedRevision, 3)
    assert.equal(workspace.project.value.revision.number, 4)
  } finally { workspace.close() }
})

test('initial recent history cannot replace an explicitly selected project while it loads', async () => {
  const recent = pending(), chosen = pending()
  const workspace = setup(path => path === '/generation/projects' ? recent.promise : path.endsWith('/revisions') ? history : chosen.promise)
  try {
    const selecting = workspace.select('chosen')
    recent.resolve([{ id: 'old', title: 'old' }]); await flush()
    assert.equal(workspace.calls.some(item => item.path.endsWith('/old')), false)
    assert.equal(workspace.loading.value, true)
    chosen.resolve(view('chosen')); await selecting
    assert.equal(workspace.project.value.id, 'chosen')
    assert.equal(workspace.loading.value, false)
  } finally { workspace.close() }
})
