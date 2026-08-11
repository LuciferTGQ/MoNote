import {
  defaultKeymap,
  history,
  historyKeymap,
  redo,
  redoDepth,
  undo,
  undoDepth,
} from "@codemirror/commands"
import { markdown } from "@codemirror/lang-markdown"
import { EditorSelection, EditorState, type Text } from "@codemirror/state"
import { EditorView, keymap } from "@codemirror/view"

import type { EditorCommand } from "./bridge"
import { utf8ByteLength } from "./utf8"

export const EDITOR_HISTORY_DEPTH = 500

type FormatCommand = Exclude<EditorCommand, "undo" | "redo">

export interface FormattedSelection {
  text: string
  anchor: number
  head: number
}

function wrapSelection(
  text: string,
  from: number,
  to: number,
  before: string,
  after: string,
  fallback: string,
): FormattedSelection {
  const selected = text.slice(from, to)
  const content = selected || fallback
  return {
    text:
      text.slice(0, from) + before + content + after + text.slice(to),
    anchor: from + before.length,
    head: from + before.length + content.length,
  }
}

export function formatSelection(
  text: string,
  from: number,
  to: number,
  command: FormatCommand,
): FormattedSelection {
  switch (command) {
    case "bold":
      return wrapSelection(text, from, to, "**", "**", "粗体")
    case "italic":
      return wrapSelection(text, from, to, "*", "*", "斜体")
    case "link":
      return wrapSelection(text, from, to, "[", "](https://)", "链接")
    case "image":
      return wrapSelection(text, from, to, "![", "](assets/)", "图片")
    case "strike":
      return wrapSelection(text, from, to, "~~", "~~", "删除线")
    case "code":
      return wrapSelection(text, from, to, "`", "`", "代码")
    case "bulletList":
    case "taskList":
    case "quote": {
      const prefix =
        command === "bulletList" ? "- " : command === "taskList" ? "- [ ] " : "> "
      const lineStart = text.lastIndexOf("\n", Math.max(0, from - 1)) + 1
      return {
        text: `${text.slice(0, lineStart)}${prefix}${text.slice(lineStart)}`,
        anchor: from + prefix.length,
        head: to + prefix.length,
      }
    }
    case "table":
      return wrapSelection(
        text,
        from,
        to,
        "\n| 列 1 | 列 2 |\n| --- | --- |\n| ",
        " | 内容 |\n",
        "内容",
      )
    case "math":
      return wrapSelection(text, from, to, "\n$$\n", "\n$$\n", "E = mc^2")
    case "mermaid":
      return wrapSelection(
        text,
        from,
        to,
        "\n```mermaid\n",
        "\n```\n",
        "graph TD\n  A --> B",
      )
    case "heading": {
      const lineStart = text.lastIndexOf("\n", Math.max(0, from - 1)) + 1
      return {
        text: `${text.slice(0, lineStart)}# ${text.slice(lineStart)}`,
        anchor: from + 2,
        head: to + 2,
      }
    }
  }
}

export interface EditorChange {
  revision: number
  text: string
  canUndo: boolean
  canRedo: boolean
}

export interface EditorController {
  readonly text: string
  readonly revision: number
  readonly canUndo: boolean
  readonly canRedo: boolean
  load(text: string, revision: number): boolean
  replaceSelection(text: string): void
  execute(command: EditorCommand): boolean
  focus(): void
  destroy(): void
}

export interface EditorOptions {
  largeDocumentBytes?: number
  largeChangeDelayMs?: number
}

const DEFAULT_LARGE_DOCUMENT_BYTES = 2 * 1024 * 1024
const DEFAULT_LARGE_CHANGE_DELAY_MS = 150

function textUtf8ByteLength(text: Text): number {
  let bytes = 0
  const iterator = text.iter()
  while (true) {
    iterator.next()
    if (iterator.done) return bytes
    bytes += utf8ByteLength(iterator.value)
  }
}

