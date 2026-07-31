export const NORMAL_PREVIEW_DELAY_MS = 250
export const LARGE_PREVIEW_DELAY_MS = 1_000
export const LARGE_DOCUMENT_BYTES = 2 * 1024 * 1024
export const MANUAL_PREVIEW_BYTES = 5 * 1024 * 1024

export type ScheduleResult = "scheduled" | "throttled" | "manual" | "stale"
export type PreviewRenderer = (
  text: string,
  revision: number,
) => string | Promise<string>
export type PreviewCommit = (html: string, revision: number) => void
export type PreviewError = (error: Error, revision: number) => void

export class PreviewScheduler {
  private latestRevision = -1
  private token = 0
  private timer: ReturnType<typeof setTimeout> | null = null
  private largeDocumentLivePreview = false

  constructor(
    private readonly render: PreviewRenderer,
    private readonly commit: PreviewCommit,
    private readonly onError: PreviewError = () => undefined,
  ) {}

  update(text: string, revision: number): ScheduleResult {
    if (revision < this.latestRevision) return "stale"
    this.latestRevision = revision
    this.cancelTimer()
    const bytes = utf8ByteLength(text, MANUAL_PREVIEW_BYTES)
    if (bytes > MANUAL_PREVIEW_BYTES && !this.largeDocumentLivePreview) {
      this.token += 1
      return "manual"
    }
    const throttled = bytes > LARGE_DOCUMENT_BYTES
    this.schedule(
      text,
      revision,
      throttled ? LARGE_PREVIEW_DELAY_MS : NORMAL_PREVIEW_DELAY_MS,
    )
    return throttled ? "throttled" : "scheduled"
  }

  refresh(text: string, revision: number): boolean {
    if (revision !== this.latestRevision) return false
    this.cancelTimer()
    this.schedule(text, revision, 0)
    return true
  }

  setLargeDocumentLivePreview(enabled: boolean): void {
    if (enabled === this.largeDocumentLivePreview) return
    this.largeDocumentLivePreview = enabled
    this.cancelTimer()
    this.token += 1
  }

  destroy(): void {
    this.cancelTimer()
    this.token += 1
  }

  private schedule(text: string, revision: number, delay: number): void {
    const token = ++this.token
    this.timer = setTimeout(() => {
      this.timer = null
      void Promise.resolve()
        .then(() => this.render(text, revision))
        .then((html) => {
          if (token === this.token && revision === this.latestRevision) {
            this.commit(html, revision)
          }
        })
        .catch((cause: unknown) => {
          if (token !== this.token || revision !== this.latestRevision) return
          const error =
            cause instanceof Error ? cause : new Error(String(cause))
          this.onError(error, revision)
        })
    }, delay)
  }

  private cancelTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer)
      this.timer = null
    }
  }
}
import { utf8ByteLength } from "./utf8"
