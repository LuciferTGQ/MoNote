import {
  hasExplicitOrProtocolRelativeScheme,
  normalizeExternalHttpUrl,
} from "./urlPolicy"

export type EditorMode = "edit" | "preview" | "split" | "read"
export type EditorTheme = "light" | "dark"
export type EditorCommand =
  | "undo"
  | "redo"
  | "bold"
  | "italic"
  | "heading"
  | "link"
  | "image"
  | "strike"
  | "bulletList"
  | "taskList"
  | "quote"
  | "code"
  | "table"
  | "math"
  | "mermaid"

export type NativeMessage =
  | {
      type: "load"
      revision: number
      text: string
      mode: EditorMode
      theme: EditorTheme
    }
  | { type: "command"; name: EditorCommand }
  | { type: "setMode"; mode: EditorMode }
  | { type: "setSplitRatio"; ratio: number }
  | { type: "setFontSize"; pixels: number }
  | { type: "refreshPreview"; revision: number }
  | {
      type: "setPreviewPolicy"
      largeDocument: "manual" | "live"
    }
  | {
      type: "searchDocument"
      query: string
      action: "reset" | "next" | "previous"
    }
  | { type: "navigateToHeading"; headingId: string }
  | {
      type: "restoreReadingPosition"
      editorLine: number
      editorColumn: number
      editorProgress: number
      previewHeadingId: string | null
      previewProgress: number
    }

export interface DocumentHeading {
  id: string
  title: string
  level: number
  sourceLine: number
}

export type WebMessage =
  | { type: "ready" }
  | {
      type: "changed"
      revision: number
      text: string
      canUndo: boolean
      canRedo: boolean
    }
  | { type: "externalLink"; href: string }
  | { type: "renderError"; block: string; message: string }
  | { type: "outlineChanged"; headings: DocumentHeading[] }
  | { type: "searchResult"; current: number; total: number }
  | { type: "activeHeadingChanged"; headingId: string | null }
  | {
      type: "readingPositionChanged"
      editorLine: number
      editorColumn: number
      editorProgress: number
      previewHeadingId: string | null
      previewProgress: number
    }
  | { type: "previewTapped" }

const modes = new Set<EditorMode>(["edit", "preview", "split", "read"])
const themes = new Set<EditorTheme>(["light", "dark"])
const commands = new Set<EditorCommand>([
  "undo",
  "redo",
  "bold",
  "italic",
  "heading",
  "link",
  "image",
  "strike",
  "bulletList",
  "taskList",
  "quote",
  "code",
  "table",
  "math",
  "mermaid",
])

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function hasExactKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
): boolean {
  const actual = Object.keys(value).sort()
  return (
    actual.length === expected.length &&
    expected
      .slice()
      .sort()
      .every((key, index) => actual[index] === key)
  )
}

function isRevision(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0
}

function isBoundedInteger(value: unknown, minimum: number, maximum: number): value is number {
  return Number.isSafeInteger(value) && (value as number) >= minimum && (value as number) <= maximum
}

function isProgress(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1
}

function isHeadingId(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0 && value.length <= 256 && !/[\u0000-\u001f\u007f]/.test(value)
}

function isHeading(value: unknown): value is DocumentHeading {
  return isRecord(value) &&
    hasExactKeys(value, ["id", "title", "level", "sourceLine"]) &&
    isHeadingId(value.id) &&
    typeof value.title === "string" &&
    value.title.trim().length > 0 &&
    value.title.length <= 512 &&
    !/[\u0000-\u001f\u007f]/.test(value.title) &&
    isBoundedInteger(value.level, 1, 6) &&
    isBoundedInteger(value.sourceLine, 1, 10_000_000)
}

function isReadingPosition(value: Record<string, unknown>): boolean {
  return isBoundedInteger(value.editorLine, 1, 10_000_000) &&
    isBoundedInteger(value.editorColumn, 0, 10_000_000) &&
    isProgress(value.editorProgress) &&
    (value.previewHeadingId === null || isHeadingId(value.previewHeadingId)) &&
    isProgress(value.previewProgress)
}

