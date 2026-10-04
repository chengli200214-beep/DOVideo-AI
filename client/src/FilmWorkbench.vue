<template>
  <section class="film-workbench" aria-labelledby="film-heading">
    <div class="heading"><h2 id="film-heading">成片与效果评测</h2><button :disabled="busy || loading" @click="refresh()">刷新成片与评测</button></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <p class="help">选择与当前确认稿兼容的已归档镜头，生成输入未变化的历史视频可直接复用；按当前分镜顺序和字幕合成。保留原声，无音轨时补静音；口播文字不会自动配音。</p>
    <p v-if="runtime && !runtime.available" class="error">合成工具不可用，请检查后端 FFmpeg / ffprobe 配置。</p>
    <div class="selections">
      <label v-for="shot in project.revision.draft.shots" :key="shot.id">镜头 {{ shot.sequence }} · {{ shot.title }}
        <select v-model="choices[shot.id]" :disabled="busy || loading || !confirmed"><option :value="undefined">请选择已归档版本</option>
          <option v-for="v in options(shot.id)" :key="v.id" :value="v.id">来源分镜 v{{ v.revision }} / 生成 v{{ v.version }} · {{ v.task.model }} · {{ new Date(v.createdAt).toLocaleString() }}</option></select>
      </label>
    </div>
    <label class="check"><input v-model="burnCaptions" type="checkbox" :disabled="busy || loading || project.archived">将镜头字幕烧录进视频</label>
    <p v-if="burnCaptions && runtime && !runtime.captionsAvailable" class="error">中文字幕字体不可用，可配置 CJK 字体或关闭字幕烧录。</p>
    <div class="actions"><button :disabled="busy || loading || !ready || !selectionDirty" @click="saveSelection">保存镜头选择</button>
      <button :disabled="busy || loading || !confirmed || selectionDirty || !runtime?.available || (burnCaptions && !runtime.captionsAvailable)" @click="compose">合成为视频</button></div>
    <p class="help">{{ selectionDirty ? '当前镜头选择尚未保存' : `镜头选择版本 ${films?.selection.number} 已保存` }}。合成失败只恢复合成，视频模型任务保持不变。</p>
    <article v-for="task in films?.tasks || []" :key="task.id" class="film">
      <div class="heading"><strong>成片 {{ task.id.slice(0, 8) }}</strong><span>{{ filmStates[task.state] }} · {{ new Date(task.createdAt).toLocaleString() }}</span></div>
      <p class="help" v-if="task.errorCode">{{ task.errorCode }} · 已尝试 {{ task.attempts }} 次</p>
      <div class="actions"><button v-if="task.state === 'FAILED'" :disabled="busy || loading || project.archived" @click="retry(task)">恢复合成</button>
        <button v-if="task.state === 'SUCCEEDED'" :disabled="busy || loading" @click="preview(task)">预览与溯源</button>
        <button v-if="task.state === 'SUCCEEDED'" :disabled="downloading" @click="downloadFilm(task)">下载 MP4</button></div>
      <template v-if="artifact?.id === task.id"><video :src="artifact.url" controls preload="metadata"></video>
        <details><summary>合成输入与阶段记录</summary><p v-for="clip in artifact.detail.input.clips" :key="clip.versionId">{{ clip.title }} · 生成版本 {{ clip.versionId }} · SHA-256 {{ clip.artifactSha256 }}</p>
          <p v-for="(event, i) in artifact.detail.events" :key="i">{{ filmStates[event.state] }} · {{ new Date(event.occurredAt).toLocaleString() }} · {{ event.errorCode || '' }}</p></details></template>
    </article>
    <div class="evaluation">
      <h3>固定样例与人工评价</h3>
      <p class="help">可将前两个镜头提示词替换为同一固定样例的原始 / 结构化提示词，共用参考图和种子。操作仅编辑草稿，随后需保存、确认并明确提交生成。</p>
      <div class="actions"><select v-model="caseId" :disabled="busy || loading || project.archived"><option value="">选择评测样例</option><option v-for="item in cases" :key="item.id" :value="item.id">{{ item.title }}</option></select>
        <button :disabled="!caseId || busy || loading || dirty || project.archived || project.revision.draft.shots.length < 2" @click="$emit('applyCase', cases.find(item => item.id === caseId))">载入对比提示词</button></div>
      <template v-if="report">
        <div class="metrics"><span>全部版本 {{ report.summary.totalVersions }}</span><span>成功 {{ report.summary.succeeded }}</span><span>已评价 {{ report.summary.reviewed }}</span><span>可用 {{ report.summary.usable }}</span><span>未评价 {{ report.summary.unreviewed }}</span>
          <span>成功任务耗时中位数 {{ report.summary.medianCompletedMs == null ? '暂无' : `${(report.summary.medianCompletedMs / 1000).toFixed(2)} 秒` }}</span>
          <span>已知实际成本 ¥{{ report.summary.knownActualCost }} · {{ report.summary.unknownCostTasks }} 个已提交任务费用未知</span>
          <span>可用视频成本 {{ report.summary.costPerUsableVideo == null ? '尚不能结算' : `¥${report.summary.costPerUsableVideo}` }}</span></div>
        <p class="help">统计截点 {{ new Date(report.cutoffAt).toLocaleString() }}，失败、待定和未评价均计入。Mock 版本 {{ report.summary.mockVersions }} 个，仅用于流程演示，不作为真实模型效果。</p>
        <div class="actions"><button @click="exportJson">导出 JSON 评测</button><button @click="exportCsv">导出 CSV 明细</button></div>
        <p v-for="group in report.comparisons" :key="group.caseId + group.controlHash" class="help">{{ group.caseId }} · {{ group.model }} · 原始提示词 {{ group.original.reviewed }} / {{ group.original.total }} 已评分，结构化 {{ group.structured.reviewed }} / {{ group.structured.total }} 已评分 · {{ group.comparable ? '参数匹配，可人工比较；样本量不代表统计显著性' : '尚不可作为真实模型效果对比' }}</p>
        <article v-for="row in report.rows.filter(row => row.state === 'SUCCEEDED')" :key="row.versionId" class="review">
          <strong>生成 v{{ row.version }} · 分镜 v{{ row.revision }} · {{ row.caseId }} / {{ row.variant }} · {{ row.provider === 'mock' ? 'Mock 演示' : row.model }}</strong>
          <form v-if="reviewForms[row.versionId]" @submit.prevent="saveReview(row)">
            <div class="scores"><label v-for="field in scoreFields" :key="field.key">{{ field.title }}<select v-model.number="reviewForms[row.versionId][field.key]" :disabled="busy || loading || project.archived" required><option :value="0">未评分</option><option v-for="n in 5" :key="n" :value="n">{{ n }}</option></select></label></div>
            <label class="check"><input v-model="reviewForms[row.versionId].usable" type="checkbox" :disabled="busy || loading || project.archived">人工判断可用</label>
            <label>评价依据<textarea v-model="reviewForms[row.versionId].notes" maxlength="2000" rows="2" :disabled="busy || loading || project.archived"></textarea></label>
            <div class="actions"><button :disabled="busy || loading || project.archived || scoreFields.some(field => !reviewForms[row.versionId][field.key])">保存评价</button><button type="button" :disabled="busy || loading" @click="resetReview(row)">载入已保存评价</button></div>
          </form>
        </article>
      </template>
    </div>
  </section>
