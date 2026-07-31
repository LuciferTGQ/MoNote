declare module "markdown-it-footnote" {
  import type MarkdownIt from "markdown-it"
  const plugin: (engine: MarkdownIt) => void
  export default plugin
}

declare module "markdown-it-task-lists" {
  import type MarkdownIt from "markdown-it"
  const plugin: (
    engine: MarkdownIt,
    options?: { enabled?: boolean; label?: boolean; labelAfter?: boolean },
  ) => void
  export default plugin
}

declare module "*.css"
