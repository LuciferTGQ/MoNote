export interface ScrollSyncOptions {
  isEnabled: () => boolean
  now?: () => number
  pauseMs?: number
}

export function bindSplitScrollSync(
  editor: HTMLElement,
  preview: HTMLElement,
  options: ScrollSyncOptions,
): () => void {
  const now = options.now ?? Date.now
  const pauseMs = options.pauseMs ?? 1_500
  let directPane: HTMLElement | null = null
  let directUntil = 0
  let mirroring = false

  const markDirect = (pane: HTMLElement) => {
    directPane = pane
    directUntil = now() + pauseMs
  }
  const synchronize = (source: HTMLElement, target: HTMLElement) => {
    if (!options.isEnabled() || mirroring) return
    if (now() < directUntil && directPane !== null && directPane !== source) return
    const sourceRange = Math.max(0, source.scrollHeight - source.clientHeight)
    const targetRange = Math.max(0, target.scrollHeight - target.clientHeight)
    const progress = sourceRange === 0 ? 0 : source.scrollTop / sourceRange
    mirroring = true
    target.scrollTop = Math.max(0, Math.min(targetRange, progress * targetRange))
    queueMicrotask(() => {
      mirroring = false
    })
  }

  const editorDirect = () => markDirect(editor)
  const previewDirect = () => markDirect(preview)
  const editorScroll = () => synchronize(editor, preview)
  const previewScroll = () => synchronize(preview, editor)
  const directEvents = ["wheel", "touchstart", "pointerdown"] as const

  directEvents.forEach((event) => {
    editor.addEventListener(event, editorDirect, { passive: true })
    preview.addEventListener(event, previewDirect, { passive: true })
  })
  editor.addEventListener("scroll", editorScroll, { passive: true })
  preview.addEventListener("scroll", previewScroll, { passive: true })

  return () => {
    directEvents.forEach((event) => {
      editor.removeEventListener(event, editorDirect)
      preview.removeEventListener(event, previewDirect)
    })
    editor.removeEventListener("scroll", editorScroll)
    preview.removeEventListener("scroll", previewScroll)
  }
}
