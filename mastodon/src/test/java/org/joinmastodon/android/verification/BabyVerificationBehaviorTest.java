package org.joinmastodon.android.verification;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.content.Intent;
import android.view.LayoutInflater;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.verification.VerificationRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.settings.BabyVerificationFragment;
import org.joinmastodon.android.model.verification.VerificationModels.*;
import org.joinmastodon.android.ui.media.MediaCameraContract;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;

/** Executes the production Fragment callbacks against an in-memory backend; no remote requests. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28, shadows={BabyVerificationBehaviorTest.LocalRequests.class, BabyVerificationBehaviorTest.LocalUploader.class})
public class BabyVerificationBehaviorTest{
    private static final String SESSION="123e4567-e89b-42d3-a456-426614174000";
    private BabyVerificationFragment fragment;
    private Activity activity;
    private File rootAlias;
    private VerificationPendingCapture capture;
    public static class CaptureActivity extends Activity{
        File privateRoot;
        @Override public File getNoBackupFilesDir(){ return privateRoot==null ? super.getNoBackupFilesDir() : privateRoot; }
    }

    @Implements(MastodonAPIRequest.class)
    public static class LocalRequests{
        @org.robolectric.annotation.RealObject MastodonAPIRequest<?> request;
        static final ArrayList<MastodonAPIRequest<?>> requests=new ArrayList<>();
        @Implementation public MastodonAPIRequest<?> exec(String account){ requests.add(request); return request; }
        @Implementation public void cancel(){} // deliberately allow late callbacks
    }
    @Implements(VerificationUploader.class)
    public static class LocalUploader{
        static VerificationUploader.UploadListener listener;
        static int starts;
        @Implementation public void __constructor__(AccountSession session, VerificationUploader.UploadListener value){ listener=value; }
        @Implementation public void start(String id, VerificationImageProcessor.Result photo){ assertNotNull(photo); starts++; }
        @Implementation public void cancel(){}
    }
    private static Field field(Class<?> type,String name) throws Exception{ Field f=type.getDeclaredField(name); f.setAccessible(true); return f; }
    private Object get(String name) throws Exception{ return field(BabyVerificationFragment.class,name).get(fragment); }
    private void invoke(String name,Class<?>[] types,Object... args) throws Exception{ Method m=BabyVerificationFragment.class.getDeclaredMethod(name,types); m.setAccessible(true); m.invoke(fragment,args); }
    @SuppressWarnings("unchecked") private static void success(MastodonAPIRequest<?> request,Object value) throws Exception{
        ((org.joinmastodon.android.model.BaseModel)value).postprocess();
        ((Callback<Object>)field(APIRequest.class,"callback").get(request)).onSuccess(value);
    }
    private static State state(){
        State value=new State(); value.config=new org.joinmastodon.android.model.verification.VerificationModels.Config();
        value.config.version=1; value.config.declarationVersion="v1"; value.config.captureTtlSeconds=600;
        value.config.uploadTtlSeconds=60; value.config.maxEvidenceSize=5*1024*1024;
        value.quota=new Quota(); return value;
    }
    private static CaptureSession completed(){
        CaptureSession value=new CaptureSession(); value.id=SESSION; value.status="completed";
        value.nonce="test-nonce"; value.expiresAt=System.currentTimeMillis()/1000+600; return value;
    }
    @SuppressWarnings("unchecked") private static void error(MastodonAPIRequest<?> request) throws Exception{ ((Callback<Object>)field(APIRequest.class,"callback").get(request)).onError(new VerificationRequest.VerificationError("offline",0,null)); }
    private static String path(MastodonAPIRequest<?> request) throws Exception{ return (String)field(MastodonAPIRequest.class,"path").get(request); }
    private MastodonAPIRequest<?> last(){ return LocalRequests.requests.get(LocalRequests.requests.size()-1); }
    private void content(){ fragment.onCreateContentView(LayoutInflater.from(activity),null,null); }
    private void awaitRequest(String suffix) throws Exception{
        for(int i=0;i<200;i++){ org.robolectric.shadows.ShadowLooper.idleMainLooper(); if(!LocalRequests.requests.isEmpty() && path(last()).endsWith(suffix)) return; Thread.sleep(10); }
        fail("Missing request "+suffix);
    }
    @Before @SuppressWarnings("unchecked") public void setup() throws Exception{
        LocalRequests.requests.clear();
        org.joinmastodon.android.MastodonApp.context=RuntimeEnvironment.getApplication();
        activity=Robolectric.buildActivity(CaptureActivity.class).setup().get();
        activity.setTheme(R.style.Theme_Mastodon_Light);
        VerificationImageProcessor.deleteTree(new File(activity.getNoBackupFilesDir(),"verification"));
        var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true);
        AccountSession session=constructor.newInstance();
        session.domain="offline.example.test"; session.self=new org.joinmastodon.android.model.Account(); session.self.id="42";
        ((Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(AccountSessionManager.getInstance())).put(session.getID(),session);
        fragment=new BabyVerificationFragment(); Bundle args=new Bundle(); args.putString("account",session.getID()); fragment.setArguments(args);
        // Attach without creating AppKit's outer navigation shell; exercise its real content view.
        activity.getFragmentManager().beginTransaction().add(fragment,"verification").commit();
        activity.getFragmentManager().executePendingTransactions();
        if(!(boolean)get("viewReady")) content();
        capture=new VerificationPendingCapture(SESSION,"123456","v1","requirement",System.currentTimeMillis()/1000+600,5*1024*1024, "offline.example.test_42", 42);
        capture.save(activity);
    }
    @After public void cleanup() throws Exception{
        if(fragment!=null) fragment.onDestroyView();
        VerificationImageProcessor.deleteTree(new File(activity.getNoBackupFilesDir(),"verification"));
        if(rootAlias!=null) java.nio.file.Files.deleteIfExists(rootAlias.toPath());
    }
    private File raw() throws Exception{
        File file=capture.rawFile(activity); Bitmap bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
        try(FileOutputStream out=new FileOutputStream(file)){ assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,out)); }
        bitmap.recycle(); return file;
    }
    @Test public void trustedRootAliasRoundTripsCameraAndRecoversRawAndProcessedPhoto() throws Exception{
        File real=activity.getNoBackupFilesDir();
        rootAlias=new File(activity.getCacheDir(),"private-root-alias");
        java.nio.file.Files.createSymbolicLink(rootAlias.toPath(),real.toPath());
        ((CaptureActivity)activity).privateRoot=rootAlias;
        File raw=raw(); assertNotEquals(raw.getAbsolutePath(),raw.getCanonicalPath());
        assertTrue(capture.hasRecoverablePhoto(activity));
        Intent request=MediaCameraContract.createCertificationIntent(activity,SESSION,0,"requirement",60_000);
        assertEquals(raw,MediaCameraContract.readCertificationRequest(activity,request).output());
        fragment.onActivityResult(1901,Activity.RESULT_OK,MediaCameraContract.createCertificationResult(raw.getAbsolutePath(),SESSION,0));
        awaitRequest("/complete"); assertTrue(capture.photoFile(activity).isFile());
        assertTrue(capture.hasRecoverablePhoto(activity));
        assertTrue((boolean)get("cameraResultConsumed"));
    }
    private void rejected(Intent result,int resource) throws Exception{
        int requests=LocalRequests.requests.size();
        fragment.onActivityResult(1901,Activity.RESULT_OK,result);
        assertEquals(activity.getString(resource),((TextView)get("messageBody")).getText().toString());
        assertEquals(activity.getString(R.string.verification_capture_result_error),((TextView)get("messageTitle")).getText().toString());
        assertFalse((boolean)get("cameraResultConsumed"));
        assertEquals(requests,LocalRequests.requests.size());
    }
    private Intent result(){ return MediaCameraContract.createCertificationResult(capture.rawFile(activity).getAbsolutePath(),SESSION,0); }
    @Test public void missingOrCorruptMetadataIsNotReportedAsExpiry() throws Exception{
        raw(); capture.deleteMetadata(activity, "offline.example.test_42", 42); rejected(result(),R.string.verification_capture_state_missing);
        java.nio.file.Files.write(new File(VerificationPendingCapture.root(activity,SESSION),"pending.properties").toPath(),"expires_at=bad".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rejected(result(),R.string.verification_capture_state_missing);
    }
    @Test public void actualExpiryHasDistinctMessageAndDeletesOnlyControlledRaw() throws Exception{
        raw(); new VerificationPendingCapture(SESSION,"123456","v1","requirement",1,5*1024*1024, "offline.example.test_42", 42).save(activity);
        rejected(result(),R.string.verification_capture_expired); assertFalse(capture.rawFile(activity).exists());
    }
    @Test public void sessionSlotPathAndMissingPhotoHaveDistinctSafeMessages() throws Exception{
        field(BabyVerificationFragment.class,"pendingCapture").set(fragment,capture);
        rejected(result().putExtra(MediaCameraContract.EXTRA_CERTIFICATION_SESSION,"123e4567-e89b-42d3-a456-426614174001"),R.string.verification_capture_session_invalid);
        rejected(result().putExtra(MediaCameraContract.EXTRA_CERTIFICATION_SESSION,"../escape"),R.string.verification_capture_session_invalid);
        rejected(result().putExtra(MediaCameraContract.EXTRA_CERTIFICATION_SLOT,1),R.string.verification_capture_slot_invalid);
        rejected(result().putExtra(MediaCameraContract.EXTRA_CONTROLLED_PATH,"/private/photo.jpg"),R.string.verification_capture_path_invalid);
        rejected(result().putExtra(MediaCameraContract.EXTRA_CONTROLLED_PATH,new File(capture.rawFile(activity).getParentFile(),"../"+SESSION+"/raw-0.jpg").getAbsolutePath()),R.string.verification_capture_path_invalid);
        rejected(result(),R.string.verification_capture_photo_missing);
        java.nio.file.Files.write(capture.rawFile(activity).toPath(),new byte[0]);
        rejected(result(),R.string.verification_capture_photo_missing);
    }
    @Test public void symlinkResultNeverReadsOrDeletesOutsidePhoto() throws Exception{
        File target=File.createTempFile("outside",".jpg",activity.getCacheDir());
        java.nio.file.Files.write(target.toPath(),new byte[]{1,2,3});
        try{
            java.nio.file.Files.createSymbolicLink(capture.rawFile(activity).toPath(),target.toPath());
            rejected(result(),R.string.verification_capture_path_invalid);
            assertArrayEquals(new byte[]{1,2,3},java.nio.file.Files.readAllBytes(target.toPath()));
        }finally{ capture.rawFile(activity).delete(); target.delete(); }
    }
    @Test public void lateStateCannotReplaceProcessingAndDuplicateCameraResultCannotCreateTwice() throws Exception{
        var old=new ArrayList<>(LocalRequests.requests);
        File raw=raw(); Intent result=MediaCameraContract.createCertificationResult(raw.getAbsolutePath(),SESSION,0);
        fragment.onActivityResult(1901,Activity.RESULT_OK,result);
        fragment.onActivityResult(1901,Activity.RESULT_OK,result);
        for(var request:old){ if(path(request).endsWith("/me") && !path(request).contains("certificates")) success(request,state()); else success(request,new CertificateEnvelope()); }
        awaitRequest("/complete");
        assertTrue((boolean)get("busy")); assertTrue((boolean)get("localFlow"));
        assertEquals(1,LocalRequests.requests.stream().filter(r->{try{return path(r).endsWith("/complete");}catch(Exception e){throw new RuntimeException(e);}}).count());
        success(last(),completed()); assertTrue(path(last()).endsWith("/applications"));
        error(last()); assertFalse((boolean)get("busy")); assertTrue(capture.photoFile(activity).isFile());
        assertNotNull(VerificationPendingCapture.load(activity,SESSION, "offline.example.test_42", 42));
    }
    @Test public void rawOnlyRecoveryUsesProcessingNotCamera() throws Exception{
        raw(); field(BabyVerificationFragment.class,"pendingCapture").set(fragment,capture);
        field(BabyVerificationFragment.class,"busy").setBoolean(fragment,false);
        invoke("resumePendingCapture",new Class<?>[0]);
        awaitRequest("/complete"); assertTrue(capture.photoFile(activity).isFile());
    }
    @Test public void destroyedViewResetsBusyAndLateCompleteCannotCreateDraft() throws Exception{
        raw(); fragment.onActivityResult(1901,Activity.RESULT_OK,MediaCameraContract.createCertificationResult(capture.rawFile(activity).getAbsolutePath(),SESSION,0));
        awaitRequest("/complete"); var complete=last(); int count=LocalRequests.requests.size();
        fragment.onDestroyView(); assertFalse((boolean)get("busy"));
        content(); success(complete,completed()); assertEquals(count,LocalRequests.requests.size());
        assertEquals(activity.getString(R.string.verification_continue_submit),((android.widget.Button)get("messagePrimary")).getText().toString());
    }
    @Test public void cameraToDraftUploadAndSubmitClearsPhotoOnlyAfterSuccess() throws Exception{
        raw(); LocalUploader.starts=0;
        fragment.onActivityResult(1901,Activity.RESULT_OK,MediaCameraContract.createCertificationResult(capture.rawFile(activity).getAbsolutePath(),SESSION,0));
        awaitRequest("/complete"); success(last(),completed());
        ApplicationResult created=new ApplicationResult(); created.id=SESSION; created.status="draft"; success(last(),created);
        assertEquals(1,LocalUploader.starts); assertTrue(capture.photoFile(activity).isFile());
        EvidenceComplete evidence=new EvidenceComplete(); evidence.id=SESSION; evidence.status="ready"; evidence.verifiedSize=1024; evidence.postprocess();
        LocalUploader.listener.onSuccess(evidence); assertTrue(path(last()).endsWith("/submit"));
        SubmitResult submitted=new SubmitResult(); submitted.id=SESSION; submitted.status="submitted";
        success(last(),submitted); assertFalse(capture.photoFile(activity).exists());
        assertFalse((boolean)get("localFlow"));
    }
    @Test public void uncertainSubmitAlreadySubmittedDoesNotResubmit() throws Exception{
        raw(); field(BabyVerificationFragment.class,"pendingCapture").set(fragment,capture);
        invoke("submitApplication",new Class<?>[]{String.class,boolean.class},SESSION,false);
        error(last()); State state=state(); state.application=new org.joinmastodon.android.model.verification.VerificationModels.Application();
        state.application.id=SESSION; state.application.status="submitted"; success(last(),state);
        assertFalse(capture.rawFile(activity).exists());
        assertEquals(1,LocalRequests.requests.stream().filter(r->{try{return path(r).endsWith("/submit");}catch(Exception e){throw new RuntimeException(e);}}).count());
    }
    @Test public void successfulSubmissionDropsOldDraftReferencesBeforeNextApplication() throws Exception{
        raw(); field(BabyVerificationFragment.class,"pendingCapture").set(fragment,capture);
        ApplicationDetail old=new ApplicationDetail(); old.id=SESSION; old.userId=42; old.captureSessionId=SESSION;
        field(BabyVerificationFragment.class,"currentDetail").set(fragment,old); field(BabyVerificationFragment.class,"applicationId").set(fragment,SESSION);
        invoke("submitApplication",new Class<?>[]{String.class,boolean.class},SESSION,false);
        SubmitResult result=new SubmitResult();result.id=SESSION;result.status="submitted";success(last(),result);
        assertNull(get("currentDetail"));assertNull(get("applicationId"));
    }
    @Test public void uncertainSubmitDifferentLatestApplicationChecksExactOriginalDetail() throws Exception{
        raw(); field(BabyVerificationFragment.class,"pendingCapture").set(fragment,capture);
        invoke("submitApplication",new Class<?>[]{String.class,boolean.class},SESSION,false);error(last());
        State state=state();state.application=new org.joinmastodon.android.model.verification.VerificationModels.Application();state.application.id="123e4567-e89b-42d3-a456-426614174099";state.application.status="draft";success(last(),state);
        assertEquals("/baby-verification/applications/"+SESSION,path(last()));
    }
    @Test public void uncertainSubmitChecksStateBeforeRetryAndPreservesPhoto() throws Exception{
        raw(); field(BabyVerificationFragment.class,"pendingCapture").set(fragment,capture);
        invoke("submitApplication",new Class<?>[]{String.class,boolean.class},SESSION,false);
        error(last()); assertEquals("/baby-verification/me",path(last()));
        error(last()); assertFalse((boolean)get("busy")); assertTrue(capture.rawFile(activity).isFile());
        ((android.widget.Button)get("messagePrimary")).performClick(); assertEquals("/baby-verification/me",path(last()));
    }
}
