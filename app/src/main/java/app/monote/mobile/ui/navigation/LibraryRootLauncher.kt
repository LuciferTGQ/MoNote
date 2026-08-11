package app.monote.mobile.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract

fun launchLibraryRoot(context: Context) {
    val root = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:Documents/MoNote",
    )
    val viewIntent = Intent(Intent.ACTION_VIEW, root).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(viewIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, root)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
