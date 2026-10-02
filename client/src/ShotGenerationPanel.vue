<template>
  <section class="generation-panel" aria-labelledby="shot-generation-heading">
    <div class="heading"><h2 id="shot-generation-heading">镜头生成</h2><button :disabled="busy || loading" @click="refresh">刷新状态</button></div>
    <p class="help">从已确认分镜生成独立镜头；期望时长用于创作规划，实际长度以模型产物为准。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <p v-if="!cleanConfirmed" class="help">请保存并确认当前分镜，再提交首次生成或局部重生成。已有任务可继续查询和恢复。</p>
    <template v-if="overview">
      <div class="ledger"><span>版本额度 {{ overview.budget.usedVersions }} / {{ overview.budget.maxVersions }}</span>
        <span>预留 ¥{{ overview.budget.reservedCost }} / ¥{{ overview.budget.costLimit }}</span>
        <span>已知实际成本 ¥{{ overview.knownActualCost }} · {{ overview.unknownCostTasks }} 个任务实际成本未知</span></div>
      <form v-if="overview.budget.usedVersions === 0" class="budget" @submit.prevent="configureBudget">
        <label>最多生成版本数<input v-model.number="maxVersions" type="number" min="1" max="1000" required :disabled="busy || loading"></label>
        <label>预留预算上限（元）<input v-model="costLimit" type="number" min="0" step="0.000001" required :disabled="busy || loading"></label>
        <button :disabled="busy || loading">保存项目额度</button>
      </form>
      <p class="help">首次生成后额度固定，局部重生成也占用版本额度。预留金额是配置的保守估算，供应商账单尚未接入；失败与待核对任务不会自动退还预留。项目额度不授予付费模型调用权限。</p>
      <div v-if="quote" class="quote">
        <p v-for="shot in quote.shots" :key="shot.shotId">镜头 {{ shot.sequence }} · {{ shot.title }} · {{ shot.kind === 'IMAGE_TO_VIDEO' ? '图生视频' : '文生视频' }} · {{ shot.model }} · {{ shot.imageSize }} · 预留 ¥{{ shot.reservation }} <span v-if="!shot.available">（调用未开放）</span></p>
        <p class="help" v-if="quote.shots.every(shot => shot.provider === 'mock')">当前使用 Mock：返回固定测试视频，用于验证流程与归档，不代表生成效果。</p>
        <button :disabled="busy || loading || !cleanConfirmed || !initialIds.length || !quote.shots.every(shot => shot.available) || !overview.budget.maxVersions" @click="submit({ mode: 'INITIAL', shotIds: initialIds })">生成尚未提交的 {{ initialIds.length }} 个镜头</button>
      </div>
      <button v-if="pendingInput" :disabled="busy || loading || !cleanConfirmed" @click="retryPending">重试上次提交（复用请求编号）</button>
      <article v-for="version in overview.versions" :key="version.id" class="version">
        <div class="heading"><strong>{{ shotTitle(version) }} · 分镜 v{{ version.revision }} / 生成 v{{ version.version }}</strong><span>{{ generationStates[version.task.state] }}</span></div>
        <p class="help">{{ version.task.model }} · 任务 {{ version.task.id }}<br>预留 ¥{{ version.reservation }} · {{ costLabel(version) }}<span v-if="version.task.errorCode"> · {{ version.task.errorCode }}</span></p>
        <div class="actions">
          <button v-if="version.task.state === 'SUCCEEDED'" :disabled="busy || loading" @click="preview(version.task)">查看已归档视频</button>
          <button v-if="version.task.state === 'FAILED' && version.task.recoverable" :disabled="busy || loading || version.task.recoveryAvailable === false" @click="recover(version.task)">恢复原任务</button>
          <button v-if="isLatest(version) && ['SUCCEEDED', 'FAILED'].includes(version.task.state)" :disabled="busy || loading || !cleanConfirmed || version.revision !== project.revision.number || !quote?.shots.find(shot => shot.shotId === version.shotId)?.available" @click="submit({ mode: 'REGENERATE', shotIds: [version.shotId] })">仅重生成此镜头</button>
        </div>
        <form v-if="version.task.state === 'SUBMISSION_UNKNOWN'" class="reconcile" @submit.prevent="reconcile(version.task, remoteIds[version.task.id])">
          <label>已核实的模型 requestId<input v-model="remoteIds[version.task.id]" maxlength="128" :disabled="busy || loading" required></label><button :disabled="busy || loading || version.task.recoveryAvailable === false">补录并查询原任务</button>
          <p v-if="version.task.recoveryAvailable === false" class="help">原任务恢复尚未开放，请先启用查询与归档恢复。</p>
        </form>
        <video v-if="artifact?.taskId === version.task.id" :src="artifact.url" controls preload="metadata"></video>
      </article>
      <p v-if="!overview.versions.length" class="help">暂无镜头生成版本。</p>
      <FilmWorkbench :user="user" :project="project" :dirty="dirty" :versions="overview.versions" @apply-case="$emit('applyCase', $event)" />
    </template>
  </section>
