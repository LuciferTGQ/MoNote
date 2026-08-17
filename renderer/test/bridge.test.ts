import { describe, expect, it, vi } from "vitest"

import {
  bindExternalLinkBridge,
  createNativeChannel,
  encodeWebMessage,
  parseNativeMessage,
  type WebMessage,
} from "../src/bridge"

describe("bridge protocol", () => {
  it("accepts each valid native message variant", () => {
    expect(
      parseNativeMessage(
        JSON.stringify({
          type: "load",
          revision: 4,
          text: "# 墨笺",
          mode: "split",
          theme: "dark",
        }),
      ),
    ).toEqual({
      type: "load",
      revision: 4,
      text: "# 墨笺",
      mode: "split",
      theme: "dark",
    })
    expect(
      parseNativeMessage(
        JSON.stringify({ type: "command", name: "undo" }),
      ),
    ).toEqual({ type: "command", name: "undo" })
    expect(
      parseNativeMessage(
        JSON.stringify({ type: "setMode", mode: "preview" }),
      ),
    ).toEqual({ type: "setMode", mode: "preview" })
    expect(
      parseNativeMessage(
        JSON.stringify({ type: "setSplitRatio", ratio: 0.6 }),
      ),
    ).toEqual({ type: "setSplitRatio", ratio: 0.6 })
    expect(
      parseNativeMessage(
        JSON.stringify({ type: "setFontSize", pixels: 20 }),
      ),
    ).toEqual({ type: "setFontSize", pixels: 20 })
    expect(
      parseNativeMessage(
        JSON.stringify({ type: "refreshPreview", revision: 4 }),
      ),
    ).toEqual({ type: "refreshPreview", revision: 4 })
    expect(
      parseNativeMessage(
        JSON.stringify({
          type: "setPreviewPolicy",
          largeDocument: "live",
        }),
      ),
    ).toEqual({ type: "setPreviewPolicy", largeDocument: "live" })
    expect(
      parseNativeMessage(
        JSON.stringify({
          type: "searchDocument",
          query: "复习",
          action: "next",
        }),
      ),
    ).toEqual({ type: "searchDocument", query: "复习", action: "next" })
    expect(
      parseNativeMessage(
        JSON.stringify({ type: "navigateToHeading", headingId: "heading-1" }),
      ),
    ).toEqual({ type: "navigateToHeading", headingId: "heading-1" })
    expect(
      parseNativeMessage(
        JSON.stringify({
          type: "restoreReadingPosition",
          editorLine: 8,
          editorColumn: 3,
          editorProgress: 0.25,
          previewHeadingId: "heading-1",
          previewProgress: 0.5,
        }),
      ),
    ).toEqual({
      type: "restoreReadingPosition",
      editorLine: 8,
      editorColumn: 3,
      editorProgress: 0.25,
      previewHeadingId: "heading-1",
      previewProgress: 0.5,
    })
    expect(
      parseNativeMessage(JSON.stringify({ type: "setMode", mode: "read" })),
    ).toEqual({ type: "setMode", mode: "read" })
  })

  it.each([
    "",
    "{",
    "null",
    "[]",
    '{"type":"eval","code":"alert(1)"}',
    '{"type":"load","revision":-1,"text":"","mode":"edit","theme":"light"}',
    '{"type":"load","revision":1.5,"text":"","mode":"edit","theme":"light"}',
    '{"type":"load","revision":1,"text":"","mode":"edit","theme":"light","extra":true}',
    '{"type":"command","name":"deleteAll"}',
    '{"type":"setMode","mode":"fullscreen"}',
    '{"type":"setSplitRatio","ratio":0.9}',
    '{"type":"setFontSize","pixels":17}',
    '{"type":"refreshPreview","revision":"4"}',
    '{"type":"setPreviewPolicy","largeDocument":"always"}',
    `{"type":"searchDocument","query":"${"x".repeat(257)}","action":"reset"}`,
    '{"type":"searchDocument","query":"x","action":"again"}',
    '{"type":"navigateToHeading","headingId":""}',
    '{"type":"restoreReadingPosition","editorLine":0,"editorColumn":0,"editorProgress":0,"previewHeadingId":null,"previewProgress":0}',
  ])("rejects malformed or unknown native message %s", (raw) => {
    expect(parseNativeMessage(raw)).toBeNull()
  })

  it("encodes only the documented web message shape", () => {
    const message: WebMessage = {
      type: "changed",
      revision: 7,
      text: "# 墨笺",
      canUndo: true,
      canRedo: false,
    }

    expect(encodeWebMessage(message)).toBe(
      '{"type":"changed","revision":7,"text":"# 墨笺","canUndo":true,"canRedo":false}',
    )
  })

  it("accepts bounded reading events from the renderer", () => {
    const messages: WebMessage[] = [
      {
        type: "outlineChanged",
        headings: [{ id: "heading-1", title: "第一章", level: 1, sourceLine: 1 }],
      },
      { type: "searchResult", current: 1, total: 3 },
      { type: "activeHeadingChanged", headingId: "heading-1" },
      {
        type: "readingPositionChanged",
        editorLine: 8,
        editorColumn: 3,
        editorProgress: 0.25,
        previewHeadingId: "heading-1",
        previewProgress: 0.5,
      },
      { type: "previewTapped" },
    ]

    messages.forEach((message) => {
      expect(() => encodeWebMessage(message)).not.toThrow()
    })
  })

  it("rejects invalid reading events from the renderer", () => {
    expect(() =>
      encodeWebMessage({
        type: "searchResult",
        current: 2,
        total: 1,
      }),
    ).toThrow()
    expect(() =>
      encodeWebMessage({
        type: "outlineChanged",
        headings: [
          { id: "same", title: "A", level: 1, sourceLine: 1 },
          { id: "same", title: "B", level: 2, sourceLine: 2 },
        ],
      }),
    ).toThrow()
  })

  it("prevents external navigation and emits a bridge event", () => {
    const root = document.createElement("div")
    root.innerHTML = '<a href="https://example.com/path">external</a>'
    document.body.append(root)
    const send = vi.fn()
    const dispose = bindExternalLinkBridge(root, send)
    const link = root.querySelector("a")!
    const click = new MouseEvent("click", {
      bubbles: true,
      cancelable: true,
      button: 0,
    })

    link.dispatchEvent(click)

    expect(click.defaultPrevented).toBe(true)
    expect(send).toHaveBeenCalledWith({
      type: "externalLink",
      href: "https://example.com/path",
    })
    dispose()
  })

  it("does not emit an external event for a relative link", () => {
    const root = document.createElement("div")
    root.innerHTML = '<a href="chapter-2.md">local</a>'
    const send = vi.fn()
    const dispose = bindExternalLinkBridge(root, send)
    const click = new MouseEvent("click", {
      bubbles: true,
      cancelable: true,
      button: 0,
    })

    root.querySelector("a")!.dispatchEvent(click)

    expect(click.defaultPrevented).toBe(false)
    expect(send).not.toHaveBeenCalled()
    dispose()
  })

  it("accepts window messages only from the trusted renderer window", () => {
    const receive = vi.fn()
    const channel = createNativeChannel(receive, {
      trustedOrigin: "https://appassets.androidplatform.net",
    })
    const message = JSON.stringify({
      type: "setMode",
      mode: "preview",
    })

    window.dispatchEvent(
      new MessageEvent("message", {
        data: message,
        origin: "https://evil.example",
        source: window,
      }),
    )
    window.dispatchEvent(
      new MessageEvent("message", {
        data: message,
        origin: "https://appassets.androidplatform.net",
        source: null,
      }),
    )
    expect(receive).not.toHaveBeenCalled()

    window.dispatchEvent(
      new MessageEvent("message", {
        data: message,
        origin: "https://appassets.androidplatform.net",
        source: window,
      }),
    )
    expect(receive).toHaveBeenCalledOnce()
    channel.dispose()
  })

  it("can reserve source-null messages for the Android native bootstrap", () => {
    const receive = vi.fn()
    const channel = createNativeChannel(receive, {
      trustedOrigin: "https://appassets.androidplatform.net",
      trustedSource: null,
    })
    const message = JSON.stringify({
      type: "setMode",
      mode: "preview",
    })

    window.dispatchEvent(
      new MessageEvent("message", {
        data: message,
        origin: "https://appassets.androidplatform.net",
        source: window,
      }),
    )
    expect(receive).not.toHaveBeenCalled()

    window.dispatchEvent(
      new MessageEvent("message", {
        data: message,
        origin: "https://appassets.androidplatform.net",
        source: null,
      }),
    )
    expect(receive).toHaveBeenCalledOnce()
    channel.dispose()
  })

  it("rejects a synthetic empty-origin Android bootstrap", () => {
    const receive = vi.fn()
    const channel = createNativeChannel(receive, {
      trustedOrigin: "https://appassets.androidplatform.net",
      trustedSource: null,
      allowTrustedEmptyOrigin: true,
    })
    const message = JSON.stringify({
      type: "setMode",
      mode: "preview",
    })

    window.dispatchEvent(
      new MessageEvent("message", {
        data: message,
        origin: "",
        source: null,
      }),
    )

    expect(receive).not.toHaveBeenCalled()
    channel.dispose()
  })

  it("binds the transferred native port only once", () => {
    const firstPort = {
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      start: vi.fn(),
      postMessage: vi.fn(),
      close: vi.fn(),
    } as unknown as MessagePort
    const replacementPort = {
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      start: vi.fn(),
      postMessage: vi.fn(),
      close: vi.fn(),
    } as unknown as MessagePort
    const channel = createNativeChannel(vi.fn(), {
      trustedOrigin: "https://appassets.androidplatform.net",
    })
    const bootstrap = {
      data: JSON.stringify({ type: "setMode", mode: "preview" }),
      origin: "https://appassets.androidplatform.net",
      source: window,
    }

    window.dispatchEvent(
      new MessageEvent("message", {
        ...bootstrap,
        ports: [firstPort],
      }),
    )
    window.dispatchEvent(
      new MessageEvent("message", {
        ...bootstrap,
        ports: [replacementPort],
      }),
    )
    channel.send({ type: "ready" })

    expect(firstPort.postMessage).toHaveBeenCalledWith('{"type":"ready"}')
    expect(replacementPort.addEventListener).not.toHaveBeenCalled()
    expect(replacementPort.postMessage).not.toHaveBeenCalled()
    channel.dispose()
  })

  it("normalizes obfuscated external links and always prevents WebView navigation", () => {
    const root = document.createElement("div")
    root.innerHTML = '<a href="h&#10;ttps://example.com/path">external</a>'
    const send = vi.fn()
    const dispose = bindExternalLinkBridge(root, send)
    const click = new MouseEvent("click", {
      bubbles: true,
      cancelable: true,
      button: 0,
    })

    root.querySelector("a")!.dispatchEvent(click)

    expect(click.defaultPrevented).toBe(true)
    expect(send).toHaveBeenCalledWith({
      type: "externalLink",
      href: "https://example.com/path",
    })
    dispose()
  })

  it.each([
    { button: 0, ctrlKey: true },
    { button: 1, ctrlKey: false },
  ])("prevents modified or non-primary external navigation %o", (init) => {
    const root = document.createElement("div")
    root.innerHTML = '<a href="https://example.com/path">external</a>'
    const send = vi.fn()
    const dispose = bindExternalLinkBridge(root, send)
    const click = new MouseEvent("click", {
      bubbles: true,
      cancelable: true,
      ...init,
    })

    root.querySelector("a")!.dispatchEvent(click)

    expect(click.defaultPrevented).toBe(true)
    expect(send).not.toHaveBeenCalled()
    dispose()
  })

  it.each(["\\\\evil.example\\path", "/\\evil.example/path"])(
    "prevents backslash network-path navigation %s",
    (href) => {
      const root = document.createElement("div")
      const link = document.createElement("a")
      link.setAttribute("href", href)
      root.append(link)
      const send = vi.fn()
      const dispose = bindExternalLinkBridge(root, send)
      const click = new MouseEvent("click", {
        bubbles: true,
        cancelable: true,
        button: 0,
      })

      link.dispatchEvent(click)

      expect(click.defaultPrevented).toBe(true)
      expect(send).not.toHaveBeenCalled()
      dispose()
    },
  )
})