function decodeJson(raw: string): unknown {
  try {
    return JSON.parse(raw) as unknown
  } catch {
    return null
  }
}

export function parseNativeMessage(raw: string): NativeMessage | null {
  const value = decodeJson(raw)
  if (!isRecord(value) || typeof value.type !== "string") return null

  switch (value.type) {
    case "load":
      return hasExactKeys(value, [
        "type",
        "revision",
        "text",
        "mode",
        "theme",
      ]) &&
        isRevision(value.revision) &&
        typeof value.text === "string" &&
        modes.has(value.mode as EditorMode) &&
        themes.has(value.theme as EditorTheme)
        ? (value as NativeMessage)
        : null
    case "command":
      return hasExactKeys(value, ["type", "name"]) &&
        commands.has(value.name as EditorCommand)
        ? (value as NativeMessage)
        : null
    case "setMode":
      return hasExactKeys(value, ["type", "mode"]) &&
        modes.has(value.mode as EditorMode)
        ? (value as NativeMessage)
        : null
    case "setSplitRatio":
      return hasExactKeys(value, ["type", "ratio"]) &&
        typeof value.ratio === "number" &&
        Number.isFinite(value.ratio) &&
        value.ratio >= 0.25 &&
        value.ratio <= 0.75
        ? (value as NativeMessage)
        : null
    case "setFontSize":
      return hasExactKeys(value, ["type", "pixels"]) &&
        typeof value.pixels === "number" &&
        [14, 16, 20].includes(value.pixels)
        ? (value as NativeMessage)
        : null
    case "refreshPreview":
      return hasExactKeys(value, ["type", "revision"]) &&
        isRevision(value.revision)
        ? (value as NativeMessage)
        : null
    case "setPreviewPolicy":
      return hasExactKeys(value, ["type", "largeDocument"]) &&
        (value.largeDocument === "manual" || value.largeDocument === "live")
        ? (value as NativeMessage)
        : null
    case "searchDocument":
      return hasExactKeys(value, ["type", "query", "action"]) &&
        typeof value.query === "string" &&
        value.query.length <= 256 &&
        ["reset", "next", "previous"].includes(value.action as string)
        ? (value as NativeMessage)
        : null
    case "navigateToHeading":
      return hasExactKeys(value, ["type", "headingId"]) &&
        isHeadingId(value.headingId)
        ? (value as NativeMessage)
        : null
    case "restoreReadingPosition":
      return hasExactKeys(value, [
        "type",
        "editorLine",
        "editorColumn",
        "editorProgress",
        "previewHeadingId",
        "previewProgress",
      ]) && isReadingPosition(value)
        ? (value as NativeMessage)
        : null
    default:
      return null
  }
}

export function isWebMessage(value: unknown): value is WebMessage {
  if (!isRecord(value) || typeof value.type !== "string") return false
  switch (value.type) {
    case "ready":
      return hasExactKeys(value, ["type"])
    case "changed":
      return (
        hasExactKeys(value, [
          "type",
          "revision",
          "text",
          "canUndo",
          "canRedo",
        ]) &&
        isRevision(value.revision) &&
        typeof value.text === "string" &&
        typeof value.canUndo === "boolean" &&
        typeof value.canRedo === "boolean"
      )
    case "externalLink":
      return (
        hasExactKeys(value, ["type", "href"]) &&
        typeof value.href === "string" &&
        /^https?:\/\//i.test(value.href)
      )
    case "renderError":
      return (
        hasExactKeys(value, ["type", "block", "message"]) &&
        typeof value.block === "string" &&
        typeof value.message === "string"
      )
    case "outlineChanged": {
      if (!hasExactKeys(value, ["type", "headings"]) || !Array.isArray(value.headings) || value.headings.length > 1000) return false
      if (!value.headings.every(isHeading)) return false
      const ids = new Set(value.headings.map((heading) => heading.id))
      return ids.size === value.headings.length
    }
    case "searchResult":
      return hasExactKeys(value, ["type", "current", "total"]) &&
        isBoundedInteger(value.total, 0, 1000) &&
        ((value.total === 0 && value.current === 0) ||
          ((value.total as number) > 0 && isBoundedInteger(value.current, 1, value.total as number)))
    case "activeHeadingChanged":
      return hasExactKeys(value, ["type", "headingId"]) &&
        (value.headingId === null || isHeadingId(value.headingId))
    case "readingPositionChanged":
      return hasExactKeys(value, [
        "type",
        "editorLine",
        "editorColumn",
        "editorProgress",
        "previewHeadingId",
        "previewProgress",
      ]) && isReadingPosition(value)
    case "previewTapped":
      return hasExactKeys(value, ["type"])
    default:
      return false
  }
}

