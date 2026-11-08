/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.appdatabackup

import android.app.Application
import android.app.appbackup.AppBackupInfo
import android.app.appbackup.AppDataBackupRestoreManager
import android.app.appbackup.BackupRecord
import android.app.appbackup.BackupResult
import android.app.appbackup.IBackupProgressCallback
import android.app.appbackup.IRestoreProgressCallback
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.UserHandle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.Collator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupUiState(
    val apps: List<AppBackupInfo> = emptyList(),
    val backups: List<BackupRecord> = emptyList(),
    val selected: Set<String> = emptySet(),
    val selectedBackups: Set<String> = emptySet(),
    val corruptedBackups: Set<String> = emptySet(),
    val lastBackupByPkg: Map<String, Long> = emptyMap(),
    val noBackupPkgs: Set<String> = emptySet(),
    val loadingApps: Boolean = true,
    val loadingBackups: Boolean = true,
    val appsError: Boolean = false,
    val backupsError: Boolean = false,
    val operation: BackupOperation? = null,
    val message: String? = null,
    val spaceWarning: SpaceWarning? = null,
    val results: OperationResults? = null,
) {
    val busy: Boolean get() = operation != null
    val selectedBytes: Long get() = apps.filter { it.packageName in selected }
        .sumOf { it.dataSize.coerceAtLeast(0) }
}

data class BackupOperation(
    val title: String,
    val detail: String,
    val progress: Float? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val cancellable: Boolean = false,
    val cancelling: Boolean = false,
)

data class BackupOptions(
    val components: Int,
    val excludeCache: Boolean,
    val passphrase: String?,
    val keepVersions: Int,
)

data class SpaceWarning(
    val needed: Long,
    val free: Long,
    val options: BackupOptions,
)

