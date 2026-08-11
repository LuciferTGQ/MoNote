import {
  hasExplicitOrProtocolRelativeScheme,
  normalizeExternalHttpUrl,
} from "./urlPolicy"

export type EditorMode = "edit" | "preview" | "split"
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

const modes = new Set<EditorMode>(["edit", "preview", "split"])
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
