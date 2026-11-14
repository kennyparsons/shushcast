package com.local.shushcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Receives PackageInstaller commit results for self-updates.
 */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                SelfUpdater.lastInstallStatus = "Confirm update installation on TV";
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                SelfUpdater.lastInstallStatus = "Update installed successfully";
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                SelfUpdater.lastInstallStatus = "Update install cancelled";
                break;
            default:
                SelfUpdater.lastInstallStatus = (msg != null && !msg.isEmpty())
                        ? "Install failed: " + msg
                        : "Install failed (status " + status + ")";
                break;
        }
    }
}
