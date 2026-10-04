export const generationStates = {
  QUEUED: '等待提交', SUBMITTING: '正在提交', SUBMISSION_UNKNOWN: '提交结果待核对',
  RUNNING: '模型处理中', SAVING: '正在归档视频', SUCCEEDED: '已完成', FAILED: '失败'
}

export function generationIsActive(state) {
  return ['QUEUED', 'SUBMITTING', 'RUNNING', 'SAVING'].includes(state)
}

// Some browsers throw even when obtaining the storage object (privacy settings).
export function browserStorage(name) {
  return {
    getItem(key) { return globalThis[name].getItem(key) },
    setItem(key, value) { return globalThis[name].setItem(key, value) },
    removeItem(key) { return globalThis[name].removeItem(key) },
    keys() { const storage = globalThis[name]; return Array.from({ length: storage.length }, (_, index) => storage.key(index)).filter(Boolean) }
  }
}

export function buildGenerationRequest(form, capability, asset) {
  if (!capability?.available) throw new Error('当前模型尚未开放调用，请先检查服务配置与调用授权')
  const prompt = form.prompt.trim()
  if (!prompt || form.prompt.length > capability.maxPromptLength) throw new Error('请填写符合长度限制的提示词')
  if (!capability.sizes.includes(form.imageSize)) throw new Error('所选尺寸不受当前模型支持')
  const request = { kind: capability.kind, prompt: form.prompt, imageSize: form.imageSize }
  if (capability.negativePrompt && form.negativePrompt.trim()) request.negativePrompt = form.negativePrompt.trim()
  if (capability.seed && form.seed !== '') {
    const seed = Number(form.seed)
    if (!Number.isSafeInteger(seed) || seed < 0) throw new Error('种子需为非负整数')
    request.seed = seed
  }
  if (capability.referenceImageRequired) {
    if (!asset?.id) throw new Error('请先上传参考图片')
    request.referenceImageId = asset.id
  }
  return request
}

/** Persist only the payload digest and key, scoped to the logged-in account. A lost response is retryable. */
export function createSubmissionIntent({ storage, scope, newKey = () => crypto.randomUUID(),
  digest = async value => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))))
    .map(value => value.toString(16).padStart(2, '0')).join('') }) {
  const storageKey = `generation-intent:${scope}`
  let memory = null
  try { memory = JSON.parse(storage.getItem(storageKey) || 'null') } catch { /* browser storage may be unavailable */ }
  return {
    async keyFor(request) {
      const fingerprint = await digest(JSON.stringify(request))
      if (memory?.fingerprint === fingerprint && memory.key) return memory.key
      memory = { fingerprint, key: newKey() }
      try { storage.setItem(storageKey, JSON.stringify(memory)) } catch { /* in-memory retry still works */ }
      return memory.key
    },
    reset() {
      memory = null
      try { storage.removeItem(storageKey) } catch { /* no storage */ }
    }
  }
}
