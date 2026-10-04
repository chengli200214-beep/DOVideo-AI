<template>
  <section class="generation-workspace" aria-labelledby="generation-heading">
    <div class="generation-heading">
      <div><p class="eyebrow">AIGC VIDEO</p><h1 id="generation-heading">从一个画面开始创作</h1>
        <p>输入文字或参考图片，生成视频并保存到你的素材库。</p></div>
      <span class="provider-tag" v-if="capability">{{ capability.provider === 'mock' ? '本地演示 · 不产生模型费用' : capability.model }}</span>
    </div>
    <div v-if="!user" class="empty-state"><p>登录后即可创建生成任务、查看视频和任务记录。</p>
      <button @click="$emit('login')">登录 / 注册</button></div>
    <template v-else>
      <p v-if="error" class="generation-error" role="alert">{{ error }}</p>
      <div class="generation-grid">
        <form class="creation-form" @submit.prevent="submit">
          <div class="form-heading"><h2>创作输入</h2><button type="button" class="secondary" :disabled="busy" @click="load">刷新配置</button></div>
          <label>生成模式<select v-model="form.kind" :disabled="busy">
            <option v-for="item in capabilities.filter(item => item.enabled)" :key="item.kind" :value="item.kind">
              {{ item.kind === 'TEXT_TO_VIDEO' ? '文生视频' : '图生视频' }} · {{ item.model }}
            </option></select></label>
          <p v-if="capability && !capability.available" class="generation-notice">当前模型调用尚未开放，需要先完成模型与预算授权。</p>
          <label>画面描述<textarea v-model="form.prompt" :maxlength="capability?.maxPromptLength || 2000" rows="5"
            placeholder="例如：桌上的白色运动鞋，镜头缓慢推进，柔和自然光，简洁产品展示" :disabled="busy"></textarea></label>
          <div class="form-row"><label>画面尺寸<select v-model="form.imageSize" :disabled="busy">
            <option v-for="size in capability?.sizes || []" :key="size">{{ size }}</option></select></label>
            <label v-if="capability?.seed">随机种子（可选）<input v-model="form.seed" inputmode="numeric" placeholder="留空随机" :disabled="busy"></label></div>
          <label v-if="capability?.negativePrompt">避免出现的内容（可选）<input v-model="form.negativePrompt" maxlength="2000" placeholder="模糊、畸变…" :disabled="busy"></label>
          <label v-if="capability?.referenceImageRequired">参考图片<input type="file" accept="image/png,image/jpeg" :disabled="busy" @change="upload">
            <span class="field-help">PNG / JPEG，最大 5 MiB，边长最大 4096。图片会先保存为私有素材。</span>
            <img v-if="preview" :src="preview" alt="参考图片" class="reference-preview">
            <span v-if="asset" class="field-help">已保存 · {{ asset.width }} × {{ asset.height }} · {{ Math.ceil(asset.size / 1024) }} KB</span></label>
          <button class="submit-button" :disabled="busy || !capability?.available">{{ busy ? '处理中…' : '提交生成任务' }}</button>
          <p class="field-help">网络中断后，保持输入不变并再次提交会查询同一次任务。</p>
          <button type="button" class="secondary" :disabled="busy || generationIsActive(task?.state)" @click="newCreation">开始下一次创作</button>
        </form>
        <div class="generation-results">
          <div class="form-heading"><h2>任务与视频</h2><button class="secondary" :disabled="refreshing" @click="refresh">刷新任务</button></div>
          <div v-if="!task" class="empty-state">创建任务后，视频和处理状态会显示在这里。</div>
          <template v-else>
            <div class="task-summary"><strong>{{ generationStates[task.state] || task.state }}</strong>
              <span>{{ task.model }}</span><code>{{ task.id }}</code>
              <p v-if="task.errorCode" class="generation-error">{{ errorLabels[task.errorCode] || task.errorCode }}</p>
              <p v-if="task.state === 'SUBMISSION_UNKNOWN'" class="generation-notice">提交结果未知，请核对模型平台记录；系统不会自动重复生成。</p>
            </div>
            <video v-if="videoUrl" :src="videoUrl" controls preload="metadata" class="generated-video"></video>
            <div v-if="task.state === 'SUCCEEDED'" class="result-actions"><a v-if="videoUrl" :href="videoUrl" target="_blank" rel="noopener">打开视频</a>
              <button class="secondary" @click="refresh">刷新视频链接</button><span>{{ Math.ceil(task.artifactSize / 1024) }} KB · 已归档</span></div>
            <p v-if="task.recoveryAvailable === false && (task.state === 'SUBMISSION_UNKNOWN' || task.recoverable)" class="generation-notice">原任务恢复尚未开放，请先启用查询与归档恢复；无需重新生成。</p>
            <button v-if="task.state === 'FAILED' && task.recoverable" class="secondary" :disabled="busy || task.recoveryAvailable === false" @click="retry">恢复查询 / 保存</button>
            <div v-if="task.state === 'SUBMISSION_UNKNOWN'" class="reconcile"><label>经模型平台核对的任务 ID<input v-model="remoteId" placeholder="requestId"></label>
              <button class="secondary" :disabled="busy || !remoteId || task.recoveryAvailable === false" @click="reconcile">补录并继续查询</button></div>
            <details v-if="trace" class="task-trace"><summary>输入与处理记录</summary>
              <p>原始描述：{{ trace.originalPrompt }}</p>
              <ol><li v-for="event in trace.events" :key="event.id"><time>{{ new Date(event.occurredAt).toLocaleString() }}</time>
                {{ generationStates[event.state] || event.state }} <span v-if="event.errorCode">· {{ event.errorCode }}</span></li></ol>
              <p v-if="task.artifactSha256">产物 SHA-256</p><code class="checksum">{{ task.artifactSha256 }}</code>
              <pre>{{ JSON.stringify(trace.effectiveSubmission, null, 2) }}</pre>
            </details>
          </template>
          <div class="recent-tasks"><h3>最近任务</h3><ul><li v-for="item in tasks" :key="item.id">
            <button :class="{ selected: task?.id === item.id }" :disabled="busy" @click="select(item)"><span>{{ generationStates[item.state] }}</span>
              <span>{{ new Date(item.createdAt).toLocaleString() }}</span><code>{{ item.id.slice(0, 8) }}</code></button></li></ul></div>
        </div>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { apiRequest, captureAuthSession } from './api'
