<template>
  <section class="storyboard-workspace" aria-labelledby="storyboard-heading">
    <div class="intro"><p class="eyebrow">CREATIVE STORYBOARD</p><h1 id="storyboard-heading">先把故事分成镜头</h1>
      <p>根据产品与卖点起草分镜，逐镜头编辑并确认，为后续视频生成做好准备。</p><span class="template-badge">本地模板草稿 · 不产生模型费用</span></div>
    <div v-if="!user" class="empty"><p>登录后创建项目、保存分镜版本。</p><button @click="$emit('login')">登录 / 注册</button></div>
    <template v-else>
      <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
      <div class="storyboard-grid">
        <aside>
          <form @submit.prevent="createProject" class="brief-form">
            <h2>创作需求</h2>
            <label>项目名称<input v-model="brief.title" maxlength="120" required :disabled="busy"></label>
            <label>产品名称<input v-model="brief.productName" maxlength="80" required :disabled="busy"></label>
            <label>视频目标<textarea v-model="brief.goal" maxlength="1000" rows="3" required :disabled="busy"></textarea></label>
            <label>产品卖点（每行一条）<textarea v-model="sellingPoints" rows="3" :disabled="busy"></textarea></label>
            <label>画面风格<input v-model="brief.style" maxlength="120" required :disabled="busy"></label>
            <div class="row"><label>期望总时长（秒）<input v-model.number="brief.desiredDurationSeconds" type="number" min="4" max="120" required :disabled="busy"></label>
              <label>草稿镜头数<input v-model.number="brief.shotCount" type="number" min="2" max="8" required :disabled="busy"></label></div>
            <label>画幅<select v-model="brief.frameRatio" :disabled="busy"><option>16:9</option><option>9:16</option><option>1:1</option></select></label>
            <label>参考产品图片（可选）<input type="file" accept="image/png,image/jpeg" :disabled="busy || uploading" @change="uploadReference">
              <span class="help">PNG / JPEG，最大 5 MiB，沿用私有素材存储。</span></label>
            <div v-if="brief.referenceAssetId" class="asset-saved"><span>参考图已保存</span><button type="button" class="secondary" @click="brief.referenceAssetId = null">移除引用</button></div>
            <button :disabled="busy || uploading || loading || dirty">{{ busy ? '保存中…' : '创建模板分镜' }}</button>
            <p class="help">时长为创作规划，尚未提交视频模型。模板不会理解图片内容，图文一致性需人工检查。</p>
          </form>
          <div class="project-list"><div class="heading"><h2>最近项目</h2><button class="secondary" :disabled="loading || busy" @click="load">刷新</button></div>
            <button v-for="item in projects" :key="item.id" class="project-choice" :class="{ selected: project?.id === item.id }"
              :disabled="busy || dirty" @click="select(item.id)"><strong>{{ item.title }}</strong><span>{{ projectStates[item.status] }} · v{{ item.revision }}</span></button>
            <button v-if="project" class="secondary" :disabled="busy || dirty" @click="reset">开始下一次创作</button></div>
        </aside>
        <div class="editor">
          <StoryboardModelPanel v-if="project" :user="user" :project="project" :dirty="dirty || busy || loading" @load="discardChanges" />
          <div v-if="!project" class="empty">填写创作需求后，会生成可编辑的脚本和镜头草稿。</div>
          <fieldset v-else class="editor-body" :disabled="busy || loading">
            <div class="heading"><div><h2>{{ project.brief.title }}</h2><p class="help">{{ projectStates[project.status] }} · 当前版本 {{ project.revision.number }} · {{ dirty ? '有未保存修改' : '已保存' }}</p></div>
              <button class="secondary" :disabled="busy || loading" @click="discardChanges">重新载入已保存版本</button></div>
            <details class="original"><summary>原始创作需求</summary><p>{{ project.brief.goal }}</p><p>{{ project.brief.sellingPoints.join(' / ') }}</p></details>
            <ul v-if="project.revision.validationErrors.length" class="validation"><li v-for="item in project.revision.validationErrors" :key="item">{{ item }}</li></ul>
            <label>视频脚本<textarea v-model="draft.script" maxlength="4000" rows="4" :disabled="busy"></textarea></label>
            <div class="duration" :class="{ invalid: totalDuration !== project.brief.desiredDurationSeconds }">镜头期望时长 {{ totalDuration }} / {{ project.brief.desiredDurationSeconds }} 秒 · {{ draft.shots.length }} 个镜头</div>
            <article v-for="(shot, index) in draft.shots" :key="shot.id" class="shot-card">
              <div class="heading"><h3>镜头 {{ index + 1 }} · {{ shot.title || '未命名' }}</h3><div class="shot-actions">
                <button class="secondary" :disabled="busy || index === 0" @click="moveShot(index, -1)">上移</button>
                <button class="secondary" :disabled="busy || index === draft.shots.length - 1" @click="moveShot(index, 1)">下移</button>
                <button class="secondary" :disabled="busy" @click="removeShot(index)">删除</button></div></div>
              <div class="row"><label>镜头名称<input v-model="shot.title" maxlength="120" :disabled="busy"></label><label>主体<input v-model="shot.subject" maxlength="200" :disabled="busy"></label></div>
              <label>动作与展示内容<textarea v-model="shot.action" maxlength="500" rows="2" :disabled="busy"></textarea></label>
              <div class="row"><label>场景<input v-model="shot.setting" maxlength="500" :disabled="busy"></label><label>运镜<input v-model="shot.camera" maxlength="200" :disabled="busy"></label></div>
              <div class="row"><label>期望时长（秒）<input v-model.number="shot.desiredDurationSeconds" type="number" min="2" max="15" :disabled="busy"></label>
                <label>参考图片<select v-model="shot.referenceAssetId" :disabled="busy"><option :value="null">无参考图片</option><option v-for="assetId in assetIds" :key="assetId" :value="assetId">参考图 {{ assetId.slice(0, 8) }}</option></select></label></div>
              <label>字幕<input v-model="shot.caption" maxlength="120" :disabled="busy"></label>
              <label>口播<textarea v-model="shot.narration" maxlength="500" rows="2" :disabled="busy"></textarea></label>
              <div class="heading"><span class="help">生成提示词 · 已保存版本 {{ shot.promptVersion }}</span><button class="secondary" :disabled="busy" @click="shot.prompt = composeShotPrompt(shot, project.brief.style)">根据分镜更新提示词</button></div>
              <textarea v-model="shot.prompt" maxlength="2000" rows="3" aria-label="镜头生成提示词" :disabled="busy"></textarea>
              <div class="row"><label>负向提示（可选）<input v-model="shot.parameters.negativePrompt" maxlength="2000" :disabled="busy"></label><label>种子（可选）<input v-model="shot.parameters.seed" inputmode="numeric" :disabled="busy"></label></div>
            </article>
            <button class="secondary" :disabled="busy || draft.shots.length >= 8" @click="addShot">添加镜头</button>
            <div class="save-actions"><button :disabled="busy || !dirty || totalDuration !== project.brief.desiredDurationSeconds" @click="save">保存编辑稿</button>
              <button :disabled="busy || dirty || project.status === 'CONFIRMED' || !draft.shots.length" @click="confirm">确认当前分镜</button></div>
            <p class="help">编辑后需保存并重新确认。确认记录用于后续生成，当前不会提交模型任务。</p>
            <details v-if="history" class="versions"><summary>修订与确认记录（{{ history.revisions.length }} 个版本）</summary>
              <p v-for="version in history.revisions" :key="version.number">v{{ version.number }} · {{ version.origin === 'TEMPLATE' ? '模板草稿' : version.origin === 'USER' ? '用户编辑稿' : '待人工补充' }} · {{ new Date(version.createdAt).toLocaleString() }}
                <button class="secondary" :disabled="busy" @click="restore(version)">载入为编辑稿</button></p>
              <p v-for="item in history.confirmations" :key="item.revision">v{{ item.revision }} 已于 {{ new Date(item.confirmedAt).toLocaleString() }} 确认</p>
              <p class="help">历史版本保持不变；载入旧版本后保存，会创建新的修订。</p></details>
          </fieldset>
          <ShotGenerationPanel v-if="project" :user="user" :project="project" :dirty="dirty || busy || loading" @apply-case="applyEvaluationCase" />
        </div>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, onUnmounted, ref, toRef, watch } from 'vue'
