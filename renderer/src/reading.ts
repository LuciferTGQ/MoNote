export type SearchAction = "reset" | "next" | "previous"

export interface TextMatch {
  from: number
  to: number
}

export interface SearchResult {
  current: number
  total: number
}

export interface PreviewReadingPosition {
  headingId: string | null
  progress: number
}

export interface PreviewReadingOptions {
  onTap?: () => void
  onPositionChange?: (position: PreviewReadingPosition) => void
}

const MAX_MATCHES = 1_000

export function findLiteralMatches(
  text: string,
  query: string,
  limit = MAX_MATCHES,
): TextMatch[] {
  if (query.length === 0 || limit <= 0) return []
  const haystack = text.toLocaleLowerCase()
  const needle = query.toLocaleLowerCase()
  if (needle.length === 0) return []
  const matches: TextMatch[] = []
  let from = 0
  while (matches.length < limit) {
    const index = haystack.indexOf(needle, from)
    if (index < 0) break
    matches.push({ from: index, to: index + query.length })
    from = index + Math.max(1, query.length)
  }
  return matches
}

export function nextSearchIndex(
  current: number,
  total: number,
  action: SearchAction,
): number {
  if (total === 0) return -1
  if (action === "reset" || current < 0 || current >= total) return 0
  return action === "next"
    ? (current + 1) % total
    : (current - 1 + total) % total
}

function isSearchableText(node: Text, root: HTMLElement): boolean {
  const parent = node.parentElement
  return parent !== null &&
    root.contains(parent) &&
    parent.closest("mark[data-monote-search], script, style, textarea") === null &&
    (node.nodeValue?.length ?? 0) > 0
}

function scrollRatio(element: HTMLElement): number {
  const maximum = Math.max(0, element.scrollHeight - element.clientHeight)
  return maximum === 0 ? 0 : Math.min(1, Math.max(0, element.scrollTop / maximum))
}

export class PreviewReadingController {
  private query = ""
  private marks: HTMLElement[] = []
  private current = -1
  private positionTimer: ReturnType<typeof setTimeout> | null = null
  private readonly onClick: (event: MouseEvent) => void
  private readonly onScroll: () => void

  constructor(
    private readonly root: HTMLElement,
    private readonly options: PreviewReadingOptions = {},
  ) {
    this.onClick = (event) => {
      const target = event.target
      if (
        target instanceof Element &&
        target.closest("a, button, input, textarea, select, label") !== null
      ) {
        return
      }
      this.options.onTap?.()
    }
    this.onScroll = () => {
      if (this.positionTimer !== null) clearTimeout(this.positionTimer)
      this.positionTimer = setTimeout(() => {
        this.positionTimer = null
        this.options.onPositionChange?.(this.position())
      }, 150)
    }
    root.addEventListener("click", this.onClick)
    root.addEventListener("scroll", this.onScroll, { passive: true })
  }

  search(query: string, action: SearchAction): SearchResult {
    if (action === "reset" || query !== this.query) this.rebuildSearch(query)
    this.current = nextSearchIndex(this.current, this.marks.length, action)
    this.marks.forEach((mark, index) => {
      if (index === this.current) mark.dataset.monoteSearchCurrent = "true"
      else delete mark.dataset.monoteSearchCurrent
    })
    this.marks[this.current]?.scrollIntoView?.({ block: "center" })
    return {
      current: this.current < 0 ? 0 : this.current + 1,
      total: this.marks.length,
    }
  }

  clearSearch(): void {
    this.rebuildSearch("")
  }

  contentChanged(): void {
    this.query = ""
    this.current = -1
    this.marks = []
  }

  navigateToHeading(headingId: string): boolean {
    const target = this.root.ownerDocument.getElementById(headingId)
    if (!(target instanceof HTMLElement) || !this.root.contains(target)) return false
    target.scrollIntoView?.({ block: "start" })
    return true
  }

  restorePosition(headingId: string | null, progress: number): boolean {
    if (headingId !== null && this.navigateToHeading(headingId)) return true
    const maximum = Math.max(0, this.root.scrollHeight - this.root.clientHeight)
    this.root.scrollTop = maximum * Math.min(1, Math.max(0, progress))
    return false
  }

  position(): PreviewReadingPosition {
    const rootTop = this.root.getBoundingClientRect().top
    const headings = [
      ...this.root.querySelectorAll<HTMLElement>("h1[id],h2[id],h3[id],h4[id],h5[id],h6[id]"),
    ]
    const active = headings.reduce<HTMLElement | null>((selected, heading) => {
      return heading.getBoundingClientRect().top <= rootTop + 32 ? heading : selected
    }, null)
    return { headingId: active?.id ?? null, progress: scrollRatio(this.root) }
  }

  dispose(): void {
    if (this.positionTimer !== null) clearTimeout(this.positionTimer)
    this.clearSearch()
    this.root.removeEventListener("click", this.onClick)
    this.root.removeEventListener("scroll", this.onScroll)
  }

  private rebuildSearch(query: string): void {
    this.marks.forEach((mark) => mark.replaceWith(mark.ownerDocument.createTextNode(mark.textContent ?? "")))
    this.root.normalize()
    this.query = query
    this.current = -1
    this.marks = []
    if (query.length === 0) return

    const walker = this.root.ownerDocument.createTreeWalker(this.root, NodeFilter.SHOW_TEXT)
    const nodes: Text[] = []
    let node = walker.nextNode()
    while (node !== null) {
      if (node instanceof Text && isSearchableText(node, this.root)) nodes.push(node)
      node = walker.nextNode()
    }
    for (const textNode of nodes) {
      if (this.marks.length >= MAX_MATCHES) break
      const value = textNode.nodeValue ?? ""
      const matches = findLiteralMatches(value, query, MAX_MATCHES - this.marks.length)
      if (matches.length === 0) continue
      const fragment = this.root.ownerDocument.createDocumentFragment()
      let cursor = 0
      matches.forEach((match) => {
        fragment.append(value.slice(cursor, match.from))
        const mark = this.root.ownerDocument.createElement("mark")
        mark.dataset.monoteSearch = "true"
        mark.textContent = value.slice(match.from, match.to)
        fragment.append(mark)
        this.marks.push(mark)
        cursor = match.to
      })
      fragment.append(value.slice(cursor))
      textNode.replaceWith(fragment)
    }
  }
}
