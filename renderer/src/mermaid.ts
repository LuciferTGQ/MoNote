import mermaid from "mermaid"

import { sanitizeMermaidSvg } from "./security"

export const MERMAID_CONFIG = Object.freeze({
  startOnLoad: false,
  securityLevel: "strict" as const,
  htmlLabels: false,
})

export interface MermaidRenderOptions {
  revision: number
  isCurrent: (revision: number) => boolean
  onError: (source: string, error: Error) => void
}

let diagramSequence = 0

export async function renderMermaidBlocks(
  root: HTMLElement,
  options: MermaidRenderOptions,
): Promise<void> {
  mermaid.initialize(MERMAID_CONFIG)
  const blocks = [...root.querySelectorAll<HTMLElement>(".mermaid-block")]
  for (const block of blocks) {
    await renderMermaidBlock(block, options)
  }
}

export async function renderMermaidBlock(
  block: HTMLElement,
  options: MermaidRenderOptions,
): Promise<void> {
  if (
    block.classList.contains("mermaid-rendered") ||
    !options.isCurrent(options.revision)
  ) {
    return
  }
  const source = block.dataset.mermaidSource ?? ""
  try {
    const id = `monote-mermaid-${options.revision}-${diagramSequence++}`
    const rendered = await mermaid.render(id, source)
    if (!options.isCurrent(options.revision)) return
    block.innerHTML = sanitizeMermaidSvg(rendered.svg)
    block.classList.add("mermaid-rendered")
  } catch (cause) {
    const error = cause instanceof Error ? cause : new Error(String(cause))
    block.replaceChildren(
      Object.assign(document.createElement("span"), {
        className: "render-error",
        textContent: `Mermaid 渲染失败：${error.message}`,
      }),
    )
    options.onError(source, error)
  }
}

export function observeMermaidBlocks(
  root: HTMLElement,
  options: MermaidRenderOptions,
): () => void {
  if (!("IntersectionObserver" in window)) {
    void renderMermaidBlocks(root, options)
    return () => undefined
  }

  mermaid.initialize(MERMAID_CONFIG)
  const blocks = [...root.querySelectorAll<HTMLElement>(".mermaid-block")]
  const pending = new Set(blocks)
  const observer = new window.IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return
      const block = entry.target as HTMLElement
      observer.unobserve(block)
      pending.delete(block)
      void renderMermaidBlock(block, options)
    })
    if (pending.size === 0) observer.disconnect()
  })
  blocks.forEach((block) => observer.observe(block))
  return () => observer.disconnect()
}
