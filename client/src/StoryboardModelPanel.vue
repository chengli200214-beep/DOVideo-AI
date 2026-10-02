<template>
  <section class="model-panel" aria-labelledby="model-planning-heading">
    <div class="heading"><h2 id="model-planning-heading">DeepSeek 脚本与分镜</h2><button :disabled="busy || loading" @click="refresh">查询状态</button></div>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="runtime">
      <p>{{ runtime.model }} · {{ runtime.reason }}</p>
      <p v-if="runtime.ready">本次预留 {{ runtime.reservationPerCall }} {{ runtime.currency }}；已使用 {{ runtime.usedCalls }} / {{ runtime.maxCalls }} 次，已预留 {{ runtime.reservedCost }} / {{ runtime.budgetLimit }} {{ runtime.currency }}。预留金额与实际账单不同。</p>
      <p>根据已保存的原始文字需求生成新版本；参考图仅保留引用，不发送给文字模型。生成结果需人工检查并确认。</p>
      <label v-if="runtime.ready"><input v-model="agreed" type="checkbox" :disabled="busy || hasActive">我确认本次请求可能产生模型费用，并使用页面所示调用额度</label>
      <button :disabled="!ready" @click="generate()">DeepSeek 生成分镜</button>
      <button v-if="tasks.some(task => task.expectedRevision === project.revision.number && !['QUEUED','SUBMITTING'].includes(task.status))" :disabled="!ready" @click="generate(true)">再次生成（新调用，单独占用额度）</button>
    </template>
    <article v-for="task in tasks" :key="task.id">
      <strong>{{ planningStates[task.status] || task.status }}</strong> · {{ task.model }} · 请求 {{ task.id.slice(0, 8) }} · 输入 v{{ task.expectedRevision }}
      <p v-if="task.errorCode">{{ task.errorCode }}。原分镜仍保留；失败和不确定请求不会自动重发，账单请在服务商后台核对。</p>
      <p v-if="task.estimatedCost != null">按返回用量估算 {{ task.estimatedCost }} {{ task.currency }}；实际账单尚未核对。</p>
      <button v-if="task.savedRevision && task.savedRevision > project.revision.number" :disabled="dirty || busy" @click="$emit('load')">载入生成分镜 v{{ task.savedRevision }}</button>
      <details><summary>输入、输出与校验记录</summary><pre>{{ task.requestJson }}</pre><pre v-if="task.resultJson">{{ task.resultJson }}</pre><pre v-if="task.validationJson">{{ task.validationJson }}</pre><pre v-if="task.usageJson">{{ task.usageJson }}</pre></details>
    </article>
  </section>
</template>
<script setup>
import { toRef } from 'vue'
import { apiRequest, captureAuthSession } from './api'
import { storyboardApi } from './storyboardWorkspace'
import { planningStates, useStoryboardModelWorkspace } from './storyboardModelWorkspace'
const props = defineProps({ user: Object, project: Object, dirty: Boolean })
defineEmits(['load'])
const { runtime, tasks, agreed, busy, loading, error, notice, ready, hasActive, refresh, generate } = useStoryboardModelWorkspace({
  user: toRef(props, 'user'), project: toRef(props, 'project'), dirty: toRef(props, 'dirty'), request: storyboardApi(apiRequest), captureSession: captureAuthSession, storage: sessionStorage
})
</script>
<style scoped>
.model-panel { padding:18px; margin-bottom:18px; border:1px solid #424653; border-radius:12px; background:#20232a; }
.heading { display:flex; justify-content:space-between; align-items:center; gap:12px; } h2 { margin:0; font-size:17px; }
p,label { color:#b9bfca; font-size:12px; line-height:1.7; } label { display:block; margin:12px 0; } input { vertical-align:middle; }
button { padding:8px 12px; margin:4px 8px 4px 0; border-radius:6px; border:1px solid #545b69; background:#303640; color:#edf0f6; cursor:pointer; }
button:disabled { opacity:.45; cursor:default; } article { margin-top:14px; padding-top:12px; border-top:1px solid #424653; font-size:12px; }
pre { white-space:pre-wrap; overflow-wrap:anywhere; max-height:250px; overflow:auto; font-size:11px; } summary { cursor:pointer; margin:8px 0; }
</style>
