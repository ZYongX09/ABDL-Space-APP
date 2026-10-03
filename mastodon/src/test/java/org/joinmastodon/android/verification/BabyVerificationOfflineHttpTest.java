package org.joinmastodon.android.verification;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.content.Intent;
import android.view.LayoutInflater;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import com.google.gson.JsonObject;
import org.joinmastodon.android.R;
import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.*;
import org.joinmastodon.android.fragments.settings.BabyVerificationFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.ui.media.MediaCameraContract;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;

/** Real Fragment, API request/controller/Gson/DTO validation and uploader, loopback HTTP only.
 * Only Android system-trust client creation is shadowed; TLS/device trust is NOT tested.
 * The interceptor reroutes validated first-party/COS URLs, retaining all request headers/body.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, shadows=BabyVerificationOfflineHttpTest.LoopbackClient.class)
public class BabyVerificationOfflineHttpTest {
    static final String CAP="123e4567-e89b-42d3-a456-426614174000", APP="123e4567-e89b-42d3-a456-426614174001", EVID="123e4567-e89b-42d3-a456-426614174002";
    static MockWebServer server;
    static final List<String> paths=Collections.synchronizedList(new ArrayList<>());
    Activity activity; BabyVerificationFragment fragment; String accountId;
    volatile boolean submitted, draft, submitFailOnce, completeFailOnce, invalidStart, readyDraft;
    volatile long size;
    volatile boolean createFailOnce, createCommittedUnknown, invalidSubmit, revoked, captureCompleted, exhausted, evidenceMissingOnce, evidenceUnavailable, failedEvidence, conflictAuthorize;
    volatile String historicalStatus, submitTerminal;
    volatile boolean cancelledSubmitted=true, authorizeVerifying;
    volatile int putStatus=200;
    volatile long detailUserId=42, serverExpiresAt;
    volatile boolean logoutDuringAuthorize, evidenceMismatch;
    static final String CAP_B="123e4567-e89b-42d3-a456-426614174010", APP_B="123e4567-e89b-42d3-a456-426614174011", EVID_B="123e4567-e89b-42d3-a456-426614174012";
    volatile String activeCap=CAP, activeApp=APP, activeEvid=EVID;
    volatile boolean latestMovesToB, evidenceExpiredOnce, missingAfterRenew;
    volatile long verificationStartedAt;
    volatile int uploadLeaseSeconds=60;
    final Map<String,String> historicalDetails=new java.util.concurrent.ConcurrentHashMap<>();
    long evidenceSize(){ File file=new File(VerificationPendingCapture.root(activity,activeCap),"capture.jpg"); return size>0?size:file.isFile()?file.length():123; }
    String detailJson(String status){return "{\"id\":\""+activeApp+"\",\"status\":\""+status+"\",\"decision_note\":\"offline rejection\",\"submitted_at\":"+("draft".equals(status)?"null":"1700000002")+",\"user_id\":"+detailUserId+",\"capture_session_id\":\""+activeCap+"\",\"qq\":\"123456\",\"adult_declaration\":true,\"declaration_version\":\"v1\",\"declared_at\":1700000000,\"evidence\":[{\"id\":\""+activeEvid+"\",\"kind\":\"capture_photo\",\"mime_type\":\"image/jpeg\",\"declared_size\":"+evidenceSize()+",\"status\":\""+(failedEvidence?"failed":readyDraft?"ready":"verifying")+"\",\"verified_size\":"+evidenceSize()+",\"completed_at\":1700000001}]}";}
    String effectiveStatus(){return submitted?(submitTerminal==null?"submitted":submitTerminal):draft?"draft":historicalStatus;}

    @Implements(MastodonAPIController.class)
    public static class LoopbackClient {
        @Implementation protected static OkHttpClient createSystemTrustClient(OkHttpClient ignored) {
            return new OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .addInterceptor(chain->{
                    Request request=chain.request(); String host=request.url().host();
                    if(!host.equals("offline.example.test") && !host.equals(BuildConfig.BABY_VERIFICATION_COS_HOST)) throw new IOException("Unexpected outbound host "+host);
                    return chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath())).build());
                }).build();
        }
    }
    static Field field(Class<?> c,String n)throws Exception { Field f=c.getDeclaredField(n);f.setAccessible(true);return f; }
    Object get(String n)throws Exception {return field(BabyVerificationFragment.class,n).get(fragment);}
    void waitFor(BooleanSupplier condition)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        do {org.robolectric.shadows.ShadowLooper.idleMainLooper();if(condition.getAsBoolean())return;Thread.sleep(20);}while(System.nanoTime()<end);
        fail("Timed out; paths="+paths+"; message="+((TextView)get("messageBody")).getText());
    }
    boolean busy(){try{return (boolean)get("busy");}catch(Exception e){throw new AssertionError(e);}}
    String state(){
        String status=effectiveStatus();
        return "{\"config\":{\"version\":1,\"enabled\":true,\"free_monthly_limit\":3,\"sponsor_monthly_limit\":6,\"capture_ttl_seconds\":600,\"upload_ttl_seconds\":60,\"max_evidence_size\":5242880,\"declaration_version\":\"v1\"},\"quota\":{\"limit\":3,\"used\":"+(exhausted?5:0)+",\"remaining\":"+(exhausted?0:3)+"},\"application\":"+(status==null?"null":"{\"id\":\""+activeApp+"\",\"status\":\""+status+"\",\"decision_note\":\"offline rejection\",\"submitted_at\":"+("cancelled".equals(status)&&!cancelledSubmitted?"null":"1700000002")+"}")+"}";
    }
    MockResponse json(String body){return new MockResponse().setHeader("Content-Type","application/json").setBody(body);}
    String capture(String status){return "{\"id\":\""+activeCap+"\",\"status\":\""+status+"\",\"nonce\":\"offline-nonce\",\"expires_at\":"+(serverExpiresAt>0?serverExpiresAt:System.currentTimeMillis()/1000+600)+",\"instructions_version\":1,\"paper_shape\":\"square\",\"fold_instruction\":\"fold\",\"placement_instruction\":\"place\",\"random_text\":\"TEST\"}";}
    @Before @SuppressWarnings("unchecked") public void setup()throws Exception {
        paths.clear();server=new MockWebServer();
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public MockResponse dispatch(RecordedRequest r){
            String p=r.getPath();paths.add(r.getMethod()+" "+p);
            if(p.endsWith("/certificates/me")) return json(revoked?"{\"certificate\":{\"id\":\""+activeEvid+"\",\"status\":\"revoked\",\"issued_at\":1700000000,\"generation\":1,\"revoked_at\":1700000001,\"revoke_reason\":\"offline revoked\"}}":"{\"certificate\":null}");
            if(p.endsWith("/me"))return json(state());
            if(p.endsWith("/capture-sessions"))return json(invalidStart?"{}":capture("active"));
            if(p.endsWith("/capture-sessions/"+activeCap)) return json(capture(captureCompleted?"completed":"active"));
            if(p.endsWith("/capture-sessions/"+activeCap+"/complete")) {if(completeFailOnce){completeFailOnce=false;return json("{\"code\":\"offline_test\",\"error\":\"retry\"}").setResponseCode(503);}captureCompleted=true;return json(capture("completed"));}
            if(p.endsWith("/applications")){if(createFailOnce){createFailOnce=false;if(createCommittedUnknown)draft=true;return json(createCommittedUnknown?"{}":"{\"error\":\"retry\",\"code\":\"offline_test\"}").setResponseCode(createCommittedUnknown?200:503);}draft=true;return json("{\"id\":\""+activeApp+"\",\"status\":\"draft\"}");}
            String original=historicalDetails.get(p.substring(p.lastIndexOf('/')+1));if(original!=null && r.getMethod().equals("GET"))return json(original);
            if(p.endsWith("/applications/"+activeApp))return json(detailJson("draft"));
            if(p.endsWith("/evidence/authorize")){
                if(missingAfterRenew)evidenceMissingOnce=true;
                if(verificationStartedAt>0 && verificationStartedAt<=System.currentTimeMillis()/1000-120)verificationStartedAt=0;
                if(authorizeVerifying || verificationStartedAt>0)return json("{\"code\":\"evidence_verifying\",\"error\":\"verifying\"}").setResponseCode(409);
                if(conflictAuthorize)return json("{\"code\":\"evidence_conflict\",\"error\":\"conflict\"}").setResponseCode(409);
                JsonObject body=com.google.gson.JsonParser.parseString(r.getBody().readUtf8()).getAsJsonObject();size=body.get("declared_size").getAsLong();
                if(logoutDuringAuthorize){try{((Map<?,?>)field(AccountSessionManager.class,"sessions").get(AccountSessionManager.getInstance())).remove(accountId);}catch(Exception error){throw new AssertionError(error);}}
                JsonObject a=new JsonObject();a.addProperty("evidence_id",activeEvid);a.addProperty("status","pending");a.addProperty("expires_at",System.currentTimeMillis()/1000+uploadLeaseSeconds);a.addProperty("upload_url","https://"+BuildConfig.BABY_VERIFICATION_COS_HOST+"/baby-verification/private/offline.jpg");
                JsonObject h=new JsonObject();h.addProperty("content-length",Long.toString(size));h.addProperty("Content-MD5",body.get("content_md5").getAsString());h.addProperty("content-type","image/jpeg");h.addProperty("authorization","offline-signature");h.addProperty("x-cos-acl","private");h.addProperty("x-cos-forbid-overwrite","true");h.addProperty("x-cos-meta-sha256",body.get("content_sha256").getAsString());a.add("required_headers",h);return json(a.toString());}
            if(r.getMethod().equals("PUT"))return new MockResponse().setResponseCode(putStatus);
            if(p.endsWith("/evidence/"+activeEvid+"/complete")){
                if(evidenceExpiredOnce){evidenceExpiredOnce=false;return json("{\"code\":\"upload_expired\",\"error\":\"expired\"}").setResponseCode(410);}
                if(evidenceMissingOnce){evidenceMissingOnce=false;return json("{\"code\":\"evidence_object_missing\",\"error\":\"missing\"}").setResponseCode(409);}
                if(evidenceMismatch)return json("{\"code\":\"evidence_mismatch\",\"error\":\"mismatch\"}").setResponseCode(422);
                if(evidenceUnavailable)return json("{\"code\":\"verification_unavailable\",\"error\":\"unavailable\"}").setResponseCode(502);
                return json("{\"id\":\""+activeEvid+"\",\"status\":\"ready\",\"verified_size\":"+(size>0?size:(new File(VerificationPendingCapture.root(activity,activeCap),"capture.jpg").isFile()?new File(VerificationPendingCapture.root(activity,activeCap),"capture.jpg").length():123))+"}");}
            if(p.endsWith("/submit")){submitted=true;if(latestMovesToB){historicalDetails.put(activeApp,detailJson("rejected"));activeCap=CAP_B;activeApp=APP_B;activeEvid=EVID_B;submitted=false;draft=true;return json("{\"code\":\"offline_test\",\"error\":\"unknown\"}").setResponseCode(503);}if(invalidSubmit)return json("{}");if(submitFailOnce){submitFailOnce=false;return json("{\"code\":\"offline_test\",\"error\":\"unknown\"}").setResponseCode(503);}return json("{\"id\":\""+activeApp+"\",\"status\":\"submitted\",\"submitted_at\":1700000002}");}
            if(p.endsWith("/applications/"+activeApp+"/cancel")){draft=false;historicalStatus="cancelled";return json("{\"id\":\""+activeApp+"\",\"status\":\"cancelled\",\"cancelled_at\":1700000003}");}
            if(p.endsWith("/rejection-acknowledge"))return json("{\"id\":\""+activeApp+"\",\"status\":\"rejected\"}");
            return json("{\"error\":\"unexpected path\"}").setResponseCode(404);
        }});server.start();
        org.joinmastodon.android.MastodonApp.context=RuntimeEnvironment.getApplication();
        activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.Theme_Mastodon_Light);
        org.robolectric.shadows.ShadowApplication application=org.robolectric.shadow.api.Shadow.extract(RuntimeEnvironment.getApplication());
        application.grantPermissions(Manifest.permission.CAMERA);
        VerificationImageProcessor.deleteTree(new File(activity.getNoBackupFilesDir(),"verification"));
        var ctor=AccountSession.class.getDeclaredConstructor();ctor.setAccessible(true);AccountSession s=ctor.newInstance();s.domain="offline.example.test";s.self=new Account();s.self.id="42";s.token=new Token();s.token.accessToken="offline-token";accountId=s.getID();
        ((Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(AccountSessionManager.getInstance())).put(accountId,s);
    }
    void attach()throws Exception {fragment=new BabyVerificationFragment();Bundle args=new Bundle();args.putString("account",accountId);fragment.setArguments(args);activity.getFragmentManager().beginTransaction().add(fragment,"verification").commit();activity.getFragmentManager().executePendingTransactions();if(!(boolean)get("viewReady"))fragment.onCreateContentView(LayoutInflater.from(activity),null,null);waitFor(()->!busy());}
    VerificationPendingCapture start()throws Exception {
        attach();((EditText)get("qqInput")).setText("123456");((CheckBox)get("adult")).setChecked(true);assertTrue(((Button)get("startButton")).isEnabled());((Button)get("startButton")).performClick();
        waitFor(()->VerificationPendingCapture.load(activity,CAP, accountId, 42)!=null);VerificationPendingCapture c=VerificationPendingCapture.load(activity,CAP, accountId, 42);assertEquals("123456",c.qq());assertEquals("v1",c.declarationVersion());assertTrue(c.requirement().contains("42"));assertTrue(c.expiresAt()>System.currentTimeMillis()/1000);return c;
    }
    void camera(VerificationPendingCapture c)throws Exception {Bitmap b=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(c.rawFile(activity))){assertTrue(b.compress(Bitmap.CompressFormat.JPEG,90,out));}b.recycle();fragment.onActivityResult(1901,Activity.RESULT_OK,MediaCameraContract.createCertificationResult(c.rawFile(activity).getAbsolutePath(),CAP,0));}
    @After public void cleanup()throws Exception {if(fragment!=null)fragment.onDestroyView();if(activity!=null)VerificationImageProcessor.deleteTree(new File(activity.getNoBackupFilesDir(),"verification"));server.shutdown();}
    @Test public void fullStartCameraCreateAuthorizePutCompleteSubmitWithRealJson()throws Exception {
        VerificationPendingCapture c=start();camera(c);waitFor(()->submitted&&!busy());assertFalse(c.photoFile(activity).exists());assertFalse(c.rawFile(activity).exists());assertNull(VerificationPendingCapture.load(activity,CAP, accountId, 42));
        List<RecordedRequest> requests=new ArrayList<>();RecordedRequest r;while((r=server.takeRequest(100,TimeUnit.MILLISECONDS))!=null)requests.add(r);
        RecordedRequest create=requests.stream().filter(x->x.getPath().endsWith("/applications")).findFirst().orElseThrow();JsonObject body=com.google.gson.JsonParser.parseString(create.getBody().readUtf8()).getAsJsonObject();assertEquals(CAP,body.get("capture_session_id").getAsString());assertTrue(body.get("adult_declaration").getAsBoolean());assertEquals("v1",body.get("declaration_version").getAsString());
        RecordedRequest put=requests.stream().filter(x->x.getMethod().equals("PUT")).findFirst().orElseThrow();assertEquals(size,put.getBodySize());assertEquals("private",put.getHeader("x-cos-acl"));assertEquals("true",put.getHeader("x-cos-forbid-overwrite"));assertEquals("offline-signature",put.getHeader("Authorization"));assertEquals(java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("MD5").digest(put.getBody().readByteArray())),put.getHeader("Content-MD5"));
        assertEquals(1,paths.stream().filter(x->x.endsWith("/submit")).count());assertTrue(paths.stream().anyMatch(x->x.endsWith("/evidence/"+EVID+"/complete")));
    }
    @Test public void malformedStartJsonIsRejectedByRealDtoValidation()throws Exception {invalidStart=true;attach();((EditText)get("qqInput")).setText("123456");((CheckBox)get("adult")).setChecked(true);((Button)get("startButton")).performClick();waitFor(()->!busy());assertNull(VerificationPendingCapture.load(activity,CAP, accountId, 42));assertFalse(paths.stream().anyMatch(x->x.endsWith("/applications")));assertTrue(((TextView)get("messageBody")).getText().toString().contains("拍摄会话响应无效"));}
    @Test public void failedCompletePreservesPhotoAndRealRetryFinishes()throws Exception {completeFailOnce=true;VerificationPendingCapture c=start();camera(c);waitFor(()->!busy());assertTrue(c.photoFile(activity).isFile());assertNotNull(VerificationPendingCapture.load(activity,CAP, accountId, 42));assertFalse(draft);((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertEquals(2,paths.stream().filter(x->x.endsWith("/capture-sessions/"+CAP+"/complete")).count());}
    @Test public void uncertainSubmitUsesRealStateAndNeverResubmits()throws Exception {submitFailOnce=true;VerificationPendingCapture c=start();camera(c);waitFor(()->submitted&&!busy());assertEquals(1,paths.stream().filter(x->x.endsWith("/submit")).count());assertFalse(c.photoFile(activity).exists());}
    @Test public void newFragmentRecoversRawFromDiskWithoutManualDtoCallbacks()throws Exception {VerificationPendingCapture c=start();Bitmap b=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(c.rawFile(activity))){assertTrue(b.compress(Bitmap.CompressFormat.JPEG,90,out));}b.recycle();activity.getFragmentManager().beginTransaction().remove(fragment).commit();activity.getFragmentManager().executePendingTransactions();attach();assertEquals(c,get("pendingCapture"));((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertFalse(c.photoFile(activity).exists());}
    @Test public void viewRebuildKeepsRawAndContinuesThroughRealHttp()throws Exception {VerificationPendingCapture c=start();Bitmap b=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(c.rawFile(activity))){assertTrue(b.compress(Bitmap.CompressFormat.JPEG,90,out));}b.recycle();fragment.onDestroyView();fragment.onCreateContentView(LayoutInflater.from(activity),null,null);assertFalse(busy());assertEquals(c,get("pendingCapture"));((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertFalse(c.photoFile(activity).exists());}
    @Test public void actualExpiredCameraResultNeverCompletesOrCreatesDraft()throws Exception {VerificationPendingCapture c=start();new VerificationPendingCapture(CAP,c.qq(),c.declarationVersion(),c.requirement(),System.currentTimeMillis()/1000-1,c.maxEvidenceSize(), accountId, 42).save(activity);camera(c);assertFalse(busy());assertFalse(c.rawFile(activity).exists());assertEquals(activity.getString(R.string.verification_capture_expired),((TextView)get("messageBody")).getText().toString());assertFalse(paths.stream().anyMatch(x->x.endsWith("/complete")||x.endsWith("/applications")));}
    @Test public void serverReadyDraftWithoutLocalPhotoSubmitsWithoutPut()throws Exception {draft=true;readyDraft=true;attach();assertNotNull(get("currentDetail"));((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")||x.endsWith("/evidence/authorize")||x.endsWith("/evidence/"+EVID+"/complete")));}
    @Test public void serverVerifyingDraftWithoutLocalPhotoCompletesBeforeSubmit()throws Exception {draft=true;attach();((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertTrue(paths.stream().anyMatch(x->x.endsWith("/evidence/"+EVID+"/complete")));assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")||x.endsWith("/evidence/authorize")));}
    void recreate()throws Exception {activity.getFragmentManager().beginTransaction().remove(fragment).commit();activity.getFragmentManager().executePendingTransactions();attach();}
    VerificationPendingCapture ownedPhoto()throws Exception{
        VerificationPendingCapture c=new VerificationPendingCapture(CAP,"123456","v1","requirement",System.currentTimeMillis()/1000+600,5242880,accountId,42);c.save(activity);
        Bitmap b=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(c.photoFile(activity))){assertTrue(b.compress(Bitmap.CompressFormat.JPEG,90,out));}b.recycle();return c;
    }
    @Test public void completedCaptureCreateFailureRecreatesAndResumesSameSession()throws Exception{
        createFailOnce=true;VerificationPendingCapture c=start();camera(c);waitFor(()->!busy());assertTrue(captureCompleted);assertFalse(draft);assertTrue(c.photoFile(activity).isFile());
        recreate();((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertEquals(2,paths.stream().filter(x->x.endsWith("/applications")).count());assertEquals(1,paths.stream().filter(x->x.endsWith("/capture-sessions/"+CAP+"/complete")).count());
    }
    @Test public void unknownCreateSuccessChecksStateAndUsesExistingDraft()throws Exception{
        createFailOnce=true;createCommittedUnknown=true;VerificationPendingCapture c=start();camera(c);waitFor(()->!busy());((Button)get("messagePrimary")).performClick();waitFor(()->getDetailPresent());((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertEquals(1,paths.stream().filter(x->x.endsWith("/applications")).count());assertFalse(c.photoFile(activity).exists());
    }
    boolean getDetailPresent(){try{return get("currentDetail")!=null&&!busy();}catch(Exception e){throw new AssertionError(e);}}
    @Test public void rejectedHistoryCannotHideNewOwnedProcessedPending()throws Exception{
        historicalStatus="rejected";captureCompleted=true;ownedPhoto();attach();assertNotNull(get("pendingCapture"));((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());
    }
    @Test public void revokedApprovedAllowsNewAdultApplication()throws Exception{
        revoked=true;historicalStatus="approved";attach();((EditText)get("qqInput")).setText("123456");assertFalse(((Button)get("startButton")).isEnabled());((CheckBox)get("adult")).setChecked(true);assertTrue(((Button)get("startButton")).isEnabled());((Button)get("startButton")).performClick();waitFor(()->getPendingPresent());
    }
    boolean getPendingPresent(){try{return get("pendingCapture")!=null;}catch(Exception e){throw new AssertionError(e);}}
    @Test public void revokedCertificateDoesNotHideDraftOrReview()throws Exception{
        revoked=true;draft=true;readyDraft=true;attach();assertNotNull(get("currentDetail"));((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertEquals(activity.getString(R.string.verification_review_title),((TextView)get("messageTitle")).getText().toString());
    }
    @Test public void downgradedSponsorQuotaLoadsButBlocksStart()throws Exception{
        exhausted=true;revoked=true;historicalStatus="approved";attach();((EditText)get("qqInput")).setText("123456");((CheckBox)get("adult")).setChecked(true);assertFalse(((Button)get("startButton")).isEnabled());assertNotNull(get("currentState"));
    }
    @Test public void malformedSuccessfulSubmitQueriesStateInsteadOfRetrying()throws Exception{
        invalidSubmit=true;VerificationPendingCapture c=start();camera(c);waitFor(()->submitted&&!busy());assertEquals(1,paths.stream().filter(x->x.endsWith("/submit")).count());assertFalse(c.photoFile(activity).exists());
    }
    @Test public void unknownSubmitRejectedIsTerminalAndCleansLocalPhoto()throws Exception{submitTerminal="rejected";submitFailOnce=true;VerificationPendingCapture c=start();camera(c);waitFor(()->submitted&&!busy());assertFalse(c.photoFile(activity).exists());assertEquals(1,paths.stream().filter(x->x.endsWith("/submit")).count());}
    @Test public void unknownSubmitCancelledAfterSubmissionIsTerminal()throws Exception{submitTerminal="cancelled";submitFailOnce=true;VerificationPendingCapture c=start();camera(c);waitFor(()->submitted&&!busy());assertFalse(c.photoFile(activity).exists());}
    @Test public void cancelledOriginalDraftIsNotMistakenForSubmitted()throws Exception{
        submitTerminal="cancelled";cancelledSubmitted=false;submitFailOnce=true;VerificationPendingCapture c=start();camera(c);waitFor(()->submitted&&!busy());
        assertFalse(c.photoFile(activity).exists());assertNull(VerificationPendingCapture.load(activity,CAP,accountId,42));assertEquals(1,paths.stream().filter(x->x.endsWith("/submit")).count());
        assertEquals(activity.getString(R.string.verification_application_cancelled_unsubmitted),((TextView)get("messageBody")).getText().toString());
        ((Button)get("messagePrimary")).performClick();waitFor(()->!busy());((EditText)get("qqInput")).setText("123456");((CheckBox)get("adult")).setChecked(true);assertTrue(((Button)get("startButton")).isEnabled());
    }
    @Test public void explicitMissingEvidenceWithPhotoReauthorizesPutAndSubmits()throws Exception{
        draft=true;evidenceMissingOnce=true;VerificationPendingCapture c=ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertEquals(1,paths.stream().filter(x->x.startsWith("PUT")).count());assertEquals(2,paths.stream().filter(x->x.endsWith("/evidence/"+EVID+"/complete")).count());assertFalse(c.photoFile(activity).exists());
    }
    @Test public void explicitMissingEvidenceWithoutPhotoDoesNotPutOrSubmit()throws Exception{
        draft=true;evidenceMissingOnce=true;attach();((Button)get("messagePrimary")).performClick();waitFor(()->!busy());assertFalse(submitted);assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")||x.endsWith("/evidence/authorize")));assertEquals(activity.getString(R.string.verification_photo_retake_required),((TextView)get("messageTitle")).getText().toString());
    }
    @Test public void oldBackend502IsTransientAndNeverBlindlyReputs()throws Exception{
        draft=true;evidenceUnavailable=true;ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->!busy());assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")||x.endsWith("/evidence/authorize")));assertFalse(submitted);
    }
    @Test public void missingRecoveryMetadataConflictNeverOverwritesObject()throws Exception{
        draft=true;evidenceMissingOnce=true;conflictAuthorize=true;ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->!busy());assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")));assertFalse(submitted);assertTrue(((TextView)get("messageBody")).getText().toString().contains("evidence_conflict"));
    }
    @Test public void failedEvidenceWithoutPhotoDoesNotUploadOrSubmit()throws Exception{
        draft=true;failedEvidence=true;attach();assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")||x.endsWith("/evidence/authorize")||x.endsWith("/submit")));assertEquals(activity.getString(R.string.verification_discard_draft),((Button)get("messagePrimary")).getText().toString());
    }
    @Test public void foreignAccountRawAndProcessedPendingAreNeverRecovered()throws Exception{
        VerificationPendingCapture foreign=new VerificationPendingCapture(CAP,"999999","v1","foreign-private",System.currentTimeMillis()/1000+600,5242880,"other.example_43",43);foreign.save(activity);java.nio.file.Files.write(foreign.rawFile(activity).toPath(),new byte[]{1});java.nio.file.Files.write(foreign.photoFile(activity).toPath(),new byte[]{2});
        attach();assertNull(get("pendingCapture"));fragment.onActivityResult(1901,Activity.RESULT_OK,MediaCameraContract.createCertificationResult(foreign.rawFile(activity).getAbsolutePath(),CAP,0));assertFalse(paths.stream().anyMatch(x->x.endsWith("/complete")||x.endsWith("/applications")));assertTrue(foreign.rawFile(activity).exists());assertTrue(foreign.photoFile(activity).exists());assertFalse(((TextView)get("messageBody")).getText().toString().contains("999999"));
    }
    @Test public void processRecreationNeverInfersLegacyOwnerFromCurrentSession()throws Exception{
        VerificationPendingCapture c=ownedPhoto();java.nio.file.Path file=new File(VerificationPendingCapture.root(activity,CAP),"pending.properties").toPath();String legacy=java.nio.file.Files.readString(file).replaceAll("(?m)^(account_id|user_id)=.*\n","");java.nio.file.Files.writeString(file,legacy);attach();assertNull(get("pendingCapture"));assertFalse(c.photoFile(activity).exists());assertFalse(paths.stream().anyMatch(x->x.endsWith("/applications")));
    }

    @Test public void failedEvidenceWithOwnedPhotoUsesBackendReauthorization()throws Exception{
        draft=true;failedEvidence=true;ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertEquals(1,paths.stream().filter(x->x.endsWith("/evidence/authorize")).count());assertEquals(1,paths.stream().filter(x->x.startsWith("PUT")).count());
    }

    @Test public void failedEvidenceExistingObjectPut409SafelyCompletesWithoutOverwrite()throws Exception{
        draft=true;failedEvidence=true;putStatus=409;ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertEquals(1,paths.stream().filter(x->x.startsWith("PUT")).count());assertEquals(1,paths.stream().filter(x->x.endsWith("/evidence/"+EVID+"/complete")).count());
    }
    @Test public void liveVerifyingAuthorizationNeverStartsPut()throws Exception{
        draft=true;failedEvidence=true;authorizeVerifying=true;ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->!busy());assertFalse(submitted);assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")));assertTrue(((TextView)get("messageBody")).getText().toString().contains("evidence_verifying"));
    }
    @Test public void foreignServerDetailCannotReadCurrentAccountPhotoOrSubmit()throws Exception{
        draft=true;detailUserId=43;VerificationPendingCapture c=ownedPhoto();attach();assertNull(get("currentDetail"));assertTrue(c.photoFile(activity).exists());assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")||x.endsWith("/submit")));assertEquals(activity.getString(R.string.verification_account_changed),((TextView)get("messageBody")).getText().toString());
    }
    @Test public void currentSessionReplacementRejectsLateCameraAndCleanup()throws Exception{
        VerificationPendingCapture c=start();Bitmap b=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(c.rawFile(activity))){assertTrue(b.compress(Bitmap.CompressFormat.JPEG,90,out));}b.recycle();
        ((Map<?,?>)field(AccountSessionManager.class,"sessions").get(AccountSessionManager.getInstance())).remove(accountId);
        fragment.onActivityResult(1901,Activity.RESULT_OK,MediaCameraContract.createCertificationResult(c.rawFile(activity).getAbsolutePath(),CAP,0));
        var m=BabyVerificationFragment.class.getDeclaredMethod("cancelPendingCapture",boolean.class);m.setAccessible(true);m.invoke(fragment,false);
        assertTrue(c.rawFile(activity).exists());assertNotNull(VerificationPendingCapture.load(activity,CAP,accountId,42));assertFalse(paths.stream().anyMatch(x->x.endsWith("/complete")||x.endsWith("/cancel")||x.endsWith("/applications")));
    }

    @Test public void revokedFailedExistingObjectHashMismatchNeverSubmitsOrReputs()throws Exception{
        revoked=true;draft=true;failedEvidence=true;putStatus=409;evidenceMismatch=true;VerificationPendingCapture c=ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->!busy());assertFalse(submitted);assertEquals(1,paths.stream().filter(x->x.startsWith("PUT")).count());assertTrue(c.photoFile(activity).exists());assertTrue(((TextView)get("messageBody")).getText().toString().contains("evidence_mismatch"));
    }
    @Test public void realExpiredCompletedSessionGuidesRetakeNotMalformedResponse()throws Exception{
        captureCompleted=true;ownedPhoto();serverExpiresAt=1;attach();((Button)get("messagePrimary")).performClick();waitFor(()->!busy());assertFalse(draft);assertFalse(paths.stream().anyMatch(x->x.endsWith("/applications")));assertEquals(activity.getString(R.string.verification_capture_expired),((TextView)get("messageBody")).getText().toString());
    }
    @Test public void logoutAtAuthorizationBoundaryNeverReadsOrPutsPhoto()throws Exception{
        draft=true;failedEvidence=true;logoutDuringAuthorize=true;VerificationPendingCapture c=ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->paths.stream().anyMatch(x->x.endsWith("/evidence/authorize")));Thread.sleep(100);org.robolectric.shadows.ShadowLooper.idleMainLooper();assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")||x.endsWith("/submit")));assertTrue(c.photoFile(activity).exists());
    }

    @Test public void sameFragmentSubmittedAThenRejectedThenNewBDiscardsOnlyB()throws Exception{
        draft=true;readyDraft=true;VerificationPendingCapture a=ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertNull(get("currentDetail"));assertNull(get("applicationId"));
        submitted=false;draft=false;historicalStatus="rejected";var refresh=BabyVerificationFragment.class.getDeclaredMethod("loadState");refresh.setAccessible(true);refresh.invoke(fragment);waitFor(()->!busy());
        activeCap=CAP_B;activeApp=APP_B;activeEvid=EVID_B;size=0;captureCompleted=false;readyDraft=false;
        ((EditText)get("qqInput")).setText("123456");((CheckBox)get("adult")).setChecked(true);((Button)get("startButton")).performClick();waitFor(()->VerificationPendingCapture.load(activity,CAP_B,accountId,42)!=null);
        VerificationPendingCapture b=VerificationPendingCapture.load(activity,CAP_B,accountId,42);Bitmap image=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(b.rawFile(activity))){assertTrue(image.compress(Bitmap.CompressFormat.JPEG,90,out));}image.recycle();evidenceUnavailable=true;
        fragment.onActivityResult(1901,Activity.RESULT_OK,MediaCameraContract.createCertificationResult(b.rawFile(activity).getAbsolutePath(),CAP_B,0));waitFor(()->!busy());((Button)get("messageSecondary")).performClick();waitFor(()->!busy());
        assertTrue(paths.stream().anyMatch(x->x.endsWith("/applications/"+APP_B+"/cancel")));assertFalse(paths.stream().anyMatch(x->x.endsWith("/applications/"+APP+"/cancel")));assertFalse(b.photoFile(activity).exists());assertFalse(a.photoFile(activity).exists());
    }
    @Test public void unknownSubmitOriginalARejectedLatestBChecksAWithoutTouchingB()throws Exception{
        latestMovesToB=true;VerificationPendingCapture a=start();
        VerificationPendingCapture b=new VerificationPendingCapture(CAP_B,"654321","v1","B private",System.currentTimeMillis()/1000+600,5242880,accountId,42);b.save(activity);java.nio.file.Files.write(b.photoFile(activity).toPath(),new byte[]{7});
        camera(a);waitFor(()->!busy());assertTrue(paths.stream().anyMatch(x->x.equals("GET /api/v1/baby-verification/applications/"+APP)));assertFalse(a.photoFile(activity).exists());assertTrue(b.photoFile(activity).exists());assertNotNull(VerificationPendingCapture.load(activity,CAP_B,accountId,42));assertFalse(paths.stream().anyMatch(x->x.endsWith("/applications/"+APP_B+"/cancel")||x.endsWith("/applications/"+APP_B+"/submit")));
    }
    @Test public void uploadExpiredWithExistingObjectRenewsThenCompletesWithoutPut()throws Exception{
        draft=true;evidenceExpiredOnce=true;ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertEquals(1,paths.stream().filter(x->x.endsWith("/evidence/authorize")).count());assertEquals(2,paths.stream().filter(x->x.endsWith("/evidence/"+EVID+"/complete")).count());assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")));
    }
    @Test public void uploadExpiredMissingAfterRenewOnlyThenPuts()throws Exception{
        draft=true;evidenceExpiredOnce=true;missingAfterRenew=true;ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertEquals(1,paths.stream().filter(x->x.startsWith("PUT")).count());assertEquals(1,paths.stream().filter(x->x.endsWith("/evidence/authorize")).count());
    }

    @Test public void staleVerifying120SecondsExpiredUpload60SecondsRenewsAndProbes()throws Exception{
        draft=true;evidenceExpiredOnce=true;verificationStartedAt=System.currentTimeMillis()/1000-121;uploadLeaseSeconds=60;ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->submitted&&!busy());assertEquals(0,verificationStartedAt);assertEquals(1,paths.stream().filter(x->x.endsWith("/evidence/authorize")).count());assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")));
    }
    @Test public void expiredLeaseWithLiveVerifyingCannotAuthorizeOrPut()throws Exception{
        draft=true;evidenceExpiredOnce=true;verificationStartedAt=System.currentTimeMillis()/1000-30;ownedPhoto();attach();((Button)get("messagePrimary")).performClick();waitFor(()->!busy());assertFalse(submitted);assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")));assertTrue(((TextView)get("messageBody")).getText().toString().contains("evidence_verifying"));
    }
    @Test public void expiredUploadWithoutPhotoNeverAuthorizesOrPuts()throws Exception{
        draft=true;evidenceExpiredOnce=true;attach();((Button)get("messagePrimary")).performClick();waitFor(()->!busy());assertFalse(submitted);assertFalse(paths.stream().anyMatch(x->x.startsWith("PUT")||x.endsWith("/evidence/authorize")));
    }

}
