import { beforeEach, describe, expect, it, vi } from "vitest"

import {
  PreviewReadingController,
  findLiteralMatches,
  nextSearchIndex,
} from "../src/reading"

describe("literal document search", () => {
  it("matches case-insensitively, caps results, and wraps navigation", () => {
    expect(findLiteralMatches("Alpha alpha ALPHA", "alpha", 2)).toEqual([
      { from: 0, to: 5 },
      { from: 6, to: 11 },
    ])
    expect(nextSearchIndex(-1, 3, "reset")).toBe(0)
    expect(nextSearchIndex(2, 3, "next")).toBe(0)
    expect(nextSearchIndex(0, 3, "previous")).toBe(2)
    expect(nextSearchIndex(-1, 0, "next")).toBe(-1)
  })
})

describe("preview reading navigation", () => {
  let root: HTMLElement

  beforeEach(() => {
    root = document.createElement("article")
    root.innerHTML = `
      <h1 id="chapter-1">第一章</h1>
      <p>Alpha <strong>alpha</strong></p>
      <a href="https://example.com">Alpha link</a>
      <h2 id="chapter-2">第二章</h2>
    `
    document.body.replaceChildren(root)
    Element.prototype.scrollIntoView = vi.fn()
  })

  it("searches visible text, loops, and clears highlights", () => {
    const controller = new PreviewReadingController(root)

    expect(controller.search("alpha", "reset")).toEqual({ current: 1, total: 3 })
    expect(root.querySelectorAll("mark[data-monote-search]")).toHaveLength(3)
    expect(controller.search("alpha", "previous")).toEqual({ current: 3, total: 3 })
    expect(controller.search("alpha", "next")).toEqual({ current: 1, total: 3 })
    expect(controller.search("", "reset")).toEqual({ current: 0, total: 0 })
    expect(root.querySelector("mark[data-monote-search]")).toBeNull()
    expect(root.textContent).toContain("Alpha alpha")
    controller.dispose()
  })

  it("jumps to headings and restores by heading before scroll ratio", () => {
    const controller = new PreviewReadingController(root)
    const target = root.querySelector<HTMLElement>("#chapter-2")!

    expect(controller.navigateToHeading("chapter-2")).toBe(true)
    expect(target.scrollIntoView).toHaveBeenCalled()
    expect(controller.restorePosition("chapter-2", 0.9)).toBe(true)
    expect(controller.restorePosition("missing", 0.9)).toBe(false)
    controller.dispose()
  })

  it("reports plain preview taps without treating link clicks as control taps", () => {
    const onTap = vi.fn()
    const controller = new PreviewReadingController(root, { onTap })

    root.querySelector("p")!.dispatchEvent(new MouseEvent("click", { bubbles: true }))
    root.querySelector("a")!.dispatchEvent(new MouseEvent("click", { bubbles: true }))

    expect(onTap).toHaveBeenCalledOnce()
    controller.dispose()
  })
})
