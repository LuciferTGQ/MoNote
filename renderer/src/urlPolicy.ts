const SCHEME_PATTERN = /^([a-z][a-z0-9+.-]*):/i
const CONTROL_OR_SPACE = /[\u0000-\u0020\u007f]+/g
const NETWORK_PATH_PATTERN = /^[\\/]{2}/

export function compactUrlForPolicy(value: string): string {
  return value.trim().replace(CONTROL_OR_SPACE, "")
}

export function normalizeExternalHttpUrl(value: string): string | null {
  const compact = compactUrlForPolicy(value)
  if (!/^https?:\/\//i.test(compact)) return null
  try {
    const parsed = new URL(compact)
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      return null
    }
    return parsed.href
  } catch {
    return null
  }
}

export function hasExplicitOrProtocolRelativeScheme(value: string): boolean {
  const compact = compactUrlForPolicy(value)
  return NETWORK_PATH_PATTERN.test(compact) || SCHEME_PATTERN.test(compact)
}

export function explicitScheme(value: string): string | null {
  return SCHEME_PATTERN.exec(compactUrlForPolicy(value))?.[1]?.toLowerCase() ?? null
}
