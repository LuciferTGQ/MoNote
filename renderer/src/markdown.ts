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
import type { DocumentHeading } from "./bridge"

export type RendererPlugin = (engine: MarkdownIt) => void
export interface RenderedMarkdownDocument {
  html: string
  headings: DocumentHeading[]
}
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

function normalizeHeadingTitle(value: string): string {
  return value.trim().replace(/\s+/g, " ")
}

function stableHeadingSlug(value: string): string {
  const normalized = normalizeHeadingTitle(value).toLocaleLowerCase()
  let hash = 0x811c9dc5
  for (let index = 0; index < normalized.length; index += 1) {
    hash ^= normalized.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }
  return `monote-heading-${(hash >>> 0).toString(16)}`
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
    .use(anchor, { slugify: stableHeadingSlug })
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
  return renderMarkdownDocument(source).html
}

export function renderMarkdownDocument(source: string): RenderedMarkdownDocument {
  const engine = createRenderer()
  const environment = {}
  const tokens = engine.parse(source, environment)
  const headings: DocumentHeading[] = []

  for (let index = 0; index < tokens.length && headings.length < 1_000; index += 1) {
    const opening = tokens[index]
    const inline = tokens[index + 1]
    if (opening?.type !== "heading_open" || inline?.type !== "inline") continue
    const level = Number.parseInt(opening.tag.slice(1), 10)
    const sourceLine = (opening.map?.[0] ?? -1) + 1
    const title = normalizeHeadingTitle(
      (inline.children ?? [])
        .filter((token) => !["html_inline", "image"].includes(token.type))
        .map((token) => token.content)
        .join(""),
    )
    const id = opening.attrGet("id") ?? ""
    if (
      title.length === 0 ||
      title.length > 512 ||
      id.length === 0 ||
      id.length > 256 ||
      level < 1 ||
      level > 6 ||
      sourceLine < 1
    ) {
      continue
    }
    headings.push({ id, title, level, sourceLine })
  }

  return {
    html: sanitizeMarkdownHtml(
      engine.renderer.render(tokens, engine.options, environment),
    ),
    headings,
  }
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