</template>

<script setup>
import { ref, toRef, watch } from 'vue'
import { apiRequest, captureAuthSession } from './api'
import { storyboardApi } from './storyboardWorkspace'
import { generationStates } from './generationWorkspace'
import { useShotGenerationWorkspace } from './shotGenerationWorkspace'
import FilmWorkbench from './FilmWorkbench.vue'
const props = defineProps({ user: Object, project: { type: Object, required: true }, dirty: Boolean })
defineEmits(['applyCase'])
const { overview, quote, error, notice, busy, loading, pendingInput, artifact, cleanConfirmed, initialIds,
  refresh, configure, submit, retryPending, recover, reconcile, preview } = useShotGenerationWorkspace({
  user: toRef(props, 'user'), project: toRef(props, 'project'), dirty: toRef(props, 'dirty'),
  request: storyboardApi(apiRequest), captureSession: captureAuthSession, storage: sessionStorage })
const maxVersions = ref(12), costLimit = ref('0'), remoteIds = ref({})
watch(() => props.project.id, () => { maxVersions.value = 12; costLimit.value = '0'; remoteIds.value = {} })
watch(overview, value => {
  if (value?.budget.maxVersions) { maxVersions.value = value.budget.maxVersions; costLimit.value = String(value.budget.costLimit) }
})
function configureBudget() { return configure({ maxVersions: maxVersions.value, costLimit: costLimit.value }) }
function shotTitle(version) { try { return JSON.parse(version.shotJson).title } catch { return '镜头' } }
function isLatest(version) { return !overview.value.versions.some(other => other.shotId === version.shotId && other.revision === version.revision && other.version > version.version) }
function costLabel(version) { return version.costStatus === 'FREE_MOCK' ? 'Mock 实际成本 ¥0' : version.actualCost != null ? `实际成本 ¥${version.actualCost}` : version.costStatus === 'UNKNOWN' ? '实际成本未知' : '尚未提交模型，实际成本未结算' }
</script>

<style scoped>
.generation-panel { border-top:1px solid #303540; margin-top:24px; padding-top:24px; color:#eceef2; }
h2 { font-size:18px; margin:0; } .heading { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.help, .quote { color:#a8adb9; font-size:12px; line-height:1.8; overflow-wrap:anywhere; }
.ledger { display:flex; flex-wrap:wrap; gap:12px; padding:14px; background:#202630; border-radius:8px; font-size:12px; }
.budget, .reconcile { display:flex; align-items:end; flex-wrap:wrap; gap:10px; margin:16px 0; } label { display:flex; flex-direction:column; gap:7px; font-size:12px; }
input { box-sizing:border-box; width:180px; background:#0e1116; color:#e5e8ed; border:1px solid #3a404d; border-radius:8px; padding:10px; font:inherit; }
button { border:1px solid #404755; border-radius:8px; padding:10px 14px; background:#242a34; color:#d5d9e2; cursor:pointer; font:inherit; font-size:12px; }
button:disabled { opacity:.4; cursor:default; } button:focus-visible, input:focus { outline:2px solid #b1ff86; outline-offset:2px; }
.version { border:1px solid #303540; border-radius:10px; padding:16px; margin:16px 0; } .version .heading { font-size:12px; }
.actions { display:flex; gap:10px; flex-wrap:wrap; } .error, .notice { padding:12px; border-radius:8px; font-size:12px; line-height:1.7; }
.error { color:#ffb3a8; background:#252127; } .notice { color:#b1ff86; background:#202b1c; } video { display:block; width:100%; max-height:380px; margin-top:16px; background:#000; }
@media(max-width:520px) { .heading { align-items:start; flex-wrap:wrap; } .budget label, .reconcile label, input { width:100%; } }
</style>
