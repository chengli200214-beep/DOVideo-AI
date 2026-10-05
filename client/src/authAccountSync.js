import { bindAuthSession } from './api.js'

/** Storage events may arrive after a click; apiRequest also signals before any mismatched request. */
export function listenForAccountChanges({ target, storage, onChange }) {
  const synchronize = event => {
    if ((event.storageArea && event.storageArea !== storage)
        || (event.key != null && !['authToken', 'user'].includes(event.key))) return
    let user = null
    try { if (storage.getItem('authToken')) user = JSON.parse(storage.getItem('user')) } catch {}
    bindAuthSession()
    onChange(user?.id ? user : null)
  }
  target.addEventListener('storage', synchronize)
  target.addEventListener('auth-changed', synchronize)
  return () => {
    target.removeEventListener('storage', synchronize)
    target.removeEventListener('auth-changed', synchronize)
  }
}
