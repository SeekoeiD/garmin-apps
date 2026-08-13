package za.co.dt.edgemusiccontrol

import android.service.notification.NotificationListenerService

/**
 * Carries no logic. Its only job is to exist as a bindable notification listener, because granting
 * it Notification Access is what authorises MediaSessionManager.getActiveSessions() for this app.
 */
class MusicNotificationListener : NotificationListenerService()
