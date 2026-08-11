import { describe, expect, it } from "vitest"

import { bindSplitScrollSync } from "../src/scrollSync"

function scrollPane(scrollHeight: number, clientHeight: number): HTMLElement {
  const pane = document.createElement("div")
  Object.defineProperty(pane, "scrollHeight", { value: scrollHeight })
  Object.defineProperty(pane, "clientHeight", { value: clientHeight })
  return pane
}

describe("landscape split scroll synchronization", () => {
  it("tracks relative position and lets the directly scrolled pane lead for 1.5 seconds", async () => {
    let currentTime = 0
    const editor = scrollPane(1_100, 100)
    const preview = scrollPane(2_100, 100)
    const dispose = bindSplitScrollSync(editor, preview, {
      isEnabled: () => true,
      now: () => currentTime,
    })

    preview.dispatchEvent(new Event("wheel"))
    preview.scrollTop = 1_000
    preview.dispatchEvent(new Event("scroll"))
    await Promise.resolve()
    expect(editor.scrollTop).toBe(500)

    editor.scrollTop = 800
    editor.dispatchEvent(new Event("scroll"))
    await Promise.resolve()
    expect(preview.scrollTop).toBe(1_000)

    currentTime = 1_501
    editor.dispatchEvent(new Event("scroll"))
    await Promise.resolve()
    expect(preview.scrollTop).toBe(1_600)
    dispose()
  })

  it("does nothing outside split mode", async () => {
    const editor = scrollPane(1_100, 100)
    const preview = scrollPane(2_100, 100)
    const dispose = bindSplitScrollSync(editor, preview, { isEnabled: () => false })

    editor.scrollTop = 500
    editor.dispatchEvent(new Event("scroll"))
    await Promise.resolve()

    expect(preview.scrollTop).toBe(0)
    dispose()
  })
})
