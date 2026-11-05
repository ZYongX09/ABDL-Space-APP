package org.joinmastodon.android.api;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;

import java.io.IOException;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

import me.grishka.appkit.api.ErrorResponse;
import okhttp3.HttpUrl;
import okhttp3.Response;

/** Immutable first-party error metadata. Never retains a response body, localized text or Context. */
public final class ServiceResourcePause{
	public static final int MAX_ERROR_BYTES=32*1024;
	public static final String CODE="D1_DAILY_QUOTA_EXHAUSTED";
	private static final ZoneId CHINA=ZoneId.of("Asia/Shanghai");
	private final Timing timing;

	/** Non-Mastodon typed errors can carry metadata without changing class or retry semantics. */
	public interface ErrorMetadata{
		void setServiceResourcePause(ServiceResourcePause pause);
		ServiceResourcePause getServiceResourcePause();
	}

	private ServiceResourcePause(Timing timing){ this.timing=timing; }

	public static ServiceResourcePause fromResponse(Response response, JsonObject errorBody){
		return fromResponse(response, errorBody, Clock.systemUTC(), SystemClock::elapsedRealtime);
	}

	public static ServiceResourcePause fromResponse(Response response, String errorBody){
		if(!trustedError(response)) return null; // In particular, never inspect successful user content.
		return fromResponse(response, parseErrorObject(errorBody));
	}

	static ServiceResourcePause fromResponse(Response response, JsonObject body, Clock clock, LongSupplier elapsed){
		if(!trustedError(response) || body==null || !CODE.equals(string(body, "code")) || !boundedTree(body, 0, new int[]{0})) return null;
		return new ServiceResourcePause(Timing.observe(body, clock, elapsed));
	}

	public static ServiceResourcePause fromError(ErrorResponse error){
		if(error instanceof MastodonErrorResponse.SilentNBWError) return null;
		if(error instanceof MastodonErrorResponse mastodon) return mastodon.serviceResourcePause;
		return error instanceof ErrorMetadata metadata ? metadata.getServiceResourcePause() : null;
	}

	static void attach(ErrorResponse error, ServiceResourcePause pause){
		if(pause==null || error instanceof MastodonErrorResponse.SilentNBWError) return;
		if(error instanceof MastodonErrorResponse mastodon) mastodon.serviceResourcePause=pause;
		else if(error instanceof ErrorMetadata metadata) metadata.setServiceResourcePause(pause);
	}

	private static boolean trustedError(Response response){
		if(response==null || response.isSuccessful()) return false;
		HttpUrl url=response.request().url();
		if(!"https".equals(url.scheme()) || url.port()!=443 || !url.username().isEmpty() || !url.password().isEmpty()) return false;
		return "abdl-space.top".equals(url.host()) || "api.abdl-space.top".equals(url.host());
	}

	/** Strict, single JSON document; unlike JsonParser this never enables lenient JSON parsing. */
	static JsonObject parseErrorObject(String text){
		if(text==null || text.length()>MAX_ERROR_BYTES || text.getBytes(StandardCharsets.UTF_8).length>MAX_ERROR_BYTES) return null;
		try(JsonReader reader=new JsonReader(new StringReader(text))){
			reader.setLenient(false);
			JsonElement element=readJson(reader, 0);
			return reader.peek()==JsonToken.END_DOCUMENT && element.isJsonObject() ? element.getAsJsonObject() : null;
		}catch(IOException | RuntimeException invalid){ return null; }
	}

	private static JsonElement readJson(JsonReader reader, int depth) throws IOException{
		if(depth>32) throw new IOException("Error JSON nesting limit");
		return switch(reader.peek()){
			case BEGIN_OBJECT -> {
				JsonObject object=new JsonObject(); reader.beginObject();
				while(reader.hasNext()){
					String name=reader.nextName();
					if(object.has(name)) throw new IOException("Duplicate error JSON member");
					object.add(name, readJson(reader, depth+1));
				}
				reader.endObject(); yield object;
			}
			case BEGIN_ARRAY -> {
				JsonArray array=new JsonArray(); reader.beginArray();
				while(reader.hasNext()) array.add(readJson(reader, depth+1));
				reader.endArray(); yield array;
			}
			case STRING -> new JsonPrimitive(reader.nextString());
			case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
			case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
			case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
			default -> throw new IOException("Invalid error JSON");
		};
	}

