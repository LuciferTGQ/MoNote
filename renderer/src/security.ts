import createDOMPurify from "dompurify"

import {
  compactUrlForPolicy,
  explicitScheme,
  hasExplicitOrProtocolRelativeScheme,
  normalizeExternalHttpUrl,
} from "./urlPolicy"

const FORBIDDEN_TAGS = [
  "script",
  "iframe",
  "object",
  "embed",
  "style",
  "base",
  "form",
]

const FORBIDDEN_ATTRIBUTES = [
  "style",
  "srcset",
  "formaction",
  "xlink:href",
]

export function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;")
}

export function isExternalHttpUrl(value: string): boolean {
  return normalizeExternalHttpUrl(value) !== null
}

function isSafeLink(value: string): boolean {
  const compact = compactUrlForPolicy(value)
  if (compact === "") return false
  if (hasExplicitOrProtocolRelativeScheme(compact)) return false
  const scheme = explicitScheme(compact)
  return scheme === null
}

function decodedPath(value: string): string | null {
  let decoded = value
  try {
    for (let pass = 0; pass < 8; pass += 1) {
      const next = decodeURIComponent(decoded)
      if (next === decoded) return decoded
      decoded = next
    }
    return /%[0-9a-f]{2}/i.test(decoded) ? null : decoded
  } catch {
    return null
  }
}

export function isSafeRelativeImageSource(value: string): boolean {
  const decoded = decodedPath(value.trim())
  if (
    decoded === null ||
    decoded === "" ||
    decoded.startsWith("/") ||
    decoded.startsWith("\\") ||
    decoded.includes("\\") ||
    decoded.includes("\u0000") ||
    explicitScheme(decoded) !== null
  ) {
    return false
  }

  const path = decoded.split(/[?#]/, 1)[0] ?? ""
  return path
    .split("/")
    .every((segment) => segment !== ".." && segment !== ".")
}

function blockedImage(
  document: Document,
  alt: string,
  source = "",
): HTMLSpanElement {
  const placeholder = document.createElement("span")
  placeholder.className = "image-placeholder"
  const description =
    alt.trim() && source.trim()
      ? `${alt.trim()}（${source.trim()}）`
      : alt.trim() || source.trim() || "无效或远程路径"
  placeholder.textContent = `图片未加载：${description}`
  return placeholder
}

function remoteImage(
  document: Document,
  source: string,
  alt: string,
): HTMLSpanElement {
  const placeholder = blockedImage(document, alt || "远程图片", source)
  const separator = document.createTextNode(" ")
  const link = document.createElement("a")
  link.href = source
  link.textContent = "在浏览器中打开链接"
  placeholder.append(separator, link)
  return placeholder
}

function enforceUrlPolicy(fragment: DocumentFragment): void {
  fragment
    .querySelectorAll<HTMLElement>("[data-mermaid-source-encoded]")
    .forEach((block) => {
      const encoded = block.dataset.mermaidSourceEncoded ?? ""
      try {
        block.dataset.mermaidSource = decodeURIComponent(encoded)
      } catch {
        block.classList.add("mermaid-rejected")
        block.textContent = "Mermaid 源编码无效，未渲染"
      }
      delete block.dataset.mermaidSourceEncoded
    })

  fragment.querySelectorAll("a[href]").forEach((anchor) => {
    const href = anchor.getAttribute("href") ?? ""
    const external = normalizeExternalHttpUrl(href)
    if (external !== null) {
      anchor.setAttribute("href", external)
    } else if (!isSafeLink(href)) {
      anchor.removeAttribute("href")
    }
  })

  fragment.querySelectorAll("img").forEach((image) => {
    const source = image.getAttribute("src") ?? ""
    const alt = image.getAttribute("alt") ?? ""
    if (isExternalHttpUrl(source)) {
      image.replaceWith(remoteImage(image.ownerDocument, source, alt))
    } else if (isSafeRelativeImageSource(source)) {
      image.setAttribute("loading", "lazy")
      image.setAttribute("decoding", "async")
      image.dataset.localImage = "true"
    } else {
      image.replaceWith(blockedImage(image.ownerDocument, alt, source))
    }
  })
}

const INTERNAL_MARKER_CLASSES = new Set([
  "mermaid-block",
  "mermaid-rendered",
  "mermaid-rejected",
  "deferred-code-block",
])

export function containsRendererInternalMarker(
  unsafeHtml: string,
): boolean {
  const template = document.createElement("template")
  template.innerHTML = unsafeHtml
  return [...template.content.querySelectorAll<HTMLElement>("*")].some(
    (element) =>
      [...element.classList].some((className) =>
        INTERNAL_MARKER_CLASSES.has(className),
      ) ||
      [...element.attributes].some((attribute) => {
        const name = attribute.name.toLowerCase()
        return (
          name.startsWith("data-mermaid-") ||
          name.startsWith("data-code-") ||
          name === "data-local-image"
        )
      }),
  )
}

export function sanitizeUserProvidedHtml(unsafeHtml: string): string {
  const purifier = createDOMPurify(window)
  const clean = purifier.sanitize(unsafeHtml, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: FORBIDDEN_TAGS,
    FORBID_ATTR: FORBIDDEN_ATTRIBUTES,
    ALLOW_DATA_ATTR: false,
  })
  const template = document.createElement("template")
  template.innerHTML = clean
  template.content.querySelectorAll<HTMLElement>("*").forEach((element) => {
    ;[...element.attributes].forEach((attribute) => {
      if (attribute.name.toLowerCase().startsWith("data-")) {
        element.removeAttribute(attribute.name)
      }
    })
    const retainedClasses = [...element.classList].filter(
      (className) => !INTERNAL_MARKER_CLASSES.has(className),
    )
    element.className = retainedClasses.join(" ")
    if (element.className === "") element.removeAttribute("class")
  })
  enforceUrlPolicy(template.content)
  return template.innerHTML
}

export function bindLocalImageFallbacks(root: HTMLElement): () => void {
  const onError = (event: Event) => {
    const image = event.target
    if (
      !(image instanceof HTMLImageElement) ||
      image.dataset.localImage !== "true" ||
      !root.contains(image)
    ) {
      return
    }
    const source = image.getAttribute("src") ?? ""
    const alt = image.getAttribute("alt") ?? ""
    image.replaceWith(blockedImage(image.ownerDocument, alt, source))
  }
  root.addEventListener("error", onError, true)
  return () => root.removeEventListener("error", onError, true)
}

export function sanitizeMarkdownHtml(unsafeHtml: string): string {
  const purifier = createDOMPurify(window)
  const clean = purifier.sanitize(unsafeHtml, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: FORBIDDEN_TAGS,
    FORBID_ATTR: FORBIDDEN_ATTRIBUTES,
    ALLOW_DATA_ATTR: true,
    ADD_ATTR: [
      "data-mermaid-source",
      "data-mermaid-source-encoded",
      "loading",
      "decoding",
    ],
  })
  const template = document.createElement("template")
  template.innerHTML = clean
  enforceUrlPolicy(template.content)
  return template.innerHTML
}

export function sanitizeMermaidSvg(unsafeSvg: string): string {
  const purifier = createDOMPurify(window)
  return purifier.sanitize(unsafeSvg, {
    USE_PROFILES: { svg: true, svgFilters: true },
    FORBID_TAGS: [...FORBIDDEN_TAGS, "foreignObject"],
    FORBID_ATTR: FORBIDDEN_ATTRIBUTES,
  })
}
