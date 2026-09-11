import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'
import DOMPurify from 'dompurify'
import 'highlight.js/styles/github.css'

marked.setOptions({ gfm: true, breaks: true })

marked.use({
  renderer: {
    code({ text, lang }) {
      const language = lang && hljs.getLanguage(lang) ? lang : 'plaintext'
      const highlighted = hljs.highlight(text, { language }).value
      return `<div class="code-block"><div class="code-header"><span class="code-lang">${language}</span><button type="button" class="code-copy">Copy</button></div><pre><code class="hljs language-${language}">${highlighted}</code></pre></div>`
    },
    link({ href, title, tokens }) {
      const text = this.parser.parseInline(tokens)
      const titleAttr = title ? ` title="${title}"` : ''
      return `<a href="${href}"${titleAttr} target="_blank" rel="noopener noreferrer">${text}</a>`
    }
  }
})

export function renderMarkdown(text) {
  if (!text) return ''
  const html = marked.parse(String(text))
  return DOMPurify.sanitize(html)
}

// Delegated click handler for code-block copy buttons.
// Attach once on the container that hosts v-html rendered markdown.
export function handleCopyClick(event) {
  const btn = event.target.closest('.code-copy')
  if (!btn) return
  const code = btn.closest('.code-block')?.querySelector('pre code')
  if (!code) return
  navigator.clipboard.writeText(code.textContent).then(() => {
    btn.textContent = 'Copied'
    setTimeout(() => { btn.textContent = 'Copy' }, 1500)
  })
}