import { browserStorage, buildGenerationRequest, createSubmissionIntent, generationIsActive, generationStates } from './generationWorkspace'
import { createReadPolling } from './readPolling'

const props = defineProps({ user: Object })
defineEmits(['login'])
const capabilities = ref([]), tasks = ref([]), task = ref(null), trace = ref(null)
const asset = ref(null), preview = ref(''), videoUrl = ref(''), error = ref(''), remoteId = ref('')
const busy = ref(false), refreshing = ref(false)
const form = reactive({ kind: 'TEXT_TO_VIDEO', prompt: '', negativePrompt: '', imageSize: '', seed: '' })
const capability = computed(() => capabilities.value.find(item => item.kind === form.kind))
const errorLabels = {
  SUBMISSION_AUTHORIZATION_DENIED: '调用授权无效或任务数 / 预算额度已用尽，模型尚未调用',
  SUBMISSION_PREPARATION_FAILED: '提交前准备失败，请检查素材和调用额度', SUBMISSION_REJECTED: '模型平台拒绝了请求',
  SUBMISSION_UNKNOWN: '模型提交结果未知', MODEL_FAILED: '模型生成失败', POLL_TIMEOUT: '状态查询超时',
  ARTIFACT_SAVE_FAILED: '视频归档失败，可恢复查询和保存', STATUS_QUERY_FAILED: '模型状态查询失败'
}
let epoch = 0, selection = 0, intent = null, retryLoad = false
const polling = createReadPolling({ refresh: () => retryLoad || !task.value ? load() : refresh(), active: () => generationIsActive(task.value?.state), allowed: () => !!props.user, interval: 3000 })
function currentGuard() {
  const version = epoch, auth = captureAuthSession()
  return () => version === epoch && auth()
}
async function request(path, options) {
  const response = await apiRequest(path, options)
    if (!response.ok) throw Object.assign(new Error(await response.text() || '请求失败'), { status: response.status })
  return response.json()
}
async function load() {
  if (!props.user) return
  const current = currentGuard()
  polling.stop()
  error.value = ''
  try {
    const [caps, recent] = await Promise.all([request('/generation/capabilities'), request('/generation/tasks')])
    if (!current()) return
    retryLoad = false
    capabilities.value = caps; tasks.value = recent
    if (!caps.find(item => item.kind === form.kind && item.enabled)) form.kind = caps.find(item => item.enabled)?.kind || ''
    if (!task.value && recent.length) await select(recent[0])
    else polling.succeeded()
  } catch (failure) { if (current()) { retryLoad = true; error.value = `${failure.message}；${polling.failed(failure) ? '将自动重试查询' : '请手动刷新'}` } }
}
async function upload(event) {
  const file = event.target.files?.[0]
  if (!file) return
  const current = currentGuard()
  busy.value = true; error.value = ''; asset.value = null; preview.value = ''
  try {
    if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > capability.value.maxImageBytes) throw new Error('请选择 5 MiB 以内的 PNG / JPEG 图片')
    const data = new FormData(); data.append('file', file)
    const saved = await request('/generation/assets', { method: 'POST', body: data })
    if (!current()) return
    asset.value = saved
    const link = await request(`/generation/assets/${saved.id}/preview`)
    if (current()) preview.value = link
  } catch (failure) { if (current()) error.value = failure.message }
  finally { if (current()) busy.value = false }
}
async function submit() {
  if (busy.value) return
  const current = currentGuard()
  busy.value = true; error.value = ''
  try {
    const input = buildGenerationRequest(form, capability.value, asset.value)
    const key = await intent.keyFor(input)
    if (!current()) return
    const result = await request('/generation/tasks', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify(input) })
    if (!current()) return
    await select(result.task)
    await load()
  } catch (failure) { if (current()) error.value = failure.message }
  finally { if (current()) busy.value = false }
}
async function select(item) {
  selection += 1; refreshing.value = false; task.value = item; videoUrl.value = ''; trace.value = null; remoteId.value = ''; error.value = ''
  await refresh()
}
async function refresh() {
  if (!task.value || refreshing.value) return
  const id = task.value.id, selected = selection, current = currentGuard()
  const valid = () => current() && selected === selection
  refreshing.value = true
  polling.stop()
  try {
    const [updated, history] = await Promise.all([request(`/generation/tasks/${id}`), request(`/generation/tasks/${id}/trace`)])
    if (!valid()) return
    task.value = updated; trace.value = history
    error.value = ''
    tasks.value = tasks.value.map(item => item.id === id ? updated : item)
    if (updated.state === 'SUCCEEDED') {
      const url = await request(`/generation/tasks/${id}/artifact`)
      if (valid()) videoUrl.value = url
    }
    if (valid()) polling.succeeded()
  } catch (failure) { if (valid()) error.value = `${failure.message}；${polling.failed(failure) ? '将自动重试查询' : '请手动刷新'}` }
  finally { if (valid()) refreshing.value = false }
}
async function recover(path, body = {}) {
  if (busy.value) return
  const current = currentGuard(), selected = selection
  busy.value = true; error.value = ''
  try {
    const updated = await request(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    if (current() && selected === selection) { task.value = updated; await refresh() }
  } catch (failure) { if (current() && selected === selection) error.value = failure.message }
  finally { if (current()) busy.value = false }
}
function retry() { return recover(`/generation/tasks/${task.value.id}/retry`) }
function reconcile() { return recover(`/generation/tasks/${task.value.id}/reconcile`, { requestId: remoteId.value }) }
function newCreation() { polling.reset(); intent?.reset(); selection += 1; task.value = null; videoUrl.value = ''; trace.value = null; error.value = '' }
watch(capability, value => { if (value && !value.sizes.includes(form.imageSize)) form.imageSize = value.sizes[0] || '' })
watch(() => props.user?.id, () => {
  epoch += 1; selection += 1; retryLoad = false; polling.reset()
  capabilities.value = []; tasks.value = []; task.value = null; trace.value = null; asset.value = null
  preview.value = ''; videoUrl.value = ''; error.value = ''; busy.value = false; refreshing.value = false
  form.prompt = ''; form.negativePrompt = ''; form.seed = ''; remoteId.value = ''
  intent = props.user ? createSubmissionIntent({ storage: browserStorage('sessionStorage'), scope: props.user.id }) : null
  if (props.user) {
    load()
  }
}, { immediate: true })
onUnmounted(() => { epoch += 1; polling.dispose() })
</script>

<style scoped>
.generation-workspace { width: min(1180px, 100%); margin: 0 auto; padding: 32px 0 64px; color: #eceef2; }
.generation-heading { display:flex; justify-content:space-between; align-items:center; gap:24px; margin-bottom:32px; }
.eyebrow { color:#b1ff86; font-size:12px; letter-spacing:.18em; margin-bottom:10px; }
h1 { font-size:clamp(24px, 3vw, 36px); margin:0 0 12px; } h2 { font-size:18px; margin:0; } h3 { font-size:14px; }
.generation-heading p:not(.eyebrow), .field-help { color:#a8adb9; font-size:13px; line-height:1.6; }
.provider-tag { border:1px solid #455c3a; border-radius:8px; padding:10px 14px; font-size:12px; color:#b1ff86; }
.generation-grid { display:grid; grid-template-columns:minmax(300px, 420px) minmax(0, 1fr); gap:24px; }
.creation-form, .generation-results { background:#15181e; border:1px solid #303540; border-radius:16px; padding:24px; }
.creation-form { display:flex; flex-direction:column; gap:18px; align-self:start; }
.form-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:8px; }
label { display:flex; flex-direction:column; gap:8px; font-size:13px; line-height:1.5; }
input, select, textarea { box-sizing:border-box; width:100%; color:#eceef2; background:#0e1116; border:1px solid #3a404d; border-radius:8px; padding:11px; font:inherit; }
textarea { resize:vertical; } input:focus, select:focus, textarea:focus, button:focus-visible { outline:2px solid #b1ff86; outline-offset:2px; }
.form-row { display:grid; grid-template-columns:1fr 1fr; gap:14px; }
button { cursor:pointer; border:0; border-radius:8px; padding:11px 16px; font:inherit; font-size:13px; background:#b1ff86; color:#102008; }
button:disabled { opacity:.45; cursor:default; } .secondary { background:#242a34; border:1px solid #404755; color:#d5d9e2; }
.submit-button { font-weight:600; } .generation-error { color:#ffb3a8; font-size:13px; line-height:1.6; }
.generation-notice { font-size:13px; line-height:1.6; color:#ead3a2; background:#302719; padding:12px; border-radius:8px; }
.empty-state { padding:48px 20px; text-align:center; color:#a8adb9; font-size:14px; line-height:1.8; }
.reference-preview { display:block; max-height:180px; object-fit:contain; background:#0e1116; border-radius:8px; }
.task-summary { display:flex; flex-direction:column; gap:8px; margin:20px 0; font-size:13px; }
.task-summary strong { color:#b1ff86; font-size:16px; } code { font-size:12px; color:#a8adb9; overflow-wrap:anywhere; }
.generated-video { width:100%; max-height:380px; background:#090b0f; border-radius:10px; }
.result-actions { display:flex; flex-wrap:wrap; gap:14px; align-items:center; font-size:12px; margin:16px 0; }
a { color:#b1ff86; } .reconcile { display:flex; flex-direction:column; gap:12px; }
.task-trace { border-top:1px solid #303540; margin-top:24px; padding-top:18px; font-size:12px; color:#b4bac7; }
summary { cursor:pointer; font-size:13px; } ol { padding-left:20px; } li { margin:8px 0; line-height:1.6; } time { margin-right:12px; }
pre { padding:14px; background:#0e1116; border-radius:8px; white-space:pre-wrap; overflow-wrap:anywhere; font-size:11px; }
.recent-tasks { border-top:1px solid #303540; margin-top:24px; padding-top:16px; } .recent-tasks ul { list-style:none; padding:0; }
.recent-tasks button { display:flex; justify-content:space-between; gap:8px; width:100%; background:#1c212a; color:#bfc5d1; text-align:left; }
.recent-tasks .selected { outline:1px solid #6f9656; }
@media (max-width:800px) { .generation-grid { grid-template-columns:1fr; } .generation-heading { align-items:flex-start; flex-direction:column; } .creation-form,.generation-results { padding:18px; } }
@media (prefers-reduced-motion:reduce) { * { scroll-behavior:auto; } }
</style>