</template>

<script setup>
import { ref, toRef, watch } from 'vue'
import { apiRequest, captureAuthSession } from './api'
import { storyboardApi } from './storyboardWorkspace'
import { reusableFilmVersion, useFilmWorkspace } from './filmWorkspace'
import { browserStorage } from './generationWorkspace'
const props = defineProps({ user: Object, project: Object, dirty: Boolean, versions: Array })
defineEmits(['applyCase'])
const { films, report, cases, runtime, choices, burnCaptions, busy, loading, error, notice, artifact, confirmed, selectionDirty, ready, refresh, saveSelection, compose, retry, review, preview } = useFilmWorkspace({
  user: toRef(props, 'user'), project: toRef(props, 'project'), dirty: toRef(props, 'dirty'), versions: toRef(props, 'versions'), request: storyboardApi(apiRequest), captureSession: captureAuthSession, storage: browserStorage('sessionStorage') })
const filmStates = { QUEUED: '等待合成', RENDERING: '正在合成', SUCCEEDED: '已归档', FAILED: '合成失败' }
const scoreFields = [{ key: 'contentScore', title: '内容符合度' }, { key: 'motionScore', title: '动作与画面稳定性' }, { key: 'consistencyScore', title: '主体一致性' }]
const reviewForms = ref({}), caseId = ref(''), downloading = ref(false)
function options(shot) { return (props.versions || []).filter(v => v.shotId === shot && reusableFilmVersion(v, props.project.revision.number)).sort((a, b) => b.revision - a.revision || b.version - a.version) }
function resetReview(row) { const saved = row.review; reviewForms.value[row.versionId] = { expectedReviewVersion: saved?.number || 0, contentScore: saved?.contentScore || 0, motionScore: saved?.motionScore || 0, consistencyScore: saved?.consistencyScore || 0, usable: saved?.usable || false, notes: saved?.notes || '' } }
watch(report, value => { for (const row of value?.rows || []) if (!reviewForms.value[row.versionId]) resetReview(row) })
watch(() => [props.user?.id, props.project.id, props.project.revision.number], () => { reviewForms.value = {}; caseId.value = ''; downloading.value = false })
async function saveReview(row) { const saved = await review(row.versionId, { ...reviewForms.value[row.versionId] }); if (saved && reviewForms.value[row.versionId]) reviewForms.value[row.versionId].expectedReviewVersion = saved.number }
function saveBlob(blob, filename) { const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 5000) }
function exportJson() { if (report.value) saveBlob(new Blob([JSON.stringify(report.value, null, 2)], { type: 'application/json' }), 'project-evaluation.json') }
async function exportCsv() { const current = captureAuthSession(), id = props.project.id; try { const response = await apiRequest(`/generation/projects/${id}/evaluation-report.csv`); if (!response.ok) throw new Error(await response.text()); const text = await response.text(); if (current() && id === props.project.id) saveBlob(new Blob([text], { type: 'text/csv;charset=utf-8' }), 'project-evaluation.csv') } catch (e) { if (current() && id === props.project.id) error.value = e.message } }
async function downloadFilm(task) { const current = captureAuthSession(), id = props.project.id; downloading.value = true; try { const response = await apiRequest(`/generation/projects/${id}/films/${task.id}/download`); if (!response.ok) throw new Error(await response.text()); const blob = await response.blob(); if (current() && id === props.project.id) saveBlob(blob, `film-${task.id.slice(0, 8)}.mp4`) } catch (e) { if (current() && id === props.project.id) error.value = e.message } finally { if (current() && id === props.project.id) downloading.value = false } }
</script>

