import { describe, expect, it } from "vitest"

import { renderMarkdown } from "../src/markdown"
import {
  bindLocalImageFallbacks,
  isSafeRelativeImageSource,
} from "../src/security"

describe("Markdown security", () => {
  it("removes active tags, event handlers, and inline styles", () => {
    const html = renderMarkdown(
      [
        "<script>alert(1)</script>",
        '<iframe src="https://example.com"></iframe>',
        "<object data=x></object><embed src=x>",
        '<p style="background:url(https://example.com)" onclick="alert(1)">safe</p>',
      ].join(""),
    )

    expect(html).toContain("safe")
    expect(html).not.toMatch(/<(script|iframe|object|embed|style)\b/i)
    expect(html).not.toMatch(/\s(?:style|on[a-z]+)=/i)
  })

  it.each([
    "javascript:alert(1)",
    "JaVaScRiPt:alert(1)",
    "data:text/html,<script>alert(1)</script>",
    "data:image/svg+xml,<svg onload=alert(1)>",
    "file:///sdcard/secret",
    "content://other.app/secret",
  ])("blocks dangerous link URL %s", (url) => {
    const html = renderMarkdown(`<a href="${url}">unsafe</a>`)

    expect(html).not.toMatch(/\shref=/i)
    expect(html).not.toMatch(/javascript:|data:|file:|content:/i)
  })

  it("preserves safe local relative images for the native asset resolver", () => {
    const html = renderMarkdown("![diagram](assets/chapter-1/flow.png)")

    expect(html).toContain('<img src="assets/chapter-1/flow.png"')
    expect(html).toContain('loading="lazy"')
    expect(html).toContain('decoding="async"')
  })

  it("replaces a missing local image with a visible path placeholder", () => {
    const root = document.createElement("div")
    root.innerHTML = renderMarkdown("![diagram](assets/missing.png)")
    const dispose = bindLocalImageFallbacks(root)
    const image = root.querySelector("img")!

    image.dispatchEvent(new Event("error"))

    expect(root.querySelector("img")).toBeNull()
    expect(root.textContent).toContain("assets/missing.png")
    dispose()
  })

  it.each([
    "../secret.png",
    "/sdcard/secret.png",
    "\\\\server\\share\\secret.png",
    "//example.com/track.png",
  ])("blocks unsafe image path %s", (path) => {
    const html = renderMarkdown(`![unsafe](${path})`)

    expect(html).not.toContain("<img")
    expect(html).toContain("图片未加载")
  })

  it.each([
    "https://example.com/track.png",
    "http://example.com/track.png",
    "data:image/png;base64,AAAA",
  ])("never loads remote or embedded image %s", (url) => {
    const html = renderMarkdown(`<img src="${url}" alt="remote">`)

    expect(html).not.toContain("<img")
    expect(html).toContain("图片未加载")
  })

  it("preserves safe external links for click interception", () => {
    const html = renderMarkdown("[OpenAI](https://openai.com/docs)")

    expect(html).toContain('href="https://openai.com/docs"')
  })

  it("rejects arbitrarily nested encoded traversal and separators", () => {
    const encode = (value: string, count: number) => {
      let encoded = value
      for (let index = 0; index < count; index += 1) {
        encoded = encodeURIComponent(encoded)
      }
      return encoded
    }

    expect(isSafeRelativeImageSource(encode("../secret.png", 3))).toBe(false)
    expect(isSafeRelativeImageSource(encode("../secret.png", 12))).toBe(false)
    expect(isSafeRelativeImageSource(encode("..\\secret.png", 4))).toBe(false)
    expect(isSafeRelativeImageSource(encode("/secret.png", 4))).toBe(false)
  })

  it("removes protocol-relative links instead of allowing default navigation", () => {
    const html = renderMarkdown("[unsafe](//example.com/path)")
    expect(html).not.toMatch(/\shref=/i)
  })

  it.each(["\\\\evil.example\\path", "/\\evil.example/path"])(
    "removes backslash network-path link %s",
    (url) => {
      const html = renderMarkdown(`<a href="${url}">unsafe</a>`)
      expect(html).not.toMatch(/\shref=/i)
    },
  )
})
