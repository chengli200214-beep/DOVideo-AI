<template>
  <details class="library" @toggle="opened">
    <summary>参考素材库与清理</summary>
    <template v-if="active">
      <p class="muted">历史项目和任务使用过的素材会保留。只能清理未被引用的图片。</p>
      <p v-if="error || operationError" role="alert">{{ error || operationError }}</p>
      <p v-if="cleanupBlocked" role="alert">{{ cleanupReason || '无法核对本机草稿引用，已暂停素材删除。' }}</p>
      <article v-for="item in items" :key="item.asset.id">
        <div>{{ item.asset.width }} × {{ item.asset.height }} · {{ Math.ceil(item.asset.size / 1024) }} KB <span class="muted">{{ item.asset.id.slice(0,8) }}</span></div>
        <span class="muted">{{ item.referenced ? '已被项目或任务引用' : protectedIds.includes(item.asset.id) ? '本机草稿或当前编辑正在使用' : '未引用' }}</span>
        <div class="actions"><button :disabled="busy" @click="previewAsset(item.asset)">预览</button><button :disabled="busy || blocked" @click="$emit('select', item.asset)">用作参考图</button>
          <button :disabled="busy || blocked || cleanupBlocked || item.referenced || protectedIds.includes(item.asset.id)" @click="remove(item.asset)">删除未用素材</button></div>
      </article>
      <img v-if="preview" :src="preview" alt="参考素材预览">
      <p v-if="!items.length && !loading" class="muted">暂无素材。</p>
      <div class="actions"><button :disabled="loading || busy" @click="refresh">刷新</button><button v-if="nextCursor" :disabled="loading" @click="load(true)">加载更多</button></div>
      <p v-for="job in jobs.filter(job => job.state !== 'SUCCEEDED')" :key="job.id" class="muted">清理 {{ job.id.slice(0,8) }} · {{ cleanupStates[job.state] }} <button v-if="job.state === 'FAILED'" :disabled="busy" @click="retry(job.id)">重试清理</button></p>
    </template>
  </details>
</template>
<script setup>
import { computed, onScopeDispose, ref, toRef, watch } from 'vue'
import { apiRequest, captureAuthSession } from './api'
import { storyboardApi } from './storyboardWorkspace'
import { useCursorLibrary } from './libraryWorkspace'
import { useLibraryAction } from './libraryActions'
import { browserStorage } from './generationWorkspace'
import { collectDraftAssetIds } from './storyboardDrafts'
const props = defineProps({ user: Object, protectedIds: { type: Array, default: () => [] }, blocked: Boolean, cleanupBlocked: Boolean, cleanupReason: String })
const emit = defineEmits(['select', 'removed', 'refresh-protection'])
const active = ref(false), account = computed(() => active.value ? props.user : null), query = ref({})
const request = storyboardApi(apiRequest)
const { items, nextCursor, loading, error, load, clear } = useCursorLibrary({ user: account, query, path: '/generation/assets', request, captureSession: captureAuthSession })
const jobs = ref([]), preview = ref(null)
const cleanupStates = { PENDING: '等待清理', DELETING: '清理中', FAILED: '清理失败' }
let reads = 0
const { busy, error: operationError, run: operation } = useLibraryAction({ user: toRef(props, 'user'), captureSession: captureAuthSession,
  onInvalidate: reason => { reads++; jobs.value = []; preview.value = null; if (reason === 'session') clear() } })
function opened(event) { active.value = event.target.open; if (active.value) { emit('refresh-protection'); loadJobs() } else { reads++; preview.value = null } }
async function refresh() {
  emit('refresh-protection')
  await Promise.all([load(), loadJobs()])
}
async function loadJobs() {
  if (!props.user || !active.value) return
  const ticket = ++reads, owner = props.user.id, session = captureAuthSession()
  const current = () => ticket === reads && owner === props.user?.id && active.value && session()
  try { const result = await request('/generation/assets/cleanup'); if (current()) { jobs.value = result; operationError.value = '' } }
  catch (failure) { if (current()) operationError.value = failure.message }
  finally { if (ticket === reads && owner === props.user?.id && !session()) { jobs.value = []; preview.value = null } }
}
function previewAsset(asset) { return operation(async current => { const url = await request(`/generation/assets/${asset.id}/preview`); if (current() && active.value) preview.value = url }) }
function deletionAllowed(asset) {
  if (props.blocked || props.cleanupBlocked || !props.user || props.protectedIds.includes(asset.id)) return false
  try {
    if (collectDraftAssetIds(browserStorage('localStorage'), props.user.id).includes(asset.id)) {
      operationError.value = '这张图片仍被本机草稿引用，请先修改或丢弃对应草稿。'; emit('refresh-protection'); return false
    }
    return true
  } catch { operationError.value = '无法核对本机草稿引用，已暂停素材删除。'; emit('refresh-protection'); return false }
}
function remove(asset) {
  if (!deletionAllowed(asset) || !window.confirm('删除这张未用参考图？已经引用的素材会由服务端保护。')) return
  return operation(async current => {
    if (!deletionAllowed(asset)) return
    await request(`/generation/assets/${asset.id}`, { method: 'DELETE' })
    if (!current()) return
    preview.value = null; emit('removed', asset.id); await refresh()
  })
}
function retry(id) { return operation(async current => { await request(`/generation/assets/cleanup/${id}/retry`, { method: 'POST' }); if (current()) await refresh() }) }
watch(() => props.user?.id, () => { if (active.value) loadJobs() })
onScopeDispose(() => { reads++ })
</script>
<style scoped>
.library{margin-top:20px;padding:18px;border:1px solid #303540;border-radius:14px;background:#15181e}summary{cursor:pointer;font-size:15px}article{font-size:12px;padding:12px 0;border-top:1px solid #303540}.muted{display:block;font-size:12px;color:#a8adb9;line-height:1.6;margin:8px 0}.actions{display:flex;gap:5px;flex-wrap:wrap}button{padding:7px;border:1px solid #404755;border-radius:7px;background:#242a34;color:#ddd;cursor:pointer;font-size:11px}button:disabled{opacity:.4;cursor:default}img{max-width:100%;max-height:220px;display:block;margin:12px auto}[role=alert]{color:#ffb3a8;font-size:12px}
</style>