<style scoped>
.film-workbench { margin-top:28px; padding-top:24px; border-top:1px solid #303540; color:#eceef2; } h2 { margin:0; font-size:18px; } h3 { font-size:15px; }
.heading, .actions, .metrics { display:flex; flex-wrap:wrap; gap:10px; align-items:center; } .heading { justify-content:space-between; font-size:12px; }
.help, details { font-size:12px; color:#a8adb9; line-height:1.8; overflow-wrap:anywhere; } .selections { display:grid; gap:12px; margin:18px 0; }
label { display:flex; flex-direction:column; gap:7px; font-size:12px; } select, textarea { width:100%; box-sizing:border-box; border:1px solid #3a404d; background:#0e1116; color:#e5e8ed; border-radius:8px; padding:10px; font:inherit; }
button { border:1px solid #404755; border-radius:8px; padding:10px 14px; background:#242a34; color:#d5d9e2; cursor:pointer; font:inherit; font-size:12px; } button:disabled { opacity:.4; cursor:default; }
input:focus, textarea:focus, select:focus, button:focus-visible { outline:2px solid #b1ff86; outline-offset:2px; }
.check { flex-direction:row; align-items:center; margin:14px 0; } .film, .review { padding:16px; border:1px solid #303540; border-radius:10px; margin:16px 0; } .review strong { font-size:12px; } .review form { margin-top:14px; display:grid; gap:12px; }
.scores { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; } .metrics { padding:14px; background:#202630; border-radius:8px; font-size:12px; } .evaluation { border-top:1px solid #303540; margin-top:24px; padding-top:14px; }
.error, .notice { padding:12px; font-size:12px; line-height:1.7; border-radius:8px; } .error { color:#ffb3a8; background:#252127; } .notice { color:#b1ff86; background:#202b1c; } video { width:100%; max-height:460px; background:#000; margin:16px 0; }
@media(max-width:520px) { .scores { grid-template-columns:1fr; } }
</style>