import { apiRequest, captureAuthSession } from './api'
import { composeShotPrompt, emptyShot, projectStates, storyboardApi, useStoryboardWorkspace } from './storyboardWorkspace'
import ShotGenerationPanel from './ShotGenerationPanel.vue'
import StoryboardModelPanel from './StoryboardModelPanel.vue'
const props = defineProps({ user: Object })
defineEmits(['login'])
const request = storyboardApi(apiRequest)
const { projects, project, draft, history, error, notice, busy, loading, dirty, totalDuration, load, select, create, save, confirm, restore, reset } =
  useStoryboardWorkspace({ user: toRef(props, 'user'), request, captureSession: captureAuthSession, storage: sessionStorage })
const brief = ref({ title: '', productName: '', goal: '', style: '自然光，简洁产品展示', desiredDurationSeconds: 15, shotCount: 3, frameRatio: '9:16', referenceAssetId: null })
const sellingPoints = ref(''), uploading = ref(false), uploadedAssets = ref([])
let uploadEpoch = 0
const assetIds = computed(() => Array.from(new Set([...uploadedAssets.value, brief.value.referenceAssetId, project.value?.brief.referenceAssetId,
  ...(draft.value?.shots.map(shot => shot.referenceAssetId) || [])].filter(Boolean))))
