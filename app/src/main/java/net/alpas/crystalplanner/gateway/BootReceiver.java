package net.alpas.crystalplanner.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import net.alpas.crystalplanner.storage.AppSettings;
import net.alpas.crystalplanner.storage.SecureTokenStore;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        AppSettings settings = AppSettings.load(context);
        if (settings.gatewayPresenceEnabled && new SecureTokenStore(context).hasToken()) {
            GatewayPresenceService.startOrUpdate(context);
        }
    }
}
