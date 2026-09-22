import { marked } from 'marked'
// `lib/core` instead of `lib/common`: the common bundle carries ~35 languages,
// of which a handful are ever seen in an answer. Registering the ones that are
// (below) keeps the highlighter chunk a fraction of its former size.
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import c from 'highlight.js/lib/languages/c'
import cpp from 'highlight.js/lib/languages/cpp'
import css from 'highlight.js/lib/languages/css'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import markdown from 'highlight.js/lib/languages/markdown'
// `lib/core` does NOT register plaintext (only `lib/common` does), yet the
// renderer below falls back to it for every unlabelled or unknown fence. Without
// this import those fences would throw "Unknown language" during render.
import plaintext from 'highlight.js/lib/languages/plaintext'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import DOMPurify from 'dompurify'
import 'highlight.js/styles/github.css'

// Aliases mirror the names models actually emit in fences (`html`, `shell`, …),
// so those still highlight rather than falling back to plaintext.
const LANGUAGES = {
  bash, c, cpp, css, java, javascript, json, markdown, plaintext, python, sql, typescript, xml, yaml
}
const ALIASES = { shell: bash, sh: bash, html: xml, js: javascript, py: python, ts: typescript }

for (const [name, language] of Object.entries(LANGUAGES)) hljs.registerLanguage(name, language)
for (const [name, language] of Object.entries(ALIASES)) hljs.registerLanguage(name, language)

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
