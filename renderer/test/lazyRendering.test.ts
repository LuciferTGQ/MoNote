import { afterEach, describe, expect, it, vi } from "vitest"

const { mermaidRender } = vi.hoisted(() => ({
  mermaidRender: vi.fn(async (_id: string, source: string) => ({
    svg: `<svg><text>${source}</text></svg>`,
  })),
}))

vi.mock("mermaid", () => ({
  default: {
    initialize: vi.fn(),
    render: mermaidRender,
  },
}))

import { observeMermaidBlocks } from "../src/mermaid"

type ObserverCallback = IntersectionObserverCallback

class FakeIntersectionObserver {
  static callback: ObserverCallback | null = null
  readonly observed: Element[] = []

  constructor(callback: ObserverCallback) {
    FakeIntersectionObserver.callback = callback
  }

  observe(target: Element): void {
    this.observed.push(target)
  }

  unobserve(target: Element): void {
    const index = this.observed.indexOf(target)
    if (index >= 0) this.observed.splice(index, 1)
  }

  disconnect(): void {
    this.observed.length = 0
  }
}

describe("viewport-aware rendering", () => {
  afterEach(() => {
    vi.restoreAllMocks()
    mermaidRender.mockClear()
    delete (window as { IntersectionObserver?: unknown }).IntersectionObserver
  })

  it("renders only the Mermaid block that becomes visible", async () => {
    Object.assign(window, { IntersectionObserver: FakeIntersectionObserver })
    const root = document.createElement("div")
    root.innerHTML = `
      <div class="mermaid-block" data-mermaid-source="graph TD;A--&gt;B"></div>
      <div class="mermaid-block" data-mermaid-source="graph TD;C--&gt;D"></div>
    `
    const blocks = [...root.querySelectorAll<HTMLElement>(".mermaid-block")]
    const dispose = observeMermaidBlocks(root, {
      revision: 1,
      isCurrent: () => true,
      onError: vi.fn(),
    })

    FakeIntersectionObserver.callback?.(
      [
        {
          target: blocks[0]!,
          isIntersecting: true,
          intersectionRatio: 1,
        } as unknown as IntersectionObserverEntry,
      ],
      {} as IntersectionObserver,
    )
    await Promise.resolve()
    await Promise.resolve()

    expect(mermaidRender).toHaveBeenCalledTimes(1)
    expect(mermaidRender.mock.calls[0]?.[1]).toBe("graph TD;A-->B")
    expect(blocks[0]?.classList.contains("mermaid-rendered")).toBe(true)
    expect(blocks[1]?.classList.contains("mermaid-rendered")).toBe(false)
    dispose()
  })
})