export function encodeWebMessage(message: WebMessage): string {
  if (!isWebMessage(message)) {
    throw new TypeError("Invalid web bridge message")
  }
  return JSON.stringify(message)
}

export type SendWebMessage = (message: WebMessage) => void

export function bindExternalLinkBridge(
  root: HTMLElement,
  send: SendWebMessage,
): () => void {
  const onClick = (event: MouseEvent) => {
    if (event.defaultPrevented) return
    const target = event.target
    const anchor =
      target instanceof Element ? target.closest<HTMLAnchorElement>("a") : null
    if (anchor === null) return
    const href = anchor?.getAttribute("href") ?? ""
    const normalized = normalizeExternalHttpUrl(href)
    if (
      normalized === null &&
      !hasExplicitOrProtocolRelativeScheme(href)
    ) {
      return
    }
    event.preventDefault()
    if (
      normalized !== null &&
      event.button === 0 &&
      !event.altKey &&
      !event.ctrlKey &&
      !event.metaKey &&
      !event.shiftKey
    ) {
      send({ type: "externalLink", href: normalized })
    }
  }
  root.addEventListener("click", onClick)
  return () => root.removeEventListener("click", onClick)
}

export interface NativeChannel {
  send(message: WebMessage): void
  dispose(): void
}

export interface NativeChannelOptions {
  trustedOrigin?: string
  trustedSource?: MessageEventSource | null
  allowTrustedEmptyOrigin?: boolean
}

export function createNativeChannel(
  onMessage: (message: NativeMessage) => void,
  options: NativeChannelOptions = {},
): NativeChannel {
  let port: MessagePort | null = null
  const pending: string[] = []
  const trustedOrigin = options.trustedOrigin ?? window.location.origin
  const trustedSource =
    options.trustedSource === undefined ? window : options.trustedSource
  const allowTrustedEmptyOrigin = options.allowTrustedEmptyOrigin ?? false

  const receive = (value: unknown) => {
    if (typeof value !== "string") return
    const message = parseNativeMessage(value)
    message && onMessage(message)
  }
  const onPortMessage = (event: MessageEvent<unknown>) => receive(event.data)
  const onWindowMessage = (event: MessageEvent<unknown>) => {
    const hasTrustedOrigin =
      event.origin === trustedOrigin ||
      (allowTrustedEmptyOrigin &&
        event.origin === "" &&
        event.isTrusted &&
        event.source === null &&
        event.ports.length === 1)
    if (!hasTrustedOrigin || event.source !== trustedSource) return
    const transferredPort = event.ports[0]
    if (transferredPort && port === null) {
      port = transferredPort
      port.addEventListener("message", onPortMessage)
      port.start()
      pending.splice(0).forEach((message) => port?.postMessage(message))
    }
    receive(event.data)
  }
  window.addEventListener("message", onWindowMessage)

  return {
    send(message) {
      const encoded = encodeWebMessage(message)
      if (port) {
        port.postMessage(encoded)
      } else {
        pending.push(encoded)
      }
    },
    dispose() {
      window.removeEventListener("message", onWindowMessage)
      port?.removeEventListener("message", onPortMessage)
      port?.close()
      port = null
      pending.length = 0
    },
  }
}