async function createProject() {
  const input = { ...brief.value, sellingPoints: sellingPoints.value.split('\n').map(item => item.trim()).filter(Boolean) }
  if (!input.sellingPoints.length || input.sellingPoints.length > 8 || input.sellingPoints.some(item => item.length > 120)) { error.value = '请填写 1–8 条卖点，每条最多 120 字'; return }
  await create(input)
}
async function uploadReference(event) {
  const file = event.target.files?.[0]
  if (!file) return
  const epoch = uploadEpoch, current = captureAuthSession()
  uploading.value = true; error.value = ''; brief.value.referenceAssetId = null
  try {
    if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 5 * 1024 * 1024) throw new Error('请选择 5 MiB 以内的 PNG / JPEG 图片')
    const data = new FormData(); data.append('file', file)
    const asset = await request('/generation/assets', { method: 'POST', body: data })
    if (epoch === uploadEpoch && current()) { uploadedAssets.value.push(asset.id); brief.value.referenceAssetId = asset.id }
  } catch (failure) { if (epoch === uploadEpoch && current()) error.value = failure.message }
  finally { if (epoch === uploadEpoch && current()) uploading.value = false }
}
function addShot() { draft.value.shots.push(emptyShot(draft.value.shots.length + 1, project.value.brief.frameRatio)) }
function removeShot(index) { draft.value.shots.splice(index, 1); renumber() }
function moveShot(index, direction) { const shot = draft.value.shots.splice(index, 1)[0]; draft.value.shots.splice(index + direction, 0, shot); renumber() }
function renumber() { draft.value.shots.forEach((shot, index) => { shot.sequence = index + 1 }) }
function discardChanges() { return select(project.value.id) }
function applyEvaluationCase(item) {
  if (!item || busy.value || loading.value || dirty.value || draft.value.shots.length < 2) return
  const reference = draft.value.shots[0].referenceAssetId
  draft.value.shots[0].prompt = item.originalPrompt; draft.value.shots[1].prompt = item.structuredPrompt
  for (const shot of draft.value.shots.slice(0, 2)) { shot.referenceAssetId = reference; shot.parameters = { seed: item.seed, negativePrompt: null } }
  notice.value = `已载入「${item.title}」对比提示词；请核对草稿，保存并确认后再提交。未调用模型。`
}
watch(() => props.user?.id, () => {
  uploadEpoch += 1; uploadedAssets.value = []; uploading.value = false; sellingPoints.value = ''
  brief.value = { title: '', productName: '', goal: '', style: '自然光，简洁产品展示', desiredDurationSeconds: 15, shotCount: 3, frameRatio: '9:16', referenceAssetId: null }
})
onUnmounted(() => { uploadEpoch += 1 })
</script>

