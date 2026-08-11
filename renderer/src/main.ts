import "katex/dist/katex.min.css"
import "highlight.js/styles/github.css"
import "./styles.css"

import {
  bindExternalLinkBridge,
  createNativeChannel,
  type EditorMode,
  type EditorTheme,
  type NativeChannel,
  type NativeMessage,
} from "./bridge"
import { createEditor, type EditorController } from "./editor"
import {
  observeDeferredCodeBlocks,
  renderMarkdownDocument,
  type RenderedMarkdownDocument,
} from "./markdown"
import { observeMermaidBlocks } from "./mermaid"
import {
  PreviewScheduler,
  type ScheduleResult,
} from "./previewScheduler"
import { bindLocalImageFallbacks } from "./security"
import { bindSplitScrollSync } from "./scrollSync"
import {
  PreviewReadingController,
  type PreviewReadingPosition,
} from "./reading"

function requiredElement<T extends Element>(
  parent: ParentNode,
  selector: string,
): T {
  const element = parent.querySelector<T>(selector)
  if (!element) throw new Error(`Renderer element is missing: ${selector}`)
  return element
}

const app = requiredElement<HTMLElement>(document, "#app")

app.innerHTML = `
  <section class="document-surface" data-mode="edit" data-theme="light">
    <div class="editor-pane" aria-label="Markdown 编辑器"></div>
    <article class="preview-pane markdown-body" aria-label="Markdown 预览"></article>
    <button class="manual-preview" type="button" hidden>刷新大文档预览</button>
    <p class="render-status" role="status" aria-live="polite"></p>
  </section>
`

const surface = requiredElement<HTMLElement>(app, ".document-surface")
const editorPane = requiredElement<HTMLElement>(app, ".editor-pane")
const previewPane = requiredElement<HTMLElement>(app, ".preview-pane")
const manualPreview = requiredElement<HTMLButtonElement>(
  app,
  ".manual-preview",
)
const renderStatus = requiredElement<HTMLElement>(app, ".render-status")

let channel: NativeChannel
let editor: EditorController
let disposeMermaid: () => void = () => undefined
let disposeDeferredCode: () => void = () => undefined
let renderedDocument: RenderedMarkdownDocument = { html: "", headings: [] }
let pendingRestore: NativeMessage & { type: "restoreReadingPosition" } | null = null
let searchQuery = ""
let lastActiveHeadingId: string | null = null
let readingPositionTimer: ReturnType<typeof setTimeout> | null = null

function currentMode(): EditorMode {
  const mode = surface.dataset.mode
  return mode === "preview" || mode === "split" || mode === "read"
    ? mode
    : "edit"
}

function sendReadingPosition(preview?: PreviewReadingPosition): void {
  const editorPosition = editor.readingPosition()
  const previewPosition = preview ?? previewReading.position()
  if (previewPosition.headingId !== lastActiveHeadingId) {
    lastActiveHeadingId = previewPosition.headingId
    channel.send({
      type: "activeHeadingChanged",
      headingId: previewPosition.headingId,
    })
  }
  channel.send({
    type: "readingPositionChanged",
    editorLine: editorPosition.line,
    editorColumn: editorPosition.column,
    editorProgress: editorPosition.progress,
    previewHeadingId: previewPosition.headingId,
    previewProgress: previewPosition.progress,
  })
}

function scheduleReadingPosition(): void {
  if (readingPositionTimer !== null) clearTimeout(readingPositionTimer)
  readingPositionTimer = setTimeout(() => {
    readingPositionTimer = null
    sendReadingPosition()
  }, 150)
}

const previewReading = new PreviewReadingController(previewPane, {
  onTap: () => channel.send({ type: "previewTapped" }),
  onPositionChange: (position) => sendReadingPosition(position),
})

function applyPresentation(mode: EditorMode, theme: EditorTheme): void {
  surface.dataset.mode = mode
  surface.dataset.theme = theme
  document.documentElement.dataset.theme = theme
  document.documentElement.style.colorScheme = theme
}

function showScheduleState(result: ScheduleResult): void {
  const manual = result === "manual"
  manualPreview.hidden = !manual
  renderStatus.textContent = manual
    ? "文档较大，预览已暂停。"
    : result === "throttled"
      ? "大文档预览将在内容稳定后更新。"
      : ""
}

const scheduler = new PreviewScheduler(
  (text) => {
    renderedDocument = renderMarkdownDocument(text)
    return renderedDocument.html
  },
  (html, revision) => {
    disposeMermaid()
    disposeDeferredCode()
    previewPane.innerHTML = html
    previewReading.contentChanged()
    disposeDeferredCode = observeDeferredCodeBlocks(previewPane)
    disposeMermaid = observeMermaidBlocks(previewPane, {
      revision,
      isCurrent: (candidate) => candidate === editor.revision,
      onError: (source, error) => {
        channel.send({
          type: "renderError",
          block: source,
          message: error.message,
        })
      },
    })
    manualPreview.hidden = true
    renderStatus.textContent = ""
    channel.send({ type: "outlineChanged", headings: renderedDocument.headings })
    if (pendingRestore !== null) {
      previewReading.restorePosition(
        pendingRestore.previewHeadingId,
        pendingRestore.previewProgress,
      )
      pendingRestore = null
    }
    if (searchQuery.length > 0 && ["preview", "read"].includes(currentMode())) {
      channel.send({
        type: "searchResult",
        ...previewReading.search(searchQuery, "reset"),
      })
    }
    sendReadingPosition()
  },
  (error) => {
    renderStatus.textContent = `预览失败：${error.message}`
    channel.send({
      type: "renderError",
      block: "document",
      message: error.message,
    })
  },
)

