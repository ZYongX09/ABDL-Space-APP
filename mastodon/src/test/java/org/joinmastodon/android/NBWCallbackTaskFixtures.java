package org.joinmastodon.android;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowAppTask;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AppTask dispatch boundary only, NOT an Android task-stack emulator. Production selects the task,
 * validates its root/owner and constructs the navigation; only that selected AppTask delivers it.
 * REORDER_TO_FRONT/singleTop/OEM behavior still requires a device; this seam cannot prove it.
 */
public final class NBWCallbackTaskFixtures{
	private static final Map<Activity, Integer> taskIds=new IdentityHashMap<>();
	private static final Map<Integer, Task> tasks=new LinkedHashMap<>();
	public static final List<Dispatch> dispatches=new ArrayList<>();
	private NBWCallbackTaskFixtures(){}

	public static final class Dispatch{
		public final int taskId;
		public final Intent intent;
		public final Activity callback;
		public final Activity owner;
		Dispatch(Task task, Intent intent, Activity callback, Activity owner){
			taskId=task.id; this.intent=new Intent(intent); this.callback=callback; this.owner=owner;
		}
	}
	public static final class Task{
		public final int id;
		public ComponentName root, top;
		public boolean denied;
		private final ActivityManager.AppTask appTask;
		private final List<ActivityController<?>> owners=new ArrayList<>();
		Task(int id, ComponentName root){
			this.id=id; this.root=root; top=root; appTask=ShadowAppTask.newInstance();
			TaskDispatch shadow=Shadow.extract(appTask); shadow.task=this;
		}
		public void browserOnTop(){ top=new ComponentName("offline.browser", "offline.browser.BrowserActivity"); }
	}
	public static Task pin(ActivityController<?> controller, int id){
		Activity activity=(Activity)controller.get(); taskIds.put(activity, id);
		Task task=tasks.get(id);
		if(task==null){ task=new Task(id, activity.getComponentName()); tasks.put(id, task); }
		task.owners.add(controller); task.top=activity.getComponentName(); publish(); return task;
	}
	public static Task task(int id){ return tasks.get(id); }
	public static void remove(int id){ tasks.remove(id); publish(); }
	public static void reset(){ taskIds.clear(); tasks.clear(); dispatches.clear(); publish(); }
	private static void publish(){
		ActivityManager manager=RuntimeEnvironment.getApplication().getSystemService(ActivityManager.class);
		ShadowActivityManager shadow=Shadow.extract(manager);
		List<ActivityManager.AppTask> appTasks=new ArrayList<>();
		for(Task task:tasks.values()) appTasks.add(task.appTask);
		shadow.setAppTasks(appTasks);
	}

	@Implements(Activity.class)
	public static class TaskActivity extends ShadowActivity{
		@RealObject Activity activity;
		@Implementation @Override protected int getTaskId(){ return taskIds.getOrDefault(activity, 0); }
	}
	@Implements(ActivityManager.AppTask.class)
	public static class TaskDispatch extends ShadowAppTask{
		Task task;
		@Implementation @Override protected ActivityManager.RecentTaskInfo getTaskInfo(){
			ActivityManager.RecentTaskInfo info=new ActivityManager.RecentTaskInfo();
			info.id=task.id; if(android.os.Build.VERSION.SDK_INT>=29) info.taskId=task.id;
			info.baseActivity=task.root; info.topActivity=task.top;
			info.baseIntent=new Intent().setComponent(task.root); return info;
		}
		@Implementation @Override protected void startActivity(Context context, Intent intent, Bundle options){
			if(task.denied) throw new SecurityException("Test task dispatch denied");
			if(intent.getFlags()!=(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP))
				throw new AssertionError("Owner navigation must not clear or create a task");
			if(!(context instanceof Activity callback) || callback.isFinishing()) throw new AssertionError("Dispatch must precede router finish");
			for(int i=task.owners.size()-1;i>=0;i--){
				ActivityController<?> controller=task.owners.get(i); Activity owner=(Activity)controller.get();
				if(!owner.isDestroyed() && !owner.isFinishing() && owner.getComponentName().equals(intent.getComponent())){
					dispatches.add(new Dispatch(task, intent, callback, owner)); task.top=owner.getComponentName();
					controller.newIntent(new Intent(intent)); return;
				}
			}
			throw new IllegalStateException("No live owner in selected test task");
		}
	}
}