	private static boolean boundedTree(JsonElement element, int depth, int[] size){
		if(depth>32 || (size[0]+=2)>MAX_ERROR_BYTES) return false;
		if(element.isJsonObject()){
			for(Map.Entry<String, JsonElement> entry:element.getAsJsonObject().entrySet()){
				if(!addSize(entry.getKey(), size) || !boundedTree(entry.getValue(), depth+1, size)) return false;
			}
		}else if(element.isJsonArray()){
			for(JsonElement child:element.getAsJsonArray()) if(!boundedTree(child, depth+1, size)) return false;
		}else if(!element.isJsonNull() && !addSize(element.getAsString(), size)) return false;
		return true;
	}

	private static boolean addSize(String value, int[] size){
		if(value.length()>MAX_ERROR_BYTES) return false;
		size[0]+=value.getBytes(StandardCharsets.UTF_8).length+3;
		return size[0]<=MAX_ERROR_BYTES;
	}

	private static String string(JsonObject body, String key){
		JsonElement value=body.get(key);
		return value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	public String message(Context context){ return message(context, timing.now()); }

	String message(Context context, Instant now){
		if(!now.isBefore(timing.resetAt)) return context.getString(R.string.service_resource_pause_waiting);
		var reset=timing.resetAt.atZone(CHINA);
		var today=now.atZone(CHINA).toLocalDate();
		String day;
		if(reset.toLocalDate().equals(today)) day=context.getString(R.string.service_resource_pause_today);
		else if(reset.toLocalDate().equals(today.plusDays(1))) day=context.getString(R.string.service_resource_pause_tomorrow);
		else day=reset.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
		if(reset.toLocalTime().equals(LocalTime.of(8, 0))) return context.getString(R.string.service_resource_pause_message, day);
		String time=reset.format(DateTimeFormatter.ofPattern(reset.getSecond()==0 ? "HH:mm" : "HH:mm:ss"));
		return context.getString(R.string.service_resource_pause_at_time, day, time);
	}

	/** Call once during Application.onCreate, before any Activity resumes. Idempotent on the main looper. */
	public static void install(Application application){ Ui.run(()->Ui.install(application)); }

	public void bind(TextView view){
		if(view==null) return;
		Ui.run(()->{
			Ui.unbind(view);
			// Clearing an existing single-line transformation can call setText internally.
			view.setSingleLine(false); view.setMaxLines(Integer.MAX_VALUE); view.setEllipsize(null);
			Binding binding=new Binding(view, this);
			Ui.bindings.put(view, binding);
			view.addOnAttachStateChangeListener(binding);
			view.addTextChangedListener(binding);
			binding.write(); Ui.schedule();
		});
	}

	/** Also used by ordinary error rendering and recycled views, so old metadata cannot overwrite them. */
	public static void unbind(TextView view){ if(view!=null) Ui.run(()->Ui.unbind(view)); }

	/** Foreground-only, scrollable M3 presentation. No Activity means no unsolicited long toast. */
	public void show(Context context){
		Activity activity=activity(context);
		if(activity==null) return;
		Tracker tracker=Ui.trackers.get(activity.getApplication());
		if(tracker==null || tracker.foreground.get()!=activity) return; // Do not enqueue background errors for a later resume.
		WeakReference<Activity> owner=new WeakReference<>(activity);
		Ui.run(()->{
			Activity host=owner.get();
			if(host==null || host.isFinishing() || host.isDestroyed() || tracker.foreground.get()!=host) return;
			String segment=timing.resetAt.toString();
			DialogState previous=tracker.dialogs.get(host);
			if(previous!=null && segment.equals(previous.segment)) return;
			if(previous!=null) previous.dismiss();
			AlertDialog dialog=new M3AlertDialogBuilder(host).setTitle(R.string.service_resource_pause_title)
					.setMessage(message(host)).setPositiveButton(android.R.string.ok, null).create();
			try{ dialog.show(); }catch(android.view.WindowManager.BadTokenException unavailable){ return; }
			TextView text=dialog.findViewById(android.R.id.message);
			if(text!=null) bind(text); // Framework AlertDialog message lives inside a ScrollView.
			tracker.dialogs.put(host, new DialogState(segment, dialog));
			dialog.setOnDismissListener(ignored->{ if(text!=null) unbind(text); });
		});
	}

	private static Activity activity(Context context){
		for(int depth=0; context instanceof ContextWrapper wrapper && depth<32; depth++){
			if(context instanceof Activity activity) return activity;
			Context next=wrapper.getBaseContext(); if(next==context) break; context=next;
		}
		return context instanceof Activity activity ? activity : null;
	}

	/** Pure Clock/Instant seam. Server time is anchored to elapsed time, not a potentially skewed device clock. */
	static final class Timing{
		final Instant resetAt, observedAt;
		final Clock clock;
		final LongSupplier elapsed;
		final long elapsedAt;
		final boolean serverClock;
		private Timing(Instant resetAt, Instant observedAt, Clock clock, LongSupplier elapsed, boolean serverClock){
			this.resetAt=resetAt; this.observedAt=observedAt; this.clock=clock; this.elapsed=elapsed;
			this.elapsedAt=elapsed.getAsLong(); this.serverClock=serverClock;
		}
		static Timing observe(JsonObject body, Clock clock, LongSupplier elapsed){
			Instant server=iso(string(body, "server_time")), reset=iso(string(body, "reset_at"));
			String source=string(body, "reset_source");
			boolean valid=server!=null && reset!=null
					&& Duration.between(server, reset).abs().compareTo(Duration.ofDays(1))<=0
					&& ("upstream".equals(source) || "estimated_utc_midnight".equals(source)
					&& (reset.equals(server.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
					|| reset.equals(server.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant())));
			Instant observed=valid ? server : clock.instant();
			if(!valid){
				var local=observed.atZone(CHINA);
				reset=local.toLocalDate().atTime(8, 0).atZone(CHINA).toInstant();
				if(!reset.isAfter(observed)) reset=local.toLocalDate().plusDays(1).atTime(8, 0).atZone(CHINA).toInstant();
			}
			return new Timing(reset, observed, clock, elapsed, valid);
		}
		Instant now(){
			if(!serverClock) return clock.instant();
			long delta=Math.max(0, elapsed.getAsLong()-elapsedAt);
			return observedAt.plusMillis(delta);
		}
		long nextChangeMillis(){
			Instant now=now(); if(!now.isBefore(resetAt)) return Long.MAX_VALUE;
			Instant midnight=now.atZone(CHINA).toLocalDate().plusDays(1).atStartOfDay(CHINA).toInstant();
			Instant next=resetAt.isBefore(midnight) ? resetAt : midnight;
			return Math.max(1, Duration.between(now, next).toMillis()+1);
		}
		private static Instant iso(String value){
			if(value==null || value.length()>40 || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z")) return null;
			try{ return Instant.parse(value); }catch(RuntimeException invalid){ return null; }
		}
	}

	private static final class Ui{
		static final Handler handler=new Handler(Looper.getMainLooper());
		static final Map<Application, Tracker> trackers=java.util.Collections.synchronizedMap(new WeakHashMap<>());
		static final Map<TextView, Binding> bindings=new WeakHashMap<>(); // Values have only weak View references.
		static final Runnable tick=()->{
			for(Binding binding:new ArrayList<>(bindings.values())) if(binding.active()) binding.write();
			schedule();
		};
		static void run(Runnable work){ if(Looper.myLooper()==Looper.getMainLooper()) work.run(); else handler.post(work); }
		static void install(Application application){
			if(trackers.containsKey(application)) return;
			Tracker tracker=new Tracker(); trackers.put(application, tracker);
			application.registerActivityLifecycleCallbacks(tracker);
		}
		static void unbind(TextView view){
			Binding old=bindings.remove(view);
			if(old!=null){ view.removeOnAttachStateChangeListener(old); view.removeTextChangedListener(old); }
			schedule();
		}
		static void schedule(){
			handler.removeCallbacks(tick);
			long delay=Long.MAX_VALUE;
			// A GONE ancestor may become visible without attach/resume. Keep only the finite
			// midnight/reset ticks while attached and foreground; never poll hidden views.
			for(Binding binding:new ArrayList<>(bindings.values())) if(binding.active()) delay=Math.min(delay, binding.pause.timing.nextChangeMillis());
			if(delay!=Long.MAX_VALUE) handler.postDelayed(tick, delay);
		}
	}

	private static final class Binding implements View.OnAttachStateChangeListener, TextWatcher{
		final WeakReference<TextView> view;
		final ServiceResourcePause pause;
		boolean writing, attached;
		Binding(TextView view, ServiceResourcePause pause){ this.view=new WeakReference<>(view); this.pause=pause; this.attached=view.isAttachedToWindow(); }
		boolean active(){
			TextView text=view.get(); if(text==null || !attached || !text.isAttachedToWindow()) return false;
			Activity owner=activity(text.getContext()); if(owner==null) return false;
			Tracker tracker=Ui.trackers.get(owner.getApplication());
			return tracker!=null && tracker.foreground.get()==owner && !owner.isFinishing() && !owner.isDestroyed();
		}
		void write(){
			TextView text=view.get(); if(text==null) return;
			writing=true; try{ text.setText(pause.message(text.getContext())); }finally{ writing=false; }
		}
		@Override public void onViewAttachedToWindow(View view){ attached=true; write(); Ui.schedule(); }
		@Override public void onViewDetachedFromWindow(View view){ attached=false; Ui.schedule(); }
		@Override public void onTextChanged(CharSequence s, int start, int before, int count){ if(!writing){ TextView text=view.get(); if(text!=null) Ui.unbind(text); } }
		@Override public void beforeTextChanged(CharSequence s, int start, int count, int after){}
		@Override public void afterTextChanged(Editable text){}
	}

	private static final class DialogState{
		final String segment;
		final WeakReference<AlertDialog> dialog;
		DialogState(String segment, AlertDialog dialog){ this.segment=segment; this.dialog=new WeakReference<>(dialog); }
		void dismiss(){
			AlertDialog current=dialog.get();
			if(current!=null){
				unbind(current.findViewById(android.R.id.message));
				current.dismiss();
			}
		}
	}

	private static final class Tracker implements Application.ActivityLifecycleCallbacks{
		volatile WeakReference<Activity> foreground=new WeakReference<>(null);
		final Map<Activity, DialogState> dialogs=new WeakHashMap<>();
		@Override public void onActivityResumed(Activity activity){
			foreground=new WeakReference<>(activity);
			for(Binding binding:new ArrayList<>(Ui.bindings.values())) if(binding.active()) binding.write();
			Ui.schedule();
		}
		@Override public void onActivityPaused(Activity activity){
			if(foreground.get()==activity) foreground=new WeakReference<>(null);
			DialogState state=dialogs.get(activity); if(state!=null) state.dismiss();
			Ui.schedule();
		}
		@Override public void onActivityDestroyed(Activity activity){ onActivityPaused(activity); dialogs.remove(activity); }
		@Override public void onActivityCreated(Activity activity, Bundle state){}
		@Override public void onActivityStarted(Activity activity){}
		@Override public void onActivityStopped(Activity activity){}
		@Override public void onActivitySaveInstanceState(Activity activity, Bundle state){}
	}
}
