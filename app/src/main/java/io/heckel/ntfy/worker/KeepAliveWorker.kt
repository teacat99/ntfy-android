package io.heckel.ntfy.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.service.SubscriberService
import io.heckel.ntfy.util.Log
import java.util.concurrent.TimeUnit

/**
 * Keep-alive Worker
 *
 * This worker is intentionally independent from the existing service start worker.
 * It periodically checks whether the foreground subscriber service should be running
 * (based on instant subscriptions), and (re-)starts it if necessary.
 */
class KeepAliveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val repository = Repository.getInstance(context)

        // 1. Check if keep-alive is enabled
        if (!repository.getKeepAliveEnabled()) {
            Log.d(TAG, "Keep-alive disabled; skipping")
            return Result.success()
        }

        try {
            // 2. Check if there are instant subscriptions (avoid starting service unnecessarily)
            val instantSubs = repository
                .getSubscriptionIdsWithInstantStatus()
                .filter { (_, instant) -> instant }

            if (instantSubs.isEmpty()) {
                Log.d(TAG, "No instant subscriptions; skipping service start")
                return Result.success()
            }

            // 3. Ensure the subscriber service is running
            ensureSubscriberServiceRunning(context)
            return Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "KeepAliveWorker failed: ${e.message}")
            return Result.success() // Keep-alive is best-effort; don't spam retries
        } finally {
            // Schedule next run only if still enabled
            if (repository.getKeepAliveEnabled()) {
                enqueueNext(context)
            }
        }
    }

    private fun ensureSubscriberServiceRunning(context: Context) {
        val intent = Intent(context, SubscriberService::class.java).apply {
            action = SubscriberService.Action.START.name
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "SubscriberService start triggered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to (re-)start SubscriberService: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NtfyKeepAliveWorker"

        /**
         * Unique work name for the keep-alive chain.
         *
         * Note: We deliberately implement this as a repeating chain of OneTimeWorkRequests, because
         * WorkManager periodic work has a minimum interval (and cannot run every 5 minutes).
         */
        const val WORK_NAME = "NtfyKeepAliveWorker"
        const val WORK_TAG = "keep_alive"

        private const val REPEAT_INTERVAL_MINUTES = 5L

        fun scheduleOnStartup(context: Context) {
            enqueueInitial(context, ExistingWorkPolicy.KEEP)
        }

        fun scheduleNow(context: Context) {
            enqueueInitial(context, ExistingWorkPolicy.REPLACE)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun enqueueInitial(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<KeepAliveWorker>()
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }

        private fun enqueueNext(context: Context) {
            val request = OneTimeWorkRequestBuilder<KeepAliveWorker>()
                .setInitialDelay(REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND, request)
        }
    }
}

