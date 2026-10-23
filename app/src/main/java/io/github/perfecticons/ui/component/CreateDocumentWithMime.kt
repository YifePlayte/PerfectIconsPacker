package io.github.perfecticons.ui.component

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * `CreateDocument` with the MIME type carried by the request instead of baked
 * into the contract.
 *
 * The stock [androidx.activity.result.contract.ActivityResultContracts.CreateDocument]
 * fixes its type at construction, so one launcher cannot serve two artifacts.
 * That is what turned MTZ exports into `.zip` files: the picker was created for
 * `application/zip` and the system appended ZIP's extension to every title it
 * was given.
 */
class CreateDocumentWithMime : ActivityResultContract<CreateDocumentRequest, Uri?>() {

    override fun createIntent(context: Context, input: CreateDocumentRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

/**
 * @param fileName suggested name, including the extension the artifact needs.
 * @param mimeType type advertised to the picker. A type the system cannot map to
 *   an extension (such as the MTZ one) leaves the given name untouched, so the
 *   extension is preserved instead of being replaced.
 */
data class CreateDocumentRequest(
    val fileName: String,
    val mimeType: String,
)
