import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import {
  EDITOR_HISTORY_DEPTH,
  createEditor,
  formatSelection,
} from "../src/editor"
import {
  LARGE_DOCUMENT_BYTES,
  MANUAL_PREVIEW_BYTES,
  PreviewScheduler,
} from "../src/previewScheduler"

describe("editor", () => {
  let host: HTMLDivElement

  beforeEach(() => {
    host = document.createElement("div")
    document.body.append(host)
  })

  afterEach(() => {
    host.remove()
  })

  it("retains at least 500 history steps", () => {
    expect(EDITOR_HISTORY_DEPTH).toBe(500)
  })

  it("supports undo and redo without a native reload", () => {
    const editor = createEditor(host, "note", () => undefined)
    editor.replaceSelection("!")

    expect(editor.text).toBe("!note")
    expect(editor.canUndo).toBe(true)
    expect(editor.execute("undo")).toBe(true)
    expect(editor.text).toBe("note")
    expect(editor.canRedo).toBe(true)
    expect(editor.execute("redo")).toBe(true)
    expect(editor.text).toBe("!note")
    editor.destroy()
  })

  it("formats selected text with Markdown commands", () => {
    expect(formatSelection("learn", 0, 5, "bold")).toMatchObject({
      text: "**learn**",
      anchor: 2,
      head: 7,
    })
    expect(formatSelection("title", 0, 0, "heading").text).toBe("# title")
    expect(formatSelection("site", 0, 4, "link").text).toBe(
      "[site](https://)",
    )
    expect(formatSelection("", 0, 0, "image").text).toBe("![图片](assets/)")
  })

  it("rejects a same-revision overwrite and clears history for a newer load", () => {
    const editor = createEditor(host, "", () => undefined)

    expect(editor.load("first", 1)).toBe(true)
    editor.replaceSelection("!")
    expect(editor.canUndo).toBe(true)
    expect(editor.load("stale body", editor.revision)).toBe(false)
    expect(editor.text).toBe("!first")

    expect(editor.load("second", editor.revision + 1)).toBe(true)
    expect(editor.text).toBe("second")
    expect(editor.canUndo).toBe(false)
    expect(editor.execute("undo")).toBe(false)
    expect(editor.text).toBe("second")
    editor.destroy()
  })

  it("clears redo history when a newer document has identical text", () => {
    const editor = createEditor(host, "same", () => undefined)

    expect(editor.load("same", 1)).toBe(true)
    editor.replaceSelection("!")
    expect(editor.execute("undo")).toBe(true)
    expect(editor.text).toBe("same")
    expect(editor.canRedo).toBe(true)

    expect(editor.load("same", editor.revision + 1)).toBe(true)
    expect(editor.canRedo).toBe(false)
    expect(editor.execute("redo")).toBe(false)
    expect(editor.text).toBe("same")
    editor.destroy()
  })

  it("coalesces rapid changes for a large document before serializing text", async () => {
    vi.useFakeTimers()
    const onChange = vi.fn()
    const editor = createEditor(host, "01234567890", onChange, {
      largeDocumentBytes: 10,
      largeChangeDelayMs: 150,
    })

    editor.replaceSelection("a")
    editor.replaceSelection("b")
    editor.replaceSelection("c")
    expect(onChange).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(149)
    expect(onChange).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(onChange).toHaveBeenCalledOnce()
    expect(onChange.mock.calls[0]?.[0].text).toBe("abc01234567890")
    editor.destroy()
    vi.useRealTimers()
  })

  it("uses UTF-8 bytes when coalescing changes in a multilingual document", async () => {
    vi.useFakeTimers()
    const onChange = vi.fn()
    const editor = createEditor(host, "墨笺笔记", onChange, {
      largeDocumentBytes: 10,
      largeChangeDelayMs: 150,
    })

    editor.replaceSelection("新")
    expect(onChange).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(150)
    expect(onChange).toHaveBeenCalledOnce()
    expect(onChange.mock.calls[0]?.[0].text).toBe("新墨笺笔记")
    editor.destroy()
    vi.useRealTimers()
  })
})

