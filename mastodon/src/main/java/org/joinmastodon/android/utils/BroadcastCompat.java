package org.joinmastodon.android.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public final class BroadcastCompat{
	private BroadcastCompat(){}

	@Nullable
	public static Intent registerSystemReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter){
		return ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
	}

	@Nullable
	public static Intent registerInternalReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter){
		return ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
	}
}
