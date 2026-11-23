package com.personal.statusguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Called by Android when an alarm is due, and after the phone restarts. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val alarm = AlarmStore(context).get(id)
        if (alarm == null) {
            // Phone restarted or app updated: Android forgets alarms then, so set them again.
            // An alarm that was still running when the phone went off was escaped.
            History(context).checkEscapedAlarm(context)
            AlarmScheduler.scheduleAll(context)
            Routine.scheduleAll(context)
            return
        }
        AlarmScheduler.schedule(context, alarm) // book the next occurrence
        context.startForegroundService(AlarmService.ringIntent(context, id))
    }
}