describe("preview scheduling", () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it("debounces normal documents for 250 ms", async () => {
    const render = vi.fn((text: string) => `<p>${text}</p>`)
    const commit = vi.fn()
    const scheduler = new PreviewScheduler(render, commit)

    expect(scheduler.update("note", 1)).toBe("scheduled")
    await vi.advanceTimersByTimeAsync(249)
    expect(render).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(render).toHaveBeenCalledWith("note", 1)
    expect(commit).toHaveBeenCalledWith("<p>note</p>", 1)
  })

  it("throttles documents from over 2 MB through 5 MB", async () => {
    const render = vi.fn(() => "large")
    const scheduler = new PreviewScheduler(render, vi.fn())
    const text = "a".repeat(LARGE_DOCUMENT_BYTES + 1)

    expect(scheduler.update(text, 2)).toBe("throttled")
    await vi.advanceTimersByTimeAsync(999)
    expect(render).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(render).toHaveBeenCalledOnce()
  })

  it("requires explicit refresh above 5 MB", async () => {
    const render = vi.fn(() => "manual")
    const commit = vi.fn()
    const scheduler = new PreviewScheduler(render, commit)
    const text = "a".repeat(MANUAL_PREVIEW_BYTES + 1)

    expect(scheduler.update(text, 3)).toBe("manual")
    await vi.runAllTimersAsync()
    expect(render).not.toHaveBeenCalled()

    expect(scheduler.refresh(text, 3)).toBe(true)
    await vi.runAllTimersAsync()
    expect(render).toHaveBeenCalledOnce()
    expect(commit).toHaveBeenCalledWith("manual", 3)
  })

  it("keeps large-document live preview enabled after the user resumes it", async () => {
    const render = vi.fn(() => "live")
    const scheduler = new PreviewScheduler(render, vi.fn())
    const first = "a".repeat(MANUAL_PREVIEW_BYTES + 1)
    const second = `${first}b`

    expect(scheduler.update(first, 4)).toBe("manual")
    scheduler.setLargeDocumentLivePreview(true)
    expect(scheduler.update(first, 4)).toBe("throttled")
    await vi.advanceTimersByTimeAsync(1_000)
    expect(render).toHaveBeenCalledTimes(1)

    expect(scheduler.update(second, 5)).toBe("throttled")
    await vi.advanceTimersByTimeAsync(1_000)
    expect(render).toHaveBeenCalledTimes(2)
  })

  it("drops stale revisions and late render results", async () => {
    const resolves = new Map<number, (value: string) => void>()
    const render = vi.fn(
      (_text: string, revision: number) =>
        new Promise<string>((resolve) => resolves.set(revision, resolve)),
    )
    const commit = vi.fn()
    const scheduler = new PreviewScheduler(render, commit)

    scheduler.update("first", 1)
    await vi.advanceTimersByTimeAsync(250)
    scheduler.update("second", 2)
    await vi.advanceTimersByTimeAsync(250)
    expect(scheduler.update("old", 1)).toBe("stale")

    resolves.get(1)?.("old")
    await Promise.resolve()
    expect(commit).not.toHaveBeenCalled()
    resolves.get(2)?.("new")
    await Promise.resolve()
    await Promise.resolve()
    expect(commit).toHaveBeenCalledWith("new", 2)
  })

  it("reports synchronous render failures and continues with a later revision", async () => {
    const render = vi
      .fn<(text: string) => string>()
      .mockImplementationOnce(() => {
        throw new Error("sync failure")
      })
      .mockReturnValueOnce("recovered")
    const commit = vi.fn()
    const onError = vi.fn()
    const scheduler = new PreviewScheduler(render, commit, onError)

    scheduler.update("first", 1)
    await vi.advanceTimersByTimeAsync(250)
    expect(onError).toHaveBeenCalledWith(expect.any(Error), 1)

    scheduler.update("second", 2)
    await vi.advanceTimersByTimeAsync(250)
    expect(commit).toHaveBeenCalledWith("recovered", 2)
  })

  it("reports rejected renders without producing an unhandled rejection", async () => {
    const error = new Error("async failure")
    const onError = vi.fn()
    const scheduler = new PreviewScheduler(
      () => Promise.reject(error),
      vi.fn(),
      onError,
    )

    scheduler.update("note", 1)
    await vi.advanceTimersByTimeAsync(250)
    await Promise.resolve()

    expect(onError).toHaveBeenCalledWith(error, 1)
  })
})