data class OperationResults(
    val title: Int,
    val lines: List<String>,
)

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val manager =
        application.getSystemService(Context.APP_DATA_BACKUP_SERVICE) as AppDataBackupRestoreManager
    private val backupDir = File("/data/media/${UserHandle.myUserId()}/AppDataBackup")
    private val mutableState = MutableStateFlow(BackupUiState())
    val state = mutableState.asStateFlow()
    private var generation = 0
    private var operationToken: String? = null
    private var operationReady = false
    private var cancellationSent = false

    init {
        loadApps()
        loadBackups()
    }

    fun refresh() {
        if (state.value.busy) return
        if (!state.value.loadingApps) loadApps()
        if (!state.value.loadingBackups) loadBackups()
    }

    private fun loadApps() {
        mutableState.update { it.copy(loadingApps = true, appsError = false) }
        viewModelScope.launch {
            try {
                val (apps, optOut) = withContext(Dispatchers.IO) {
                    val collator = Collator.getInstance()
                    val sorted = manager.installedApps
                        .sortedWith { a, b -> collator.compare(a.label, b.label) }
                    val noBackup = getApplication<Application>().packageManager
                        .getInstalledApplications(0)
                        .filter { (it.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) == 0 }
                        .map { it.packageName }.toSet()
                    sorted to noBackup
                }
                val packages = apps.map { it.packageName }.toSet()
                mutableState.update {
                    it.copy(apps = apps, loadingApps = false, noBackupPkgs = optOut,
                        selected = it.selected intersect packages)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Unable to load apps", e)
                mutableState.update { it.copy(loadingApps = false, appsError = true) }
            }
        }
    }

    private fun loadBackups() {
        mutableState.update { it.copy(loadingBackups = true, backupsError = false) }
        viewModelScope.launch {
            try {
                val backups = withContext(Dispatchers.IO) {
                    manager.getAvailableBackups(backupDir.absolutePath)
                        .sortedByDescending { it.timestampMs }
                }
                val lastByPkg = backups.fold(mutableMapOf<String, Long>()) { acc, record ->
                    acc[record.packageName] = maxOf(acc[record.packageName] ?: 0L, record.timestampMs)
                    acc
                }
                val ids = backups.map { it.id }.toSet()
                mutableState.update {
                    it.copy(backups = backups, loadingBackups = false, lastBackupByPkg = lastByPkg,
                        selectedBackups = it.selectedBackups intersect ids,
                        corruptedBackups = it.corruptedBackups intersect ids)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Unable to load backups", e)
                mutableState.update { it.copy(loadingBackups = false, backupsError = true) }
            }
        }
    }

    fun togglePackage(packageName: String) {
        if (state.value.busy) return
        mutableState.update {
            it.copy(selected = if (packageName in it.selected) it.selected - packageName
                else it.selected + packageName)
        }
    }

    fun selectPackages(packages: List<String>) {
        if (!state.value.busy) mutableState.update { it.copy(selected = it.selected + packages) }
    }

    fun clearSelection() {
        if (!state.value.busy) mutableState.update { it.copy(selected = emptySet()) }
    }

    fun toggleBackup(id: String) {
        if (state.value.busy) return
        mutableState.update {
            it.copy(selectedBackups = if (id in it.selectedBackups) it.selectedBackups - id
                else it.selectedBackups + id)
        }
    }

    fun selectBackups(ids: List<String>) {
        if (!state.value.busy) mutableState.update { it.copy(selectedBackups = it.selectedBackups + ids) }
    }

    fun clearBackupSelection() {
        if (!state.value.busy) mutableState.update { it.copy(selectedBackups = emptySet()) }
    }

    fun consumeMessage(message: String) {
        mutableState.update { if (it.message == message) it.copy(message = null) else it }
    }

    fun notifySort(bySize: Boolean) {
        mutableState.update {
            it.copy(message = text(if (bySize) R.string.sort_by_size else R.string.sort_by_name))
        }
    }

    fun dismissSpaceWarning() {
        mutableState.update { it.copy(spaceWarning = null) }
    }

    fun consumeResults() {
        mutableState.update { it.copy(results = null) }
    }

    fun backup(options: BackupOptions, force: Boolean = false) {
        if (state.value.busy || state.value.loadingApps || state.value.appsError ||
            state.value.selected.isEmpty() || options.components == 0 || options.keepVersions < 0) return
        if (!force) {
            val needed = state.value.selectedBytes
            val free = try {
                StatFs("/data").availableBytes
            } catch (e: Exception) {
                Log.w(TAG, "statfs failed", e)
                0L
            }
            if (free > 0 && needed > free) {
                mutableState.update { it.copy(spaceWarning = SpaceWarning(needed, free, options)) }
                return
            }
        }
        val apps = state.value.apps.filter { it.packageName in state.value.selected }
        val sizes = apps.associate { it.packageName to it.dataSize.coerceAtLeast(0) }
        val totalBytes = sizes.values.sum()
        val id = begin(R.string.progress_backup_title, R.string.progress_preparing, cancellable = true)
        val callback = object : IBackupProgressCallback.Stub() {
            private var bytesDone = 0L
            private val results = mutableListOf<String>()

            override fun onBackupStarted(token: String, total: Int) = operationStarted(id, token) {
                it.copy(total = total, progress = 0f)
            }

            override fun onPackageBackupStarted(token: String, pkg: String, idx: Int, total: Int) =
                updateOperation(id) {
                    it.copy(detail = apps.find { app -> app.packageName == pkg }?.label ?: pkg,
                        total = total)
                }

            override fun onPackageBackupFinished(token: String, pkg: String, result: BackupResult) {
                results.add(resultLine(pkg, result))
                bytesDone += sizes[pkg] ?: 0L
                updateOperation(id) {
                    val done = (it.completed + 1).coerceAtMost(it.total)
                    val progress = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f)
                        else done.toFloat() / it.total.coerceAtLeast(1)
                    it.copy(completed = done, progress = progress)
                }
            }

            override fun onBackupFinished(token: String, result: BackupResult) {
                complete(id, result, R.string.backup_complete, reloadBackups = true,
                    results = results.toList(), resultsTitle = R.string.results_title_backup)
            }

            override fun onBackupCancelled(token: String) {
                complete(id, null, R.string.backup_cancelled, reloadBackups = true)
            }
        }
        viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    manager.backupPackages(apps.map { it.packageName }, backupDir.absolutePath,
                        options.excludeCache, callback, options.passphrase, options.components,
                        options.keepVersions)
                }
                acceptToken(id, token)
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun restore(record: BackupRecord, passphrase: String?) {
        restore(listOf(record), passphrase, record.components)
    }

    fun restore(records: List<BackupRecord>, passphrase: String?, components: Int) {
        if (state.value.busy || records.isEmpty() || components == 0) return
        val id = begin(R.string.progress_restore_title, R.string.progress_preparing, cancellable = true)
        val callback = object : IRestoreProgressCallback.Stub() {
            private var packageResult: BackupResult? = null
            private val results = mutableListOf<String>()

            override fun onRestoreStarted(token: String, total: Int) = operationStarted(id, token) {
                it.copy(total = total, progress = 0f)
            }

            override fun onPackageRestoreStarted(token: String, pkg: String, idx: Int, total: Int) =
                updateOperation(id) {
                    it.copy(detail = text(R.string.progress_installing, labelFor(records, pkg)))
                }

            override fun onPackageDataRestoring(token: String, pkg: String) = updateOperation(id) {
                it.copy(detail = text(R.string.progress_restoring_data, labelFor(records, pkg)))
            }

            override fun onPackageRestoreFinished(token: String, pkg: String, result: BackupResult) {
                packageResult = result
                results.add(resultLine(pkg, result))
                updateOperation(id) {
                    val done = (it.completed + 1).coerceAtMost(it.total)
                    it.copy(completed = done, progress = done.toFloat() / it.total.coerceAtLeast(1))
                }
            }

            override fun onRestoreFinished(token: String, result: BackupResult) {
                complete(id, packageResult?.takeUnless { it.isSuccess } ?: result,
                    R.string.restore_complete, reloadApps = true, clearBackupSelection = true,
                    results = results.toList(), resultsTitle = R.string.results_title_restore)
            }

            override fun onRestoreCancelled(token: String) {
                complete(id, null, R.string.restore_cancelled, reloadApps = true,
                    clearBackupSelection = true)
            }
        }
        viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    manager.restorePackages(records.map { it.id }, records[0].backupDir, callback,
                        passphrase, components)
                }
                acceptToken(id, token)
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun importSwift(tree: Uri) {
        if (state.value.busy) return
        val id = begin(R.string.swift_import, R.string.progress_preparing)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val files = SwiftBackupDocuments.files(resolver, tree)
                    if (files.isEmpty() || files.size > 10) {
                        return@withContext BackupResult.failure(BackupResult.ERROR_IO,
                            text(R.string.swift_select_version))
                    }
                    val descriptors = mutableListOf<ParcelFileDescriptor>()
                    try {
                        files.forEach { (_, uri) -> descriptors +=
                            resolver.openFileDescriptor(uri, "r")
                                ?: throw java.io.IOException("Cannot open Swift backup file") }
                        manager.importSwiftBackup(files.map { it.first }.toTypedArray(), descriptors)
                    } finally { descriptors.forEach { runCatching { it.close() } } }
                }
                complete(id, result, R.string.swift_import_complete, reloadBackups = true)
            } catch (e: Exception) { fail(id, e) }
        }
    }

    fun exportSwift(record: BackupRecord, tree: Uri) {
        if (state.value.busy || record.isEncrypted) return
        val id = begin(R.string.swift_export, R.string.progress_preparing)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val archive = File.createTempFile("swift-export-", ".zip", app.cacheDir)
                    try {
                        val result = ParcelFileDescriptor.open(archive,
                            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use {
                            manager.exportSwiftBackup(record.id, it)
                        }
                        if (result.isSuccess) SwiftBackupDocuments.export(app.contentResolver,
                            tree, record.packageName, archive)
                        result
                    } finally { archive.delete() }
                }
                complete(id, result, R.string.swift_export_complete)
            } catch (e: Exception) { fail(id, e) }
        }
    }

    fun verify(record: BackupRecord, passphrase: String?) {
        if (state.value.busy) return
        val id = begin(R.string.progress_verify_title, R.string.progress_preparing)
        updateOperation(id) { it.copy(detail = record.label) }
        viewModelScope.launch {
            try {
                val error = withContext(Dispatchers.IO) {
                    manager.verifyBackup(record.id, record.backupDir, passphrase)
                }
                finish(id, error?.let { text(R.string.verify_failed, it) } ?: text(R.string.verify_valid))
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun verifyAll(passphrase: String?) {
        if (state.value.busy) return
        val records = state.value.backups
        if (records.isEmpty()) return
        val id = begin(R.string.verify_all, R.string.progress_preparing)
        updateOperation(id) { it.copy(total = records.size, progress = 0f) }
        viewModelScope.launch {
            try {
                val (corrupted, summary) = withContext(Dispatchers.IO) {
                    var valid = 0
                    var failed = 0
                    var skipped = 0
                    val bad = mutableSetOf<String>()
                    records.forEachIndexed { index, record ->
                        if (record.isEncrypted && passphrase == null) {
                            skipped++
                            return@forEachIndexed
                        }
                        updateOperation(id) {
                            it.copy(detail = record.label, completed = index,
                                progress = index.toFloat() / records.size.coerceAtLeast(1))
                        }
                        val error = manager.verifyBackup(record.id, record.backupDir,
                            if (record.isEncrypted) passphrase else null)
                        if (error == null) valid++ else {
                            failed++
                            bad.add(record.id)
                        }
                    }
                    bad to text(R.string.verify_all_result, valid, failed, skipped)
                }
                mutableState.update { it.copy(corruptedBackups = corrupted) }
                finish(id, summary)
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun delete(record: BackupRecord) {
        if (state.value.busy) return
        val id = begin(R.string.progress_delete_title, R.string.progress_preparing)
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    manager.deleteBackup(record.id, record.backupDir)
                }
                finish(id, text(if (deleted) R.string.delete_complete else R.string.delete_failed))
                loadBackups()
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun deleteSelected() {
        if (state.value.busy) return
        val ids = state.value.selectedBackups.toList()
        if (ids.isEmpty()) return
        val id = begin(R.string.progress_delete_title, R.string.progress_preparing)
        viewModelScope.launch {
            try {
                val failed = withContext(Dispatchers.IO) {
                    ids.count { !manager.deleteBackup(it, backupDir.absolutePath) }
                }
                finish(id, text(if (failed == 0) R.string.delete_complete else R.string.delete_failed))
                mutableState.update { it.copy(selectedBackups = emptySet()) }
                loadBackups()
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun export(record: BackupRecord) {
        if (state.value.busy) return
        val id = begin(R.string.export, R.string.progress_preparing)
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) { manager.exportBackup(record.id) }
                finish(id, path?.let { text(R.string.export_done, it) } ?: text(R.string.export_failed))
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun hideBackup(record: BackupRecord) {
        mutableState.update {
            it.copy(backups = it.backups.filterNot { entry -> entry.id == record.id },
                selectedBackups = it.selectedBackups - record.id)
        }
    }

    fun undoHideBackup(record: BackupRecord) {
        mutableState.update {
            it.copy(backups = (it.backups + record).sortedByDescending { entry -> entry.timestampMs })
        }
    }

    fun deleteQuiet(record: BackupRecord) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { manager.deleteBackup(record.id, record.backupDir) }
                loadBackups()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Unable to delete backup", e)
            }
        }
    }

    fun cancelOperation() {
        val operation = state.value.operation ?: return
        if (!operation.cancellable || operation.cancelling) return
        mutableState.update { it.copy(operation = operation.copy(cancelling = true)) }
        operationToken?.let { sendCancel(generation, it) }
    }

    private fun sendCancel(id: Int, token: String) {
        if (id != generation || !operationReady || cancellationSent) return
        cancellationSent = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { manager.cancelOperation(token) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Unable to cancel operation", e)
                if (id == generation && state.value.busy) {
                    cancellationSent = false
                    mutableState.update { it.copy(operation = it.operation?.copy(cancelling = false),
                        message = text(R.string.cancel_failed)) }
                }
            }
        }
    }

    private fun begin(title: Int, detail: Int, cancellable: Boolean = false): Int {
        generation++
        operationToken = null
        operationReady = false
        cancellationSent = false
        mutableState.update { it.copy(operation = BackupOperation(text(title), text(detail),
            cancellable = cancellable)) }
        return generation
    }

    private fun acceptToken(id: Int, token: String) {
        if (id != generation || !state.value.busy) return
        if (token.isBlank()) {
            finish(id, text(R.string.operation_failed))
        } else {
            operationToken = token
            if (state.value.operation?.cancelling == true) sendCancel(id, token)
        }
    }

    private fun updateOperation(id: Int, update: (BackupOperation) -> BackupOperation) {
        viewModelScope.launch {
            if (id == generation) mutableState.update { it.copy(operation = it.operation?.let(update)) }
        }
    }

    private fun operationStarted(id: Int, token: String, update: (BackupOperation) -> BackupOperation) {
        viewModelScope.launch {
            if (id != generation || !state.value.busy) return@launch
            operationToken = token
            operationReady = true
            mutableState.update { it.copy(operation = it.operation?.let(update)) }
            if (state.value.operation?.cancelling == true) sendCancel(id, token)
        }
    }

    private fun complete(id: Int, result: BackupResult?, success: Int,
        reloadBackups: Boolean = false, reloadApps: Boolean = false,
        clearBackupSelection: Boolean = false,
        results: List<String> = emptyList(), resultsTitle: Int = 0) {
        viewModelScope.launch {
            if (id != generation || !state.value.busy) return@launch
            val message = when {
                result == null || result.isSuccess -> text(success)
                state.value.operation?.cancelling == true || result.errorCode == BackupResult.ERROR_CANCELLED ->
                    text(R.string.operation_cancelled)
                result.status == BackupResult.STATUS_PARTIAL ->
                    text(R.string.operation_partial, result.message ?: text(R.string.operation_failed))
                else -> text(R.string.operation_error, result.message ?: text(R.string.operation_failed))
            }
            finish(id, message)
            if (resultsTitle != 0 && results.any { it.startsWith("\u2717") }) {
                mutableState.update { it.copy(results = OperationResults(resultsTitle, results)) }
            }
            if (clearBackupSelection) mutableState.update { it.copy(selectedBackups = emptySet()) }
            if (reloadBackups) loadBackups()
            if (reloadApps) loadApps()
        }
    }

    private fun finish(id: Int, message: String) {
        if (id != generation || !state.value.busy) return
        operationToken = null
        operationReady = false
        mutableState.update { it.copy(operation = null, message = message) }
    }

    private fun fail(id: Int, e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Backup operation failed", e)
        finish(id, text(R.string.operation_failed))
    }

    private fun resultLine(pkg: String, result: BackupResult): String =
        (if (result.isSuccess) "\u2713 " else "\u2717 ") + pkg +
            (if (result.isSuccess) "" else " \u2014 " + (result.message ?: ""))

    private fun labelFor(records: List<BackupRecord>, pkg: String): String =
        records.find { it.packageName == pkg }?.label ?: pkg

    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    companion object {
        private const val TAG = "AppDataBackupUI"
    }
}
