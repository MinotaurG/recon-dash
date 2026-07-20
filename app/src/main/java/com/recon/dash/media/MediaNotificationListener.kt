package com.recon.dash.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Required for MediaSessionManager.getActiveSessions() — Android needs an
 * active NotificationListenerService to grant media session access.
 *
 * This service does nothing with notifications itself; it exists solely
 * to satisfy the permission requirement.
 */
class MediaNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
