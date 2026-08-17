import { describe, expect, it } from "vitest"

import {
  DEFERRED_CODE_BYTES,
  createRenderer,
  hydrateDeferredCodeBlock,
  renderMarkdown,
  renderMarkdownDocument,
} from "../src/markdown"

describe("Markdown rendering", () => {
  it("renders GFM tables and task lists", () => {
    const html = renderMarkdown(
      "| a | b |\n| - | - |\n| 1 | 2 |\n\n- [x] learned\n\n~~old~~",
    )

    expect(html).toContain("<table>")
    expect(html).toContain('type="checkbox"')
    expect(html).toContain("checked")
    expect(html).toContain("<s>old</s>")
  })

  it("extracts stable ATX and Setext headings while ignoring fenced code", () => {
    const source = [
      "# 第一章",
      "",
      "第一章",
      "------",
      "",
      "```md",
      "# 不是标题",
      "```",
      "",
      "### **小节**",
    ].join("\n")

    const first = renderMarkdownDocument(source)
    const second = renderMarkdownDocument(source)

    expect(first.headings).toHaveLength(3)
    expect(first.headings.map((heading) => heading.title)).toEqual([
      "第一章",
      "第一章",
      "小节",
    ])
    expect(first.headings.map((heading) => heading.level)).toEqual([1, 2, 3])
    expect(first.headings.map((heading) => heading.sourceLine)).toEqual([1, 3, 10])
    expect(new Set(first.headings.map((heading) => heading.id)).size).toBe(3)
    expect(second.headings).toEqual(first.headings)
    first.headings.forEach((heading) => {
      expect(first.html).toContain(`id="${heading.id}"`)
    })
  })

  it("renders a table of contents, footnotes, and trusted-off KaTeX", () => {
    const html = renderMarkdown(
      "# 墨笺\n\n[[toc]]\n\n## 复习\n\n$x^2$\n\n内容[^1]\n\n[^1]: 脚注",
    )

    expect(html).toContain('class="table-of-contents"')
    expect(html).toContain("katex")
    expect(html).toContain("footnote")
  })

  it("keeps invalid math local instead of throwing", () => {
    expect(() => renderMarkdown("$\\notARealCommand{$")).not.toThrow()
  })

  it("creates a safe Mermaid placeholder without rendering SVG", () => {
    const html = renderMarkdown("~~~mermaid\ngraph TD;A-->B\n~~~")
    const wrapper = document.createElement("div")
    wrapper.innerHTML = html
    const placeholder = wrapper.querySelector<HTMLElement>(".mermaid-block")

    expect(placeholder?.dataset.mermaidSource).toBe("graph TD;A-->B")
    expect(html).not.toContain("<svg")
  })

  it("does not turn HTML-like Mermaid labels into active content", () => {
    const html = renderMarkdown(
      "~~~mermaid\ngraph TD;A[<img src=x onerror=alert(1)>]-->B\n~~~",
    )

    expect(html).toContain('class="mermaid-block mermaid-rejected"')
    expect(html).toContain("Mermaid 源包含不安全的 HTML")
    expect(html).not.toContain("data-mermaid-source=")
    expect(html).not.toContain("<img")
    expect(html).not.toMatch(/\sonerror=/)
  })

  it("highlights fenced code without turning source into active HTML", () => {
    const html = renderMarkdown(
      "~~~html\n<img src=x onerror=alert(1)>\n<script>alert(1)</script>\n~~~",
    )

    const wrapper = document.createElement("div")
    wrapper.innerHTML = html
    const code = wrapper.querySelector("code.hljs")
    expect(code?.textContent).toBe(
      "<img src=x onerror=alert(1)>\n<script>alert(1)</script>\n",
    )
    expect(wrapper.querySelector("img, script")).toBeNull()
    expect(
      [...wrapper.querySelectorAll("*")].some((element) =>
        [...element.attributes].some((attribute) =>
          attribute.name.toLowerCase().startsWith("on"),
        ),
      ),
    ).toBe(false)
  })

  it("defers long code highlighting until the block is hydrated", () => {
    const source = `<img src=x onerror=alert(1)>\n${"a".repeat(DEFERRED_CODE_BYTES)}`
    const html = renderMarkdown(`~~~html\n${source}\n~~~`)
    const wrapper = document.createElement("div")
    wrapper.innerHTML = html
    const block = wrapper.querySelector<HTMLElement>(".deferred-code-block")

    expect(block).not.toBeNull()
    expect(block?.querySelector("code")?.textContent).toContain("代码将在可见时高亮")

    expect(hydrateDeferredCodeBlock(block!)).toBe(true)
    expect(block?.querySelector("code")?.textContent).toContain("<img src=x")
    expect(block?.querySelector("img, script")).toBeNull()
  })

  it("does not leak plugins between renderer instances", () => {
    const customized = createRenderer([
      (engine) => {
        engine.renderer.rules.text = (tokens, index) =>
          `plugin:${engine.utils.escapeHtml(tokens[index]?.content ?? "")}`
      },
    ])
    const normal = createRenderer()

    expect(customized.render("hello")).toContain("plugin:hello")
    expect(normal.render("hello")).not.toContain("plugin:")
  })

  it("does not let raw HTML forge renderer-internal lazy markers", () => {
    const html = renderMarkdown(`
<div class="mermaid-block" data-mermaid-source="graph TD;A-->B"></div>
<pre class="deferred-code-block" data-code-source-encoded="%3Cscript%3E">
  <code>forged</code>
</pre>
`)

    expect(html).not.toContain("mermaid-block")
    expect(html).not.toContain("deferred-code-block")
    expect(html).not.toMatch(/data-(?:mermaid|code)-/i)
  })

  it("decodes entities before removing renderer-internal marker classes", () => {
    const html = renderMarkdown(
      '<div class="mermaid&#45;block">forged</div>',
    )

    expect(html).toContain("forged")
    expect(html).not.toContain("mermaid-block")
  })

  it("preserves the structure of safe inline HTML", () => {
    const html = renderMarkdown('A <span class="x">B</span> C')

    expect(html).toContain('<span class="x">B</span>')
  })
})
