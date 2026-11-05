package org.joinmastodon.android.auth;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;

import org.joinmastodon.android.NBWLoginActivity;
import org.joinmastodon.android.fragments.settings.NBWPostRegisterActivity;

import java.lang.ref.WeakReference;

/** Process-only return address, captured from the Activity that actually opens the browser. */
public final class NBWCallbackTaskRouter{
	private NBWCallbackTaskRouter(){}

	public static final class Owner{
		private final int taskId;
		private final ComponentName component;
		private WeakReference<Activity> activity;
		private Owner(Activity owner){
			taskId=owner.getTaskId(); component=owner.getComponentName(); activity=new WeakReference<>(owner);
		}
		/** Only a saved in-process flow/ticket may rebind a recreated owner, in its original task. */
		public boolean reattach(Activity owner){
			if(!matches(owner) || owner.isFinishing() || owner.isDestroyed()) return false;
			Activity previous=activity.get();
			if(previous!=null && previous!=owner && !previous.isDestroyed() && !previous.isChangingConfigurations()) return false;
			activity=new WeakReference<>(owner); return true;
		}
		public boolean owns(Activity owner){ return activity.get()==owner && matches(owner) && !owner.isFinishing() && !owner.isDestroyed(); }
		private boolean matches(Activity owner){ return owner!=null && taskId==owner.getTaskId() && component.equals(owner.getComponentName()); }
	}

	public static Owner capture(Activity owner){
		if(owner==null || owner.isFinishing() || owner.isDestroyed() || owner.getTaskId()<0) return null;
		ComponentName component=owner.getComponentName();
		if(component==null || !owner.getPackageName().equals(component.getPackageName())
				|| !(NBWLoginActivity.class.getName().equals(component.getClassName())
				|| NBWPostRegisterActivity.class.getName().equals(component.getClassName()))) return null;
		return new Owner(owner);
	}

	/** Never use a task id, component, or fallback task supplied by a callback Intent. */
	@SuppressWarnings("deprecation")
	public static boolean restore(Activity callback, Owner owner, Intent navigation){
		if(owner==null || !owner.owns(owner.activity.get()) || navigation==null || navigation.getData()!=null
				|| !owner.component.equals(navigation.getComponent())) return false;
		ActivityManager manager=callback.getSystemService(ActivityManager.class);
		if(manager==null) return false;
		try{
			for(ActivityManager.AppTask task:manager.getAppTasks()){
				ActivityManager.RecentTaskInfo info=task.getTaskInfo();
				if(info==null || (Build.VERSION.SDK_INT>=29 ? info.taskId : info.id)!=owner.taskId) continue;
				ComponentName root=info.baseActivity!=null ? info.baseActivity : info.baseIntent==null ? null : info.baseIntent.getComponent();
				// Registration need not have MainActivity as its root, but it must still be our task.
				if(root==null || !callback.getPackageName().equals(root.getPackageName())) return false;
				Intent intent=new Intent(navigation);
				// CLEAR_TOP would destroy a standard connection owner and its live authorization ticket.
				intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
				task.startActivity(callback, intent, null);
				return true;
			}
		}catch(RuntimeException unavailable){
			// Missing task, OS denial, or unsupported task API: fail closed, never create another owner.
		}
		return false;
	}
}
