package app.grapheneos.goscompat.checks.dmabuf

import android.content.Context
import app.grapheneos.goscompat.checks.GosCompatContract
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

object DmaBufReleaseProgressStore {
    fun save(context: Context, token: String?, result: DmaBufReleaseResult) {
        if (token.isNullOrEmpty()) {
            return
        }

        try {
            FileOutputStream(resultFile(context)).use { output ->
                result.toProperties(token).store(output, null)
            }
        } catch (_: IOException) {
        }
    }

    fun load(context: Context, token: String): DmaBufReleaseResult? {
        val properties = loadProperties(context) ?: return null
        if (properties.getProperty(GosCompatContract.DmaBufRelease.Extra.TOKEN) != token) {
            return null
        }
        if (!properties.getProperty(
                GosCompatContract.DmaBufRelease.Key.RESULT_AVAILABLE,
            ).toBoolean()
        ) {
            return null
        }
        return DmaBufReleaseResult.fromProperties(properties)
    }

    fun clear(context: Context) {
        resultFile(context).delete()
    }

    private fun loadProperties(context: Context): Properties? {
        val file = resultFile(context)
        if (!file.exists()) {
            return null
        }

        return try {
            Properties().also { properties ->
                FileInputStream(file).use { input -> properties.load(input) }
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun resultFile(context: Context): File =
        File(context.filesDir, GosCompatContract.DmaBufRelease.RESULT_FILE)
}
