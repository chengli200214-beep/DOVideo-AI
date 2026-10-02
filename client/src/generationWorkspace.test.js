import test from 'node:test'
import assert from 'node:assert/strict'
import { buildGenerationRequest, createSubmissionIntent, generationIsActive } from './generationWorkspace.js'

const form = { prompt: ' 产品展示 ', imageSize: '1280x720', negativePrompt: 'blur', seed: '42' }
const cap = { kind: 'TEXT_TO_VIDEO', available: true, sizes: ['1280x720'], maxPromptLength: 100, seed: true, negativePrompt: true }
test('builds only supported parameters and requires an archived image for image-to-video', () => {
  assert.deepEqual(buildGenerationRequest(form, cap, null), { kind: cap.kind, prompt: form.prompt, imageSize: form.imageSize, negativePrompt: 'blur', seed: 42 })
  const imageCap = { ...cap, kind: 'IMAGE_TO_VIDEO', referenceImageRequired: true, seed: false, negativePrompt: false }
  assert.throws(() => buildGenerationRequest(form, imageCap, null), /参考图片/)
  assert.deepEqual(buildGenerationRequest(form, imageCap, { id: 'asset' }), { kind: imageCap.kind, prompt: form.prompt, imageSize: form.imageSize, referenceImageId: 'asset' })
  assert.throws(() => buildGenerationRequest(form, { ...cap, available: false }), /授权/)
  assert.throws(() => buildGenerationRequest({ ...form, imageSize: 'invalid' }, cap), /尺寸/)
  for (const seed of ['-1', '1.5', 'abc', '9007199254740992']) assert.throws(() => buildGenerationRequest({ ...form, seed }, cap), /种子/)
})

test('a lost response and a page reload reuse the account-specific idempotency key', async () => {
  const items = new Map(), storage = { getItem: key => items.get(key), setItem: (key, value) => items.set(key, value), removeItem: key => items.delete(key) }
  let sequence = 0
  const options = { storage, scope: 'owner', newKey: () => `key-${++sequence}`, digest: async value => value }
  const input = { prompt: 'same', kind: 'TEXT_TO_VIDEO' }
  const first = createSubmissionIntent(options)
  assert.equal(await first.keyFor(input), 'key-1')
  assert.equal(await first.keyFor(input), 'key-1')
  assert.equal(await createSubmissionIntent(options).keyFor(input), 'key-1')
  assert.equal(await createSubmissionIntent({ ...options, scope: 'other' }).keyFor(input), 'key-2')
  assert.equal(await first.keyFor({ ...input, prompt: 'changed' }), 'key-3')
  first.reset()
  assert.equal(await first.keyFor(input), 'key-4')
})

test('terminal and uncertain submission states stop automatic polling', () => {
  assert.equal(generationIsActive('SAVING'), true)
  assert.equal(generationIsActive('RUNNING'), true)
  for (const state of ['SUCCEEDED', 'FAILED', 'SUBMISSION_UNKNOWN', undefined]) assert.equal(generationIsActive(state), false)
})