<style scoped>
.storyboard-workspace { max-width:1180px; margin:0 auto; padding:24px 0 64px; color:#eceef2; }
.intro { margin-bottom:28px; } .eyebrow { color:#b1ff86; font-size:12px; letter-spacing:.15em; }
h1 { font-size:clamp(24px,3vw,36px); margin:12px 0; } h2 { font-size:18px; margin:0; } h3 { font-size:15px; margin:0; }
.intro p:not(.eyebrow), .help { color:#a8adb9; font-size:12px; line-height:1.7; }
.template-badge { display:inline-block; color:#b1ff86; border:1px solid #455c3a; padding:8px 12px; border-radius:8px; font-size:12px; }
.storyboard-grid { display:grid; grid-template-columns:310px minmax(0,1fr); gap:24px; align-items:start; }
.brief-form, .project-list, .editor { padding:22px; border:1px solid #303540; border-radius:14px; background:#15181e; }
.brief-form { display:flex; flex-direction:column; gap:15px; } .project-list { margin-top:20px; }
.editor-body { border:0; padding:0; margin:0; min-width:0; }
label { display:flex; flex-direction:column; gap:7px; font-size:13px; line-height:1.5; }
input, textarea, select { box-sizing:border-box; width:100%; background:#0e1116; color:#e5e8ed; border:1px solid #3a404d; border-radius:8px; padding:10px; font:inherit; }
textarea { resize:vertical; } .row { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
button { border:0; border-radius:8px; padding:10px 14px; background:#b1ff86; color:#102008; cursor:pointer; font:inherit; font-size:12px; }
button:disabled { opacity:.4; cursor:default; } .secondary { background:#242a34; color:#d5d9e2; border:1px solid #404755; }
input:focus, textarea:focus, select:focus, button:focus-visible { outline:2px solid #b1ff86; outline-offset:2px; }
.heading { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:14px; }
.project-choice { display:flex; justify-content:space-between; gap:8px; width:100%; background:#202630; color:#c5cbd6; margin:10px 0; text-align:left; }
.selected { outline:1px solid #6f9656; } .empty { padding:48px 20px; text-align:center; color:#a8adb9; line-height:1.8; }
.shot-card { background:#101319; border:1px solid #303540; border-radius:12px; padding:18px; display:flex; flex-direction:column; gap:14px; margin:18px 0; }
.shot-actions { display:flex; gap:5px; } .shot-actions button { padding:6px 9px; }
.save-actions { display:flex; flex-wrap:wrap; gap:12px; margin-top:22px; padding-top:18px; border-top:1px solid #303540; }
.duration { margin-top:16px; font-size:13px; color:#b1ff86; } .invalid, .error, .validation { color:#ffb3a8; }
.error, .notice { padding:12px; border-radius:8px; font-size:13px; line-height:1.7; background:#252127; } .notice { color:#b1ff86; background:#202b1c; }
.original, .versions { font-size:12px; color:#b5bdca; line-height:1.7; margin:18px 0; } summary { cursor:pointer; } .versions { border-top:1px solid #303540; padding-top:16px; }
.versions button { margin-left:8px; } .asset-saved { display:flex; justify-content:space-between; align-items:center; font-size:12px; color:#b1ff86; }
@media(max-width:900px) { .storyboard-grid { grid-template-columns:1fr; } .brief-form,.editor,.project-list { padding:18px; } }
@media(max-width:520px) { .row { grid-template-columns:1fr; } .shot-card .heading { flex-direction:column; align-items:flex-start; } }
</style>
