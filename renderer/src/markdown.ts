import { katex } from "@mdit/plugin-katex"
import hljs from "highlight.js"
import MarkdownIt from "markdown-it"
import anchor from "markdown-it-anchor"
import footnote from "markdown-it-footnote"
import taskLists from "markdown-it-task-lists"
import toc from "markdown-it-toc-done-right"

import {
  containsRendererInternalMarker,
  escapeHtml,
  sanitizeMarkdownHtml,
  sanitizeUserProvidedHtml,
} from "./security"

export type RendererPlugin = (engine: MarkdownIt) => void
export const DEFERRED_CODE_BYTES = 16 * 1024
export const DEFERRED_CODE_LINES = 200

function normalizedLanguage(value: string): string {
  const language = value.trim().split(/\s+/, 1)[0]?.toLowerCase() ?? ""
  return /^[a-z0-9_+-]+$/.test(language) ? language : ""
}

function highlightedCode(source: string, language: string): string {
  return language !== "" && hljs.getLanguage(language)
    ? hljs.highlight(source, {
        language,
        ignoreIllegals: true,
      }).value
    : hljs.highlightAuto(source).value
}

function shouldDeferCode(source: string): boolean {
  return (
    new TextEncoder().encode(source).byteLength > DEFERRED_CODE_BYTES ||
    source.split("\n").length > DEFERRED_CODE_LINES
  )
}

export function createRenderer(
  plugins: readonly RendererPlugin[] = [],
): MarkdownIt {
  const engine = new MarkdownIt({
    html: true,
    linkify: true,
    breaks: false,
  })
    .use(footnote)
    .use(taskLists, { enabled: false })
    .use(anchor)
    .use(toc)
    .use(katex, {
      trust: false,
      throwOnError: false,
    })

  const renderUserHtml = (
    tokens: Parameters<NonNullable<MarkdownIt["renderer"]["rules"]["text"]>>[0],
    index: number,
  ) => {
    const content = tokens[index]?.content ?? ""
    return containsRendererInternalMarker(content)
      ? sanitizeUserProvidedHtml(content)
      : content
  }
  engine.renderer.rules.html_block = renderUserHtml
  engine.renderer.rules.html_inline = renderUserHtml

  engine.renderer.rules.fence = (tokens, index) => {
    const token = tokens[index]
    if (!token) return ""
    const language = normalizedLanguage(token.info)
    if (language === "mermaid") {
      const source = token.content.trim()
      if (/<\s*\/?\s*[a-z!]/i.test(source)) {
        return '<div class="mermaid-block mermaid-rejected"><span class="render-error">Mermaid 源包含不安全的 HTML，未渲染</span></div>'
      }
      const encoded = encodeURIComponent(
        source.replace(/[\uD800-\uDFFF]/g, "\uFFFD"),
      )
      return `<div class="mermaid-block" data-mermaid-source-encoded="${escapeHtml(encoded)}"></div>`
    }

    if (shouldDeferCode(token.content)) {
      const encoded = encodeURIComponent(
        token.content.replace(/[\uD800-\uDFFF]/g, "\uFFFD"),
      )
      return (
        `<pre class="deferred-code-block" ` +
        `data-code-source-encoded="${escapeHtml(encoded)}" ` +
        `data-code-language="${escapeHtml(language)}">` +
        "<code>代码将在可见时高亮</code></pre>"
      )
    }

    const highlighted = highlightedCode(token.content, language)
    const languageClass =
      language === "" ? "" : ` language-${escapeHtml(language)}`
    return `<pre><code class="hljs${languageClass}">${highlighted}</code></pre>`
  }

  plugins.forEach((plugin) => plugin(engine))
  return engine
}

export function renderMarkdown(source: string): string {
  return sanitizeMarkdownHtml(createRenderer().render(source))
}

export function hydrateDeferredCodeBlock(block: HTMLElement): boolean {
  const encoded = block.dataset.codeSourceEncoded
  const code = block.querySelector<HTMLElement>("code")
  if (encoded === undefined || !code) return false
  try {
    const source = decodeURIComponent(encoded)
    const language = normalizedLanguage(block.dataset.codeLanguage ?? "")
    code.className =
      language === "" ? "hljs" : `hljs language-${language}`
    code.innerHTML = highlightedCode(source, language)
    delete block.dataset.codeSourceEncoded
    delete block.dataset.codeLanguage
    block.classList.remove("deferred-code-block")
    return true
  } catch {
    code.className = "render-error"
    code.textContent = "代码块编码无效，无法高亮"
    return false
  }
}

export function observeDeferredCodeBlocks(root: HTMLElement): () => void {
  const blocks = [
    ...root.querySelectorAll<HTMLElement>(".deferred-code-block"),
  ]
  if (blocks.length === 0) return () => undefined
  if (!("IntersectionObserver" in window)) {
    blocks.forEach(hydrateDeferredCodeBlock)
    return () => undefined
  }

  const pending = new Set(blocks)
  const observer = new window.IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return
      const block = entry.target as HTMLElement
      observer.unobserve(block)
      pending.delete(block)
      hydrateDeferredCodeBlock(block)
    })
    if (pending.size === 0) observer.disconnect()
  })
  blocks.forEach((block) => observer.observe(block))
  return () => observer.disconnect()
}
