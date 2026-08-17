package app.monote.mobile.data.catalog

import kotlinx.serialization.Serializable

@Serializable
data class CatalogSnapshot(
    val version: Int = VERSION,
    val documents: List<CatalogDocument> = emptyList(),
) {
    companion object { const val VERSION = 1 }
}

@Serializable
data class CatalogDocument(
    val id: String,
    val relativePath: String,
    val favorite: Boolean,
    val tags: Set<String>,
)
