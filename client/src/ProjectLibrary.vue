<template>
  <div class="library">
    <h2>项目库</h2>
    <form @submit.prevent="applySearch"><input v-model="search" maxlength="120" placeholder="搜索项目、产品或项目 ID" aria-label="搜索项目"><button :disabled="loading || blocked">搜索</button></form>
    <label class="filter"><input type="checkbox" v-model="archived" :disabled="blocked">查看已归档项目</label>
    <p v-if="error || operationError" role="alert">{{ error || operationError }}</p>
    <p v-if="!loading && !items.length" class="muted">没有匹配的项目。</p>
    <article v-for="item in items" :key="item.id">
      <button class="project" :class="{ selected: selectedId === item.id }" :disabled="blocked || busy || dirty" @click="$emit('select', item.id)">
        <strong>{{ item.title }}</strong><span>{{ item.archived ? '已归档' : projectStates[item.status] }} · v{{ item.revision }}</span>
      </button>
      <button class="secondary" :disabled="!actionAllowed(item)" @click="archive(item)">{{ item.archived ? '恢复项目' : '归档' }}</button>
    </article>
    <div class="actions"><button :disabled="loading" @click="load()">刷新</button><button v-if="nextCursor" :disabled="loading" @click="load(true)">加载更多</button></div>
    <p class="muted">归档保留分镜、视频与记录。进行中或结果未知的项目暂不能归档。</p>
  </div>
</template>
<script setup>
import { computed, ref, toRef, watch } from 'vue'
import { apiRequest, captureAuthSession } from './api'
import { storyboardApi, projectStates } from './storyboardWorkspace'
import { useCursorLibrary } from './libraryWorkspace'
import { projectLibraryActionAllowed, useLibraryAction } from './libraryActions'
const props = defineProps({ user: Object, selectedId: String, blocked: Boolean, dirty: Boolean, revision: Number })
const emit = defineEmits(['select', 'changed', 'busy'])
const request = storyboardApi(apiRequest), search = ref(''), applied = ref(''), archived = ref(false)
const query = computed(() => ({ q: applied.value, archived: String(archived.value) }))
const { items, nextCursor, loading, error, load, clear } = useCursorLibrary({ user: toRef(props, 'user'), query, path: '/generation/projects/page', request, captureSession: captureAuthSession })
const { busy, error: operationError, run } = useLibraryAction({ user: toRef(props, 'user'), captureSession: captureAuthSession, onInvalidate: reason => { if (reason === 'session') clear() }, onBusy: value => emit('busy', value) })
function applySearch() { const value = search.value.trim(); if (value === applied.value) load(); else applied.value = value }
function actionAllowed(item) { return projectLibraryActionAllowed({ blocked: props.blocked, busy: busy.value, dirty: props.dirty }, item) }
function archive(item) {
  if (!actionAllowed(item)) return
  return run(async current => {
    const saved = await request(`/generation/projects/${item.id}/archive`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ archived: !item.archived }) })
    if (!current()) return
    emit('changed', { id: item.id, archived: saved.archived }); await load()
  })
}
watch(() => props.user?.id, () => { search.value = ''; applied.value = ''; archived.value = false })
watch([() => props.selectedId, () => props.revision], () => load())
</script>
<style scoped>
.library{margin-top:20px;padding:18px;border:1px solid #303540;border-radius:14px;background:#15181e}h2{font-size:18px;margin:0 0 14px}form,.actions{display:flex;gap:8px}input:not([type=checkbox]){min-width:0;width:100%;padding:9px;background:#0e1116;border:1px solid #3a404d;border-radius:7px;color:#eee}.filter{display:flex;gap:8px;align-items:center;font-size:12px;margin:12px 0}article{margin:12px 0;display:flex;gap:6px}.project{flex:1;text-align:left;display:grid;gap:5px}.project span,.muted{font-size:12px;color:#a8adb9;line-height:1.6}.selected{outline:1px solid #b1ff86}button{padding:9px;border:1px solid #404755;border-radius:7px;background:#242a34;color:#ddd;cursor:pointer}button:disabled{opacity:.4;cursor:default}[role=alert]{color:#ffb3a8;font-size:12px}.secondary{font-size:11px;flex-shrink:0}
</style>