editor = createEditor(
  editorPane,
  "",
  (change) => {
    channel.send({ type: "changed", ...change })
    showScheduleState(scheduler.update(change.text, change.revision))
    scheduleReadingPosition()
  },
  { onPositionChange: scheduleReadingPosition },
)
const editorScroller = requiredElement<HTMLElement>(editorPane, ".cm-scroller")
editorScroller.addEventListener("scroll", scheduleReadingPosition, { passive: true })
const disposeScrollSync = bindSplitScrollSync(editorScroller, previewPane, {
  isEnabled: () => surface.dataset.mode === "split",
})

function receiveNativeMessage(message: NativeMessage): void {
  switch (message.type) {
    case "load":
      if (!editor.load(message.text, message.revision)) return
      applyPresentation(message.mode, message.theme)
      showScheduleState(scheduler.update(message.text, message.revision))
      return
    case "command":
      editor.execute(message.name)
      return
    case "setMode":
      applyPresentation(
        message.mode,
        surface.dataset.theme === "dark" ? "dark" : "light",
      )
      if (message.mode !== "edit") {
        showScheduleState(scheduler.update(editor.text, editor.revision))
      }
      if (searchQuery.length > 0) {
        const result = message.mode === "edit" || message.mode === "split"
          ? editor.search(searchQuery, "reset")
          : previewReading.search(searchQuery, "reset")
        channel.send({ type: "searchResult", ...result })
      }
      return
    case "setSplitRatio":
      surface.style.setProperty("--split-ratio", `${message.ratio * 100}%`)
      return
    case "setFontSize":
      surface.style.setProperty("--document-font-size", `${message.pixels}px`)
      return
    case "refreshPreview":
      if (scheduler.refresh(editor.text, message.revision)) {
        manualPreview.hidden = true
        renderStatus.textContent = "正在刷新预览…"
      }
      return
    case "setPreviewPolicy": {
      const live = message.largeDocument === "live"
      scheduler.setLargeDocumentLivePreview(live)
      showScheduleState(scheduler.update(editor.text, editor.revision))
      return
    }
    case "searchDocument": {
      searchQuery = message.query
      const mode = currentMode()
      const result = mode === "edit" || mode === "split"
        ? editor.search(message.query, message.action)
        : previewReading.search(message.query, message.action)
      channel.send({ type: "searchResult", ...result })
      scheduleReadingPosition()
      return
    }
    case "navigateToHeading": {
      const heading = renderedDocument.headings.find(
        (candidate) => candidate.id === message.headingId,
      )
      const mode = currentMode()
      if (heading && (mode === "edit" || mode === "split")) {
        const position = editor.readingPosition()
        editor.restoreReadingPosition(heading.sourceLine, 0, position.progress)
      }
      if (mode !== "edit") previewReading.navigateToHeading(message.headingId)
      sendReadingPosition()
      return
    }
    case "restoreReadingPosition":
      editor.restoreReadingPosition(
        message.editorLine,
        message.editorColumn,
        message.editorProgress,
      )
      pendingRestore = message
      if (previewPane.childElementCount > 0) {
        previewReading.restorePosition(
          message.previewHeadingId,
          message.previewProgress,
        )
        pendingRestore = null
      }
      scheduleReadingPosition()
      return
  }
}

channel = createNativeChannel(receiveNativeMessage, {
  trustedOrigin: window.location.origin,
  trustedSource: null,
  allowTrustedEmptyOrigin: true,
})
const disposeLinks = bindExternalLinkBridge(previewPane, (message) =>
  channel.send(message),
)
const disposeImages = bindLocalImageFallbacks(previewPane)

manualPreview.addEventListener("click", () => {
  if (scheduler.refresh(editor.text, editor.revision)) {
    manualPreview.hidden = true
    renderStatus.textContent = "正在刷新预览…"
  }
})

window.addEventListener(
  "pagehide",
  () => {
    disposeMermaid()
    disposeDeferredCode()
    disposeLinks()
    disposeImages()
    disposeScrollSync()
    editorScroller.removeEventListener("scroll", scheduleReadingPosition)
    previewReading.dispose()
    if (readingPositionTimer !== null) clearTimeout(readingPositionTimer)
    scheduler.destroy()
    editor.destroy()
    channel.dispose()
  },
  { once: true },
)

applyPresentation("edit", "light")
channel.send({ type: "ready" })
