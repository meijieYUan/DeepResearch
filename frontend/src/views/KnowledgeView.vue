<template>
  <div class="view-container">
    <div class="view-header">
      <h2>Knowledge Base</h2>
      <p class="view-sub">Upload documents to ground the assistant's answers.</p>
    </div>
    <div class="upload-zone card" @dragover.prevent @drop.prevent="onDrop" @click="triggerFile">
      <input ref="fileInput" type="file" :accept="ACCEPT_ATTR" @change="onFile" hidden />
      <Upload :size="36" />
      <p>Drop files here or click to upload</p>
      <p class="upload-hint">Supports PDF, Markdown, TXT, Java, Python, XML, JSON (max {{ MAX_SIZE_LABEL }})</p>
    </div>
    <div v-if="uploading" class="upload-progress">
      <span class="loading"></span> Uploading {{ uploadFile }}...
    </div>
    <div v-if="uploadError" class="upload-error">
      <AlertCircle :size="16" /> {{ uploadError }}
    </div>
    <div v-if="result" class="upload-result card">
      <CheckCircle :size="18" class="result-icon" />
      <div><strong>Uploaded:</strong> {{ result.filename }}</div>
      <div><strong>Chunks:</strong> {{ result.chunks }}</div>
    </div>

    <div class="docs-section">
      <div class="docs-header">
        <h3>Indexed documents</h3>
        <button class="btn-ghost btn-sm" :disabled="docsLoading" @click="loadDocuments">
          {{ docsLoading ? 'Loading…' : 'Refresh' }}
        </button>
      </div>
      <p v-if="docsError" class="upload-error"><AlertCircle :size="16" /> {{ docsError }}</p>
      <p v-else-if="!documents.length" class="muted">No documents indexed yet.</p>
      <ul v-else class="doc-list">
        <li v-for="doc in documents" :key="doc.source">
          <span class="doc-name" :title="doc.source">{{ doc.source }}</span>
          <span class="doc-chunks">{{ doc.chunks }} chunks</span>
          <button class="btn-ghost btn-sm" :disabled="deleting === doc.source"
                  @click="removeDocument(doc)">
            {{ deleting === doc.source ? 'Deleting…' : 'Delete' }}
          </button>
        </li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { Upload, CheckCircle, AlertCircle } from 'lucide-vue-next'
import { deleteKnowledge, listKnowledge, uploadKnowledge } from '../api'

// Mirrors the picker's `accept` list: a file is checked before the request so an
// unsupported one gets a specific message instead of a round trip.
const ACCEPT_EXTENSIONS = ['.pdf', '.md', '.txt', '.java', '.py', '.xml', '.json']
const ACCEPT_ATTR = ACCEPT_EXTENSIONS.join(',')
const MAX_UPLOAD_BYTES = 10 * 1024 * 1024
const MAX_SIZE_LABEL = '10 MB'

const fileInput = ref(null)
const uploading = ref(false)
const uploadFile = ref('')
const uploadError = ref('')
const result = ref(null)
const documents = ref([])
const docsLoading = ref(false)
const docsError = ref('')
const deleting = ref('')

function triggerFile() { fileInput.value.click() }

function formatSize(bytes) {
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  if (bytes >= 1024) return Math.round(bytes / 1024) + ' KB'
  return bytes + ' B'
}

function validate(file) {
  const name = (file.name || '').toLowerCase()
  const dot = name.lastIndexOf('.')
  const ext = dot >= 0 ? name.slice(dot) : ''
  if (!ACCEPT_EXTENSIONS.includes(ext)) {
    return `Unsupported file type "${ext || file.name}". Allowed: ${ACCEPT_EXTENSIONS.join(', ')}.`
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return `File is too large (${formatSize(file.size)}). The maximum is ${MAX_SIZE_LABEL}.`
  }
  return ''
}

// Clearing the input's value lets the same file be chosen again: without it the
// change event never fires a second time for an unchanged path.
function resetPicker() {
  if (fileInput.value) fileInput.value.value = ''
}

function onFile(e) {
  const file = e.target.files[0]
  if (file) doUpload(file)
}

function onDrop(e) {
  const file = e.dataTransfer.files[0]
  if (file) doUpload(file)
}

function doUpload(file) {
  uploadError.value = ''
  result.value = null

  const problem = validate(file)
  if (problem) {
    // Nothing was sent, so leave the picker usable for the corrected file.
    resetPicker()
    uploadError.value = problem
    return
  }

  uploadFile.value = file.name
  uploading.value = true
  // The request is `silent` (see api/index.js): this view owns the error, shown
  // inline below — a global toast as well would just duplicate it.
  uploadKnowledge(file)
    .then(r => {
      // The endpoint reports extraction/indexing failures as HTTP 200 +
      // {success:false,error}; treating that as a success showed "Uploaded:
      // undefined" while the knowledge base stayed empty.
      if (r.data?.success === false) {
        uploadError.value = r.data?.error || 'Upload failed'
        return
      }
      result.value = r.data
      loadDocuments()
    })
    .catch(err => { uploadError.value = err.response?.data?.message || err.message || 'Upload failed' })
    .finally(() => {
      uploading.value = false
      resetPicker()
    })
}

async function loadDocuments() {
  docsLoading.value = true
  docsError.value = ''
  try {
    const response = await listKnowledge()
    documents.value = Array.isArray(response.data?.documents) ? response.data.documents : []
  } catch (err) {
    docsError.value = err.response?.data?.message || err.message || 'Failed to load documents'
  } finally {
    docsLoading.value = false
  }
}

async function removeDocument(doc) {
  if (!window.confirm(`Delete "${doc.source}" from the knowledge base?`)) return
  deleting.value = doc.source
  docsError.value = ''
  try {
    await deleteKnowledge(doc.source)
    await loadDocuments()
  } catch (err) {
    docsError.value = err.response?.data?.message || err.message || 'Delete failed'
  } finally {
    deleting.value = ''
  }
}

onMounted(loadDocuments)
</script>

<style scoped>
.view-container { max-width: 620px; margin: 0 auto; }
.view-header { margin-bottom: 24px; }
.view-header h2 { font-size: 19px; font-weight: 650; letter-spacing: -.02em; }
.view-sub { font-size: 13px; color: var(--text2); margin-top: 5px; }
.upload-zone {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 52px 24px; border: 2px dashed var(--border-strong); border-radius: 16px;
  background: var(--bg2); color: var(--text2);
  cursor: pointer; transition: all .2s;
}
.upload-zone:hover { border-color: var(--accent); background: var(--accent-soft); color: var(--accent); }
.upload-zone p { font-size: 14px; font-weight: 500; }
.upload-hint { font-size: 12px; font-weight: 400; color: var(--text2); }
.upload-progress { display: flex; align-items: center; gap: 8px; margin-top: 16px; font-size: 13px; }
.upload-error { display: flex; align-items: center; gap: 8px; margin-top: 16px; font-size: 13px; color: var(--red); }
.upload-result { margin-top: 16px; display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
.result-icon { color: var(--green); margin-bottom: 4px; }
.docs-section { margin-top: 28px; }
.docs-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.docs-header h3 { font-size: 14px; font-weight: 650; }
.doc-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.doc-list li {
  display: flex; align-items: center; gap: 12px; padding: 10px 12px;
  background: var(--bg2); border: 1px solid var(--border); border-radius: 10px;
}
.doc-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.doc-chunks { flex-shrink: 0; font-size: 12px; color: var(--text2); }
.muted { font-size: 13px; color: var(--text2); }
</style>