function textRangeUtf8ByteLength(
  text: Text,
  from: number,
  to: number,
): number {
  let bytes = 0
  const iterator = text.iterRange(from, to)
  while (true) {
    iterator.next()
    if (iterator.done) return bytes
    bytes += utf8ByteLength(iterator.value)
  }
}

export function createEditor(
  parent: HTMLElement,
  initialText: string,
  onChange: (change: EditorChange) => void,
  options: EditorOptions = {},
): EditorController {
  let revision = 0
  let hasNativeLoad = false
  let pendingChange: ReturnType<typeof setTimeout> | null = null
  let documentUtf8Bytes = utf8ByteLength(initialText)
  let view: EditorView
  const largeDocumentBytes =
    options.largeDocumentBytes ?? DEFAULT_LARGE_DOCUMENT_BYTES
  const largeChangeDelayMs =
    options.largeChangeDelayMs ?? DEFAULT_LARGE_CHANGE_DELAY_MS

  const cancelPendingChange = () => {
    if (pendingChange !== null) {
      clearTimeout(pendingChange)
      pendingChange = null
    }
  }
  const emitCurrentChange = () => {
    pendingChange = null
    onChange({
      revision,
      text: view.state.doc.toString(),
      canUndo: undoDepth(view.state) > 0,
      canRedo: redoDepth(view.state) > 0,
    })
  }
  const createState = (text: string) =>
    EditorState.create({
      doc: text,
      extensions: [
        markdown(),
        history({ minDepth: EDITOR_HISTORY_DEPTH }),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        EditorView.lineWrapping,
        EditorView.updateListener.of((update) => {
          if (!update.docChanged) return
          revision += 1
          update.changes.iterChanges(
            (fromA, toA, _fromB, _toB, inserted) => {
              documentUtf8Bytes -= textRangeUtf8ByteLength(
                update.startState.doc,
                fromA,
                toA,
              )
              documentUtf8Bytes += textUtf8ByteLength(inserted)
            },
          )
          if (documentUtf8Bytes > largeDocumentBytes) {
            cancelPendingChange()
            pendingChange = setTimeout(
              emitCurrentChange,
              largeChangeDelayMs,
            )
          } else {
            cancelPendingChange()
            emitCurrentChange()
          }
        }),
      ],
    })
  view = new EditorView({ state: createState(initialText), parent })

  const controller: EditorController = {
    get text() {
      return view.state.doc.toString()
    },
    get revision() {
      return revision
    },
    get canUndo() {
      return undoDepth(view.state) > 0
    },
    get canRedo() {
      return redoDepth(view.state) > 0
    },
    load(text, nextRevision) {
      if (nextRevision < revision) return false
      const currentText = view.state.doc.toString()
      if (hasNativeLoad && nextRevision === revision && text !== currentText) {
        return false
      }
      if (hasNativeLoad && nextRevision === revision) return true
      revision = nextRevision
      hasNativeLoad = true
      cancelPendingChange()
      documentUtf8Bytes = utf8ByteLength(text)
      view.setState(createState(text))
      return true
    },
    replaceSelection(text) {
      view.dispatch({
        ...view.state.replaceSelection(text),
        userEvent: "input.type",
      })
    },
    execute(command) {
      if (command === "undo") return undo(view)
      if (command === "redo") return redo(view)
      const selection = view.state.selection.main
      const formatted = formatSelection(
        view.state.doc.toString(),
        selection.from,
        selection.to,
        command,
      )
      view.dispatch({
        changes: {
          from: 0,
          to: view.state.doc.length,
          insert: formatted.text,
        },
        selection: EditorSelection.single(formatted.anchor, formatted.head),
        userEvent: "input",
      })
      view.focus()
      return true
    },
    focus() {
      view.focus()
    },
    destroy() {
      cancelPendingChange()
      view.destroy()
    },
  }
  return controller
}
