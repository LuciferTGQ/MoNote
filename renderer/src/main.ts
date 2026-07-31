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
  renderMarkdown,
} from "./markdown"
import { observeMermaidBlocks } from "./mermaid"
import {
  PreviewScheduler,
  type ScheduleResult,
} from "./previewScheduler"
import { bindLocalImageFallbacks } from "./security"

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
  (text) => renderMarkdown(text),
  (html, revision) => {
    disposeMermaid()
    disposeDeferredCode()
    previewPane.innerHTML = html
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

editor = createEditor(editorPane, "", (change) => {
  channel.send({ type: "changed", ...change })
  showScheduleState(scheduler.update(change.text, change.revision))
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
  }
}

channel = createNativeChannel(receiveNativeMessage)
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
    scheduler.destroy()
    editor.destroy()
    channel.dispose()
  },
  { once: true },
)

applyPresentation("edit", "light")
channel.send({ type: "ready" })
