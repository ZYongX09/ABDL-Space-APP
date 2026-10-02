package org.joinmastodon.android.fragments.settings;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.requests.verification.VerificationRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;
import org.joinmastodon.android.model.verification.VerificationModels.ApplicationDetail;
import org.joinmastodon.android.model.verification.VerificationModels.ApplicationResult;
import org.joinmastodon.android.model.verification.VerificationModels.CancelResult;
import org.joinmastodon.android.model.verification.VerificationModels.CaptureSession;
import org.joinmastodon.android.model.verification.VerificationModels.Certificate;
import org.joinmastodon.android.model.verification.VerificationModels.CertificateEnvelope;
import org.joinmastodon.android.model.verification.VerificationModels.Evidence;
import org.joinmastodon.android.model.verification.VerificationModels.EvidenceComplete;
import org.joinmastodon.android.model.verification.VerificationModels.State;
import org.joinmastodon.android.model.verification.VerificationModels.Status;
import org.joinmastodon.android.model.verification.VerificationModels.SubmitResult;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.media.MediaCameraContract;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.BabyVerificationProgressView;
import org.joinmastodon.android.verification.VerificationImageProcessor;
import org.joinmastodon.android.verification.VerificationPendingCapture;
import org.joinmastodon.android.verification.VerificationSessionGuard;
import org.joinmastodon.android.verification.VerificationUploader;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.core.view.ViewCompat;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

public class BabyVerificationFragment extends MastodonToolbarFragment{
	private static final int CAMERA_REQUEST=1901, CAMERA_PERMISSION_REQUEST=1902;
	private static final ExecutorService IMAGE_EXECUTOR=Executors.newSingleThreadExecutor(r->{
		Thread thread=new Thread(r, "VerificationImage");
		thread.setDaemon(true);
		return thread;
	});

	private String accountID, applicationId;
	private AccountSession session;
	private VerificationSessionGuard guard;
	private State currentState;
	private Certificate currentCertificate;
	private ApplicationDetail currentDetail;
	private CaptureSession captureSession;
	private VerificationPendingCapture pendingCapture;
	private VerificationImageProcessor.Result photo;
	private VerificationUploader uploader;
	private final ArrayList<VerificationRequest<?>> flowRequests=new ArrayList<>();
	private int imageGeneration, flowGeneration;
	private boolean cameraResultConsumed, reconcileApplication, busy, localFlow, formAvailable, viewReady, resumedOnce;

	private ScrollView scroll;
	private View loadingState, formCard, messageCard;
	private BabyVerificationProgressView progressSteps;
	private TextView loadingText, quotaValue, formNote, messageTitle, messageBody;
	private EditText qqInput;
	private CheckBox adult;
	private Button startButton, messagePrimary, messageSecondary;
	private ProgressBar messageProgress;

	public BabyVerificationFragment(){
		super(R.layout.fragment_baby_verification_shell);
	}

	@Override
	public void onCreate(Bundle state){
		super.onCreate(state);
		accountID=getArguments().getString("account");
		session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		guard=new VerificationSessionGuard(accountID);
		setTitle(R.string.verification_title);
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		View view=inflater.inflate(R.layout.fragment_baby_verification, container, false);
		scroll=view.findViewById(R.id.verification_scroll);
		progressSteps=view.findViewById(R.id.verification_progress_steps);
		loadingState=view.findViewById(R.id.verification_loading_state);
		loadingText=view.findViewById(R.id.verification_loading_text);
		formCard=view.findViewById(R.id.verification_form_card);
		quotaValue=view.findViewById(R.id.verification_quota_value);
		formNote=view.findViewById(R.id.verification_form_note);
		qqInput=view.findViewById(R.id.verification_qq_input);
		adult=view.findViewById(R.id.verification_adult_confirm);
		startButton=view.findViewById(R.id.verification_start_button);
		messageCard=view.findViewById(R.id.verification_message_card);
		messageTitle=view.findViewById(R.id.verification_message_title);
		messageBody=view.findViewById(R.id.verification_message_body);
		messageProgress=view.findViewById(R.id.verification_message_progress);
		messagePrimary=view.findViewById(R.id.verification_message_primary);
		messageSecondary=view.findViewById(R.id.verification_message_secondary);
		ViewCompat.setAccessibilityHeading(view.findViewById(R.id.verification_hero_title), true);
		ViewCompat.setAccessibilityHeading(view.findViewById(R.id.verification_quota_title), true);
		ViewCompat.setAccessibilityHeading(messageTitle, true);
		qqInput.setSaveFromParentEnabled(false);
		adult.setSaveFromParentEnabled(false);
		adult.setOnCheckedChangeListener((button, checked)->updateStartEnabled());
		qqInput.addTextChangedListener(new TextWatcher(){
			@Override public void beforeTextChanged(CharSequence value, int start, int count, int after){}
			@Override public void onTextChanged(CharSequence value, int start, int before, int count){ updateStartEnabled(); }
			@Override public void afterTextChanged(Editable value){}
		});
		startButton.setOnClickListener(v->start());
		viewReady=true;
		if(ownsCapture(pendingCapture) && applicationId==null && !reconcileApplication){
			localFlow=true;
			showPendingCapture();
		}else loadState();
		return view;
	}

	@Override
	protected void updateToolbar(){
		super.updateToolbar();
		Toolbar toolbar=getToolbar();
		if(toolbar==null) return;
		toolbar.setBackgroundColor(UiUtils.getThemeColor(getActivity(), R.attr.colorVerificationTopSurface));
		toolbar.setTitleTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorVerificationTextPrimary));
		Drawable navigation=toolbar.getNavigationIcon();
		if(navigation!=null){
			navigation=navigation.mutate();
			navigation.setTint(UiUtils.getThemeColor(getActivity(), R.attr.colorVerificationTextSecondary));
			toolbar.setNavigationIcon(navigation);
		}
	}

	@Override
	public void onResume(){
		super.onResume();
		if(!resumedOnce){ resumedOnce=true; return; }
		if(viewReady && !busy && !localFlow) loadState();
	}

	private void loadState(){
		if(!viewReady || (localFlow && busy)) return;
		localFlow=false;
		if(!sessionValid()){
			busy=false;
			showMessage(BabyVerificationProgressView.STAGE_INFORMATION, false, getString(R.string.verification_state_error), getString(R.string.verification_account_changed), false, 0, null, 0, null);
			return;
		}
		busy=true;
		showLoading(stageForState(currentState), R.string.verification_status_loading);
		int generation=guard.nextGeneration();
		final State[] stateResult={null};
		final CertificateEnvelope[] certificateResult={null};
		final ErrorResponse[] firstError={null};
		final int[] remaining={2};
		VerificationRequest<State> stateRequest=guard.track(VerificationRequest.state());
		VerificationRequest<CertificateEnvelope> certificateRequest=guard.track(VerificationRequest.certificate());
		Runnable finish=()->{
			if(--remaining[0]!=0 || !guard.live(generation) || !sessionValid() || localFlow) return;
			if(firstError[0]!=null || stateResult[0]==null || certificateResult[0]==null){
				busy=false;
				showMessage(stageForState(currentState), false, getString(R.string.verification_state_error), errorText(firstError[0]), false,
						R.string.verification_retry, v->loadState(), 0, null);
				return;
			}
			currentState=stateResult[0];
			reconcileApplication=false;
			currentCertificate=certificateResult[0].certificate;
			renderState(currentState, currentCertificate, generation);
		};
		stateRequest.setCallback(new Callback<>(){
			@Override public void onSuccess(State state){ guard.done(stateRequest); stateResult[0]=state; finish.run(); }
			@Override public void onError(ErrorResponse error){ guard.done(stateRequest); if(firstError[0]==null) firstError[0]=error; finish.run(); }
		}).exec(accountID);
		certificateRequest.setCallback(new Callback<>(){
			@Override public void onSuccess(CertificateEnvelope result){ guard.done(certificateRequest); certificateResult[0]=result; finish.run(); }
			@Override public void onError(ErrorResponse error){ guard.done(certificateRequest); if(firstError[0]==null) firstError[0]=error; finish.run(); }
		}).exec(accountID);
	}

	private void renderState(State state, Certificate certificate, int generation){
		if(certificate!=null && certificate.isActive()){
			busy=false;
			String detail=getString(R.string.verification_certificate_active_summary, certificate.generation);
			String application=applicationSummary(state);
			if(application!=null) detail=getString(R.string.verification_application_with_certificate, application, detail);
			showMessage(BabyVerificationProgressView.STAGE_REVIEW, state.status()==Status.APPROVED, state.status()==Status.APPROVED ? getString(R.string.verification_status_approved) : applicationTitle(state), detail, false,
					R.string.verification_certificate_title, v->openCertificate(certificate), R.string.verification_refresh, v->loadState());
			return;
		}
		switch(state.status()){
			case APPROVED -> {
				busy=false;
				if(certificate!=null && certificate.isRevoked()){ showPendingOrForm(state, revokedDetail(certificate)); return; }
				showMessage(BabyVerificationProgressView.STAGE_REVIEW, false, getString(R.string.verification_status_approved), getString(R.string.verification_certificate_pending_sync), false,
						R.string.verification_refresh, v->loadState(), 0, null);
			}
			case SUBMITTED, REVIEWING -> {
				busy=false;
				showMessage(BabyVerificationProgressView.STAGE_REVIEW, false, getString(R.string.verification_review_title), getString(R.string.verification_review_body), false,
						R.string.verification_refresh, v->loadState(), 0, null);
			}
			case DRAFT -> loadDraftDetail(state.application.id, generation);
			case REJECTED -> {
				acknowledgeRejection(state.application.id);
				busy=false;
				showPendingOrForm(state, getString(R.string.verification_rejected_title)+"："+state.application.decisionNote);
			}
			case NOT_STARTED, CANCELLED -> {
				busy=false;
				showPendingOrForm(state, certificate!=null && certificate.isRevoked() ? revokedDetail(certificate) : null);
			}
		}
	}

	private void loadDraftDetail(String id, int generation){
		busy=true;
		showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_draft_title), getString(R.string.verification_draft_body), true, 0, null, 0, null);
		VerificationRequest<ApplicationDetail> request=guard.track(VerificationRequest.detail(id));
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(ApplicationDetail detail){
				guard.done(request);
				if(!guard.live(generation) || !sessionValid() || localFlow) return;
				if(detail.parsedStatus!=Status.DRAFT){ loadState(); return; }
				if(!ownsDetail(detail)){ busy=false; showAccountMismatch(); return; }
				currentDetail=detail;
				applicationId=detail.id;
				pendingCapture=VerificationPendingCapture.load(getActivity(), detail.captureSessionId, accountID, ownerUserId());
				busy=false;
				showDraft(detail);
			}
			@Override public void onError(ErrorResponse error){
				guard.done(request);
				if(!guard.live(generation) || !sessionValid() || localFlow) return;
				busy=false;
				showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_draft_title), errorText(error), false,
						R.string.verification_retry, v->loadState(), 0, null);
			}
		}).exec(accountID);
	}

	private void showForm(State state, String note){
		showOnly(formCard);
		progressSteps.setStage(BabyVerificationProgressView.STAGE_INFORMATION, false);
		quotaValue.setText(getString(R.string.verification_quota_remaining, state.quota.remaining, state.quota.limit));
		formAvailable=state.canStart(currentCertificate);
		if(note!=null && !note.isBlank()) formNote.setText(note);
		else if(!state.config.enabled) formNote.setText(R.string.verification_service_disabled);
		else if(state.quota.remaining<=0) formNote.setText(R.string.verification_quota_exhausted);
		else formNote.setText(R.string.verification_privacy_note);
		updateStartEnabled();
		scroll.post(()->scroll.scrollTo(0, 0));
	}

	private void showPendingCapture(){
		if(!ownsCapture(pendingCapture)){ showAccountMismatch(); return; }
		if(pendingCapture.expiresAt()<=System.currentTimeMillis()/1000){ showExpiredCapture(); return; }
		boolean hasPhoto=pendingCapture.hasRecoverablePhoto(getActivity());
		showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_capture_title),
				hasPhoto ? getString(R.string.verification_processing_photo_body) : pendingCapture.requirement(), false,
				hasPhoto ? R.string.verification_continue_submit : R.string.verification_continue_capture,
				hasPhoto ? v->resumePendingCapture() : v->launchCamera(),
				R.string.verification_discard_draft, v->cancelPendingCapture(true));
	}

	private void showDraft(ApplicationDetail detail){
		Evidence evidence=detail.evidence("capture_photo");
		File localPhoto=localPhoto(detail);
		String body=evidence==null && !localPhoto.isFile() ? getString(R.string.verification_draft_missing_photo) : getString(R.string.verification_draft_body);
		boolean recoverable=(evidence!=null && !"failed".equals(evidence.status)) || localPhoto.isFile();
		showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_draft_title), body, false,
				recoverable ? R.string.verification_continue_submit : R.string.verification_discard_draft,
				recoverable ? v->continueDraft(detail) : v->confirmDiscardDraft(detail),
				recoverable ? R.string.verification_discard_draft : 0,
				recoverable ? v->confirmDiscardDraft(detail) : null);
	}

	private void start(){
		if(!sessionValid() || busy || currentState==null || !formAvailable || !adult.isChecked()) return;
		String qq=qqInput.getText().toString().trim();
		if(!qq.matches("\\d{5,20}")){
			qqInput.setError(getString(R.string.verification_qq_invalid));
			qqInput.requestFocus();
			return;
		}
		busy=true;
		updateStartEnabled();
		showMessage(BabyVerificationProgressView.STAGE_INFORMATION, false, getString(R.string.verification_capture_preparing), getString(R.string.verification_retry_safe), true, 0, null, 0, null);
		int generation=guard.nextGeneration();
		VerificationRequest<CaptureSession> request=guard.track(VerificationRequest.start());
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(CaptureSession result){
				guard.done(request);
				if(!guard.live(generation) || !sessionValid()) return;
				captureSession=result;
				String requirement=result.requirement(session.self.id, qq);
				pendingCapture=new VerificationPendingCapture(result.id, qq, currentState.config.declarationVersion, requirement, result.expiresAt, currentState.config.maxEvidenceSize, accountID, ownerUserId());
				try{
					pendingCapture.save(getActivity());
					launchCamera();
				}catch(Exception error){
					cancelPendingCapture(false);
					busy=false;
					showMessage(BabyVerificationProgressView.STAGE_INFORMATION, false, getString(R.string.verification_state_error), error.getMessage(), false,
							R.string.verification_retry, v->renderState(currentState, currentCertificate, guard.nextGeneration()), 0, null);
				}
			}
			@Override public void onError(ErrorResponse error){
				guard.done(request);
				if(!guard.live(generation) || !sessionValid()) return;
				busy=false;
				showMessage(BabyVerificationProgressView.STAGE_INFORMATION, false, getString(R.string.verification_state_error), errorText(error), false,
						R.string.verification_retry, v->renderState(currentState, currentCertificate, guard.nextGeneration()), 0, null);
			}
		}).exec(accountID);
	}

	private void launchCamera(){
		if(pendingCapture==null) pendingCapture=findPendingCapture();
		if(!ownsCapture(pendingCapture)){ loadState(); return; }
		if(getActivity().checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
			requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
			return;
		}
		long duration=pendingCapture.expiresAt()*1000-System.currentTimeMillis();
		if(duration<=0){
			cancelPendingCapture(true);
			Toast.makeText(getActivity(), R.string.verification_capture_expired, Toast.LENGTH_LONG).show();
			return;
		}
		beginLocalFlow();
		cameraResultConsumed=false;
		busy=true;
		showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_capture_title), pendingCapture.requirement(), false, 0, null, 0, null);
		Intent intent=MediaCameraContract.createCertificationIntent(getActivity(), pendingCapture.sessionId(), 0, pendingCapture.requirement(), duration);
		intent.putExtra(MediaCameraContract.EXTRA_CERTIFICATION_DEADLINE, pendingCapture.expiresAt()*1000);
		startActivityForResult(intent, CAMERA_REQUEST);
	}

	@Override
	public void onRequestPermissionsResult(int code, String[] permissions, int[] results){
		super.onRequestPermissionsResult(code, permissions, results);
		if(code!=CAMERA_PERMISSION_REQUEST) return;
		if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) launchCamera();
		else{
			cancelPendingCapture(false);
			busy=false;
			showMessage(BabyVerificationProgressView.STAGE_INFORMATION, false, getString(R.string.verification_state_error), getString(R.string.verification_camera_permission_denied), false,
					R.string.verification_retry, v->loadState(), 0, null);
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data){
		super.onActivityResult(requestCode, resultCode, data);
		if(requestCode!=CAMERA_REQUEST || cameraResultConsumed) return;
		String resultSession=MediaCameraContract.getCertificationSession(data);
		if(!sessionValid()){ busy=false; return; }
		if(resultCode!=Activity.RESULT_OK){ cancelPendingCapture(true); return; }
		String path=MediaCameraContract.getControlledPath(data);
		int slot=MediaCameraContract.getCertificationSlot(data);
		VerificationPendingCapture restored=VerificationPendingCapture.load(getActivity(), resultSession, accountID, ownerUserId());
		int failure=0;
		File expected=null;
		if(resultSession==null || (pendingCapture!=null && !pendingCapture.sessionId().equals(resultSession)))
			failure=R.string.verification_capture_session_invalid;
		else if(slot!=0) failure=R.string.verification_capture_slot_invalid;
		else{
			try{
				expected=MediaCameraContract.certificationPhotoFile(getActivity(), resultSession, slot);
				if(path==null || !expected.getAbsolutePath().equals(path) || !MediaCameraContract.isControlledFile(getActivity(), expected))
					failure=R.string.verification_capture_path_invalid;
			}catch(IllegalArgumentException ignored){ failure=R.string.verification_capture_session_invalid; }
			catch(java.io.IOException ignored){ failure=R.string.verification_capture_path_invalid; }
			if(failure==0){
				if(restored==null) failure=R.string.verification_capture_state_missing;
				else if(restored.expiresAt()<=System.currentTimeMillis()/1000){
					failure=R.string.verification_capture_expired;
					expected.delete();
				}else if(!expected.isFile() || expected.length()==0) failure=R.string.verification_capture_photo_missing;
			}
		}
		if(failure!=0){
			busy=false;
			if(!viewReady) return;
			showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_capture_result_error), getString(failure), false,
					R.string.verification_retry, v->loadState(), 0, null);
			return;
		}
		pendingCapture=restored;
		cameraResultConsumed=true;
		if(!viewReady){ busy=false; return; }
		processCameraPhoto(new File(path), restored);
	}

	private void processCameraPhoto(File raw, VerificationPendingCapture capture){
		if(!ownsCapture(capture)){ showAccountMismatch(); return; }
		beginLocalFlow();
		busy=true;
		showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_processing_photo_title), getString(R.string.verification_processing_photo_body), true, 0, null, 0, null);
		int generation=++imageGeneration;
		File destination=capture.photoFile(getActivity());
		try{
			if(!MediaCameraContract.isControlledFile(getActivity(), raw) || !MediaCameraContract.isControlledFile(getActivity(), destination)
					|| !MediaCameraContract.isControlledFile(getActivity(), new File(destination.getParentFile(), destination.getName()+".part")))
				throw new java.io.IOException();
		}catch(java.io.IOException ignored){
			busy=false;
			showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_capture_result_error), getString(R.string.verification_capture_path_invalid), false,
					R.string.verification_retry, v->loadState(), 0, null);
			return;
		}
		IMAGE_EXECUTOR.execute(()->{
			VerificationImageProcessor.Result result=null;
			Exception failure=null;
			try{
				if(!ownsCapture(capture)) return;
				result=raw.isFile() ? VerificationImageProcessor.process(raw, destination, capture.maxEvidenceSize())
						: VerificationImageProcessor.inspectProcessed(destination, capture.maxEvidenceSize());
			}
			catch(Exception error){ failure=error; }
			// Keep raw on failure so a destroyed view can retry processing.
			if(result!=null && ownsCapture(capture)) raw.delete();
			VerificationImageProcessor.Result finalResult=result;
			Exception finalFailure=failure;
			Activity activity=getActivity();
			if(activity!=null) activity.runOnUiThread(()->{
				if(!sessionValid() || generation!=imageGeneration) return;
				if(finalFailure!=null){
					busy=false;
					showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_state_error), finalFailure.getMessage(), false,
							R.string.verification_retry, v->launchCamera(), R.string.verification_discard_draft, v->cancelPendingCapture(true));
					return;
				}
				photo=finalResult;
				completeCapture(capture);
			});
		});
	}

	private void resumePendingCapture(){
		if(busy || !ownsCapture(pendingCapture) || !pendingCapture.hasRecoverablePhoto(getActivity())) return;
		if(pendingCapture.expiresAt()<=System.currentTimeMillis()/1000){ showExpiredCapture(); return; }
		try{
			File processed=pendingCapture.photoFile(getActivity());
			if(MediaCameraContract.isControlledFile(getActivity(), processed) && processed.isFile() && processed.length()>0) resumeProcessedCapture();
			else if(MediaCameraContract.isControlledFile(getActivity(), pendingCapture.rawFile(getActivity()))) processCameraPhoto(pendingCapture.rawFile(getActivity()), pendingCapture);
		}catch(java.io.IOException ignored){ busy=false; }
	}

	private void beginLocalFlow(){
		localFlow=true;
		flowGeneration++;
		guard.nextGeneration(); // Discard /me, /certificates and detail responses from before the camera result.
	}

	private boolean flowLive(int token){ return token==flowGeneration && sessionValid(); }

	private void resumeProcessedCapture(){
		if(!ownsCapture(pendingCapture)) return;
		beginLocalFlow();
		busy=true;
		showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_processing_photo_title), getString(R.string.verification_processing_photo_body), true, 0, null, 0, null);
		int generation=++imageGeneration;
		VerificationPendingCapture capture=pendingCapture;
		File file=capture.photoFile(getActivity());
		IMAGE_EXECUTOR.execute(()->{
			VerificationImageProcessor.Result result=null;
			Exception failure=null;
			try{ if(!ownsCapture(capture)) return; result=VerificationImageProcessor.inspectProcessed(file, capture.maxEvidenceSize()); }
			catch(Exception error){ failure=error; }
			VerificationImageProcessor.Result finalResult=result;
			Exception finalFailure=failure;
			Activity activity=getActivity();
			if(activity!=null) activity.runOnUiThread(()->{
				if(!sessionValid() || generation!=imageGeneration) return;
				if(finalFailure!=null){ busy=false; showPendingCapture(); return; }
				photo=finalResult;
				probeCapture(capture);
			});
		});
	}

	private void probeCapture(VerificationPendingCapture capture){
		if(!ownsCapture(capture)){ showAccountMismatch(); return; }
		if(capture.expiresAt()<=System.currentTimeMillis()/1000){ showExpiredCapture(); return; }
		beginLocalFlow();
		int token=flowGeneration;
		busy=true;
		VerificationRequest<CaptureSession> request=VerificationRequest.capture(capture.sessionId());
		flowRequests.add(request);
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(CaptureSession result){
				flowRequests.remove(request);
				if(!flowLive(token)) return;
				if(!capture.sessionId().equals(result.id)){ showAccountMismatch(); return; }
				if(result.expiresAt<=System.currentTimeMillis()/1000 || "expired".equals(result.status) || "cancelled".equals(result.status)){ showExpiredCapture(); return; }
				if("completed".equals(result.status)) createApplication(capture);
				else completeCapture(capture);
			}
			@Override public void onError(ErrorResponse error){
				flowRequests.remove(request);
				if(!flowLive(token)) return;
				busy=false;
				showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_state_error), errorText(error), false,
						R.string.verification_retry, v->probeCapture(capture), R.string.verification_discard_draft, v->cancelPendingCapture(true));
			}
		}).exec(accountID);
	}

	private void completeCapture(VerificationPendingCapture capture){
		if(!ownsCapture(capture)){ showAccountMismatch(); return; }
		if(capture.expiresAt()<=System.currentTimeMillis()/1000){ showExpiredCapture(); return; }
		beginLocalFlow();
		int token=flowGeneration;
		busy=true;
		showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_processing_photo_title), getString(R.string.verification_processing_photo_body), true, 0, null, 0, null);
		VerificationRequest<CaptureSession> request=VerificationRequest.finishCapture(capture.sessionId());
		flowRequests.add(request);
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(CaptureSession result){
				flowRequests.remove(request);
				if(!flowLive(token)) return;
				if(!capture.sessionId().equals(result.id) || !"completed".equals(result.status)){ busy=false; probeCapture(capture); return; }
				if(result.expiresAt<=System.currentTimeMillis()/1000){ showExpiredCapture(); return; }
				createApplication(capture);
			}
			@Override public void onError(ErrorResponse error){
				flowRequests.remove(request);
				if(!flowLive(token)) return;
				busy=false;
				showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_state_error), errorText(error), false,
						R.string.verification_retry, v->probeCapture(capture), R.string.verification_discard_draft, v->cancelPendingCapture(true));
			}
		}).exec(accountID);
	}

	private void createApplication(VerificationPendingCapture capture){
		if(!ownsCapture(capture)){ showAccountMismatch(); return; }
		if(capture.expiresAt()<=System.currentTimeMillis()/1000){ showExpiredCapture(); return; }
		reconcileApplication=true; // Cancellation/lost response may still have created a server draft.
		busy=true;
		beginLocalFlow();
		int token=flowGeneration;
		VerificationRequest<ApplicationResult> request=VerificationRequest.createApplication(capture.sessionId(), capture.qq(), capture.declarationVersion());
		flowRequests.add(request);
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(ApplicationResult result){
				flowRequests.remove(request);
				if(!flowLive(token)) return;
				if(!"draft".equals(result.status)){ busy=false; localFlow=false; loadState(); return; }
				applicationId=result.id;
				currentDetail=null; // Do not carry an earlier application's detail into this draft.
				// Retain owner metadata until terminal cleanup, including process recreation.
				startUploader(result.id, photo, VerificationUploader.Recovery.prepared());
			}
			@Override public void onError(ErrorResponse error){
				flowRequests.remove(request);
				if(!flowLive(token)) return;
				busy=false;
				showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_state_error), errorText(error), false,
						R.string.verification_retry, v->loadState(), R.string.verification_discard_draft, v->cancelPendingCapture(true));
			}
		}).exec(accountID);
	}

	private void continueDraft(ApplicationDetail detail){
		if(busy || !ownsDetail(detail)) return;
		Evidence existing=detail.evidence("capture_photo");
		if(existing!=null && "failed".equals(existing.status) && !localPhoto(detail).isFile()){ showDraft(detail); return; }
		beginLocalFlow();
		applicationId=detail.id;
		VerificationUploader.Recovery recovery=VerificationUploader.Recovery.fromDetail(detail, "capture_photo");
		File file=localPhoto(detail);
		if(file.isFile()){
			final VerificationUploader.Recovery resumeRecovery=recovery;
			busy=true;
			showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_processing_photo_title), getString(R.string.verification_processing_photo_body), true, 0, null, 0, null);
			int generation=++imageGeneration;
			long maxSize=currentState==null ? VerificationImageProcessor.MAX_BYTES : currentState.config.maxEvidenceSize;
			IMAGE_EXECUTOR.execute(()->{
				VerificationImageProcessor.Result result=null;
				Exception failure=null;
				try{ if(!ownsDetail(detail) || !file.equals(localPhoto(detail))) return; result=VerificationImageProcessor.inspectProcessed(file, maxSize); }
				catch(Exception error){ failure=error; }
				VerificationImageProcessor.Result finalResult=result;
				Exception finalFailure=failure;
				Activity activity=getActivity();
				if(activity!=null) activity.runOnUiThread(()->{
					if(!sessionValid() || generation!=imageGeneration) return;
					photo=finalResult;
					if(finalFailure!=null && resumeRecovery.phase==VerificationUploader.Phase.PREPARED){ busy=false; showDraft(detail); return; }
					startUploader(detail.id, finalResult, resumeRecovery);
				});
			});
		}else if(recovery.phase!=VerificationUploader.Phase.PREPARED){
			startUploader(detail.id, null, recovery);
		}else{
			showDraft(detail);
		}
	}

	private void startUploader(String id, VerificationImageProcessor.Result image, VerificationUploader.Recovery recovery){
		if(uploader!=null) return;
		beginLocalFlow();
		int token=flowGeneration;
		busy=true;
		showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_status_uploading), getString(R.string.verification_uploading_body), true, 0, null, 0, null);
		uploader=new VerificationUploader(session, new VerificationUploader.UploadListener(){
			@Override public void onStateChanged(VerificationUploader.State state, VerificationUploader.Recovery value){
				if(!flowLive(token)) return;
				if(state==VerificationUploader.State.AUTHORIZING || state==VerificationUploader.State.UPLOADING || state==VerificationUploader.State.COMPLETING)
					showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_status_uploading), getString(R.string.verification_uploading_body), true, 0, null, 0, null);
			}
			@Override public void onSuccess(EvidenceComplete result){
				if(!flowLive(token)) return;
				uploader=null;
				submitApplication(id, false);
			}
			@Override public void onError(VerificationUploader.UploadError error){
				if(!flowLive(token)) return;
				uploader=null;
				busy=false;
				handleUploadError(id, image, error);
			}
		});
		try{
			if(recovery.phase==VerificationUploader.Phase.PREPARED) uploader.start(id, image);
			else uploader.resume(id, image, recovery);
		}catch(RuntimeException error){
			uploader=null;
			busy=false;
			showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_state_error), error.getMessage(), false,
					R.string.verification_retry, v->continueCurrentDraft(), R.string.verification_discard_draft, v->confirmDiscardCurrentDraft());
		}
	}

	private void handleUploadError(String id, VerificationImageProcessor.Result image, VerificationUploader.UploadError error){
		if(error.requiresNewCapture || error.requiresLocalImage){
			showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_photo_retake_required), errorText(error), false,
					R.string.verification_discard_draft, v->confirmDiscardCurrentDraft(), R.string.verification_refresh, v->loadState());
			return;
		}
		View.OnClickListener retry=error.retryable && error.recovery!=null ? v->startUploader(id, image, error.recovery) : v->loadState();
		showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_state_error), errorText(error), false,
				R.string.verification_retry, retry, R.string.verification_discard_draft, v->confirmDiscardCurrentDraft());
	}

	private void submitApplication(String id, boolean retriedAfterCheck){
		flowGeneration++; // Ignore any queued upload notifications after the submit boundary.
		localFlow=true;
		busy=true;
		showMessage(BabyVerificationProgressView.STAGE_REVIEW, false, getString(R.string.verification_submission_checking), getString(R.string.verification_retry_safe), true, 0, null, 0, null);
		int generation=guard.nextGeneration();
		VerificationRequest<SubmitResult> request=guard.track(VerificationRequest.submit(id));
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(SubmitResult result){
				guard.done(request);
				if(!guard.live(generation) || !sessionValid()) return;
				deleteCurrentLocalFiles();
				busy=false;
				localFlow=false;
				loadState();
			}
			@Override public void onError(ErrorResponse error){
				guard.done(request);
				if(!guard.live(generation) || !sessionValid()) return;
				if(error instanceof VerificationRequest.VerificationError value && value.outcomeUnknown){
					checkSubmitOutcome(id, !retriedAfterCheck);
					return;
				}
				busy=false;
				showMessage(BabyVerificationProgressView.STAGE_REVIEW, false, getString(R.string.verification_state_error), errorText(error), false,
						R.string.verification_retry, v->checkSubmitOutcome(id, true), R.string.verification_refresh, v->loadState());
			}
		}).exec(accountID);
	}

	private void checkSubmitOutcome(String id, boolean allowRetry){
		busy=true;
		showMessage(BabyVerificationProgressView.STAGE_REVIEW, false, getString(R.string.verification_submission_checking), getString(R.string.verification_retry_safe), true, 0, null, 0, null);
		int generation=guard.nextGeneration();
		VerificationRequest<State> request=guard.track(VerificationRequest.state());
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(State state){
				guard.done(request);
				if(!guard.live(generation) || !sessionValid()) return;
				currentState=state;
				boolean same=state.application!=null && id.equals(state.application.id);
				if(!same){ checkExactSubmitOutcome(id, allowRetry); return; }
				if(same && (state.status()==Status.SUBMITTED || state.status()==Status.REVIEWING || state.status()==Status.APPROVED || state.status()==Status.REJECTED || (state.status()==Status.CANCELLED && state.application.submittedAt!=null && state.application.submittedAt>0))){
					deleteCurrentLocalFiles();
						currentState=state;
						busy=false;
						localFlow=false;
						loadState();
				}else if(same && state.status()==Status.CANCELLED){
					// A cancelled draft is terminal but was never submitted; do not call it success.
					deleteCurrentLocalFiles();
					currentDetail=null;
					applicationId=null;
					busy=false;
					localFlow=false;
					showMessage(BabyVerificationProgressView.STAGE_INFORMATION, false, getString(R.string.verification_application_cancelled_unsubmitted), getString(R.string.verification_application_cancelled_unsubmitted), false,
							R.string.verification_refresh, v->loadState(), 0, null);
				}else if(same && state.status()==Status.DRAFT && allowRetry){
					submitApplication(id, true);
				}else{
					busy=false;
					showMessage(BabyVerificationProgressView.STAGE_REVIEW, false, getString(R.string.verification_state_error), getString(R.string.verification_retry_safe), false,
							R.string.verification_retry, v->checkSubmitOutcome(id, true), R.string.verification_refresh, v->loadState());
				}
			}
			@Override public void onError(ErrorResponse error){
				guard.done(request);
				if(!guard.live(generation) || !sessionValid()) return;
				busy=false;
				showMessage(BabyVerificationProgressView.STAGE_REVIEW, false, getString(R.string.verification_state_error), errorText(error), false,
						R.string.verification_retry, v->checkSubmitOutcome(id, true), R.string.verification_refresh, v->loadState());
			}
		}).exec(accountID);
	}

	private void checkExactSubmitOutcome(String id, boolean allowRetry){
		int generation=guard.nextGeneration();
		VerificationRequest<ApplicationDetail> request=guard.track(VerificationRequest.detail(id));
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(ApplicationDetail detail){
				guard.done(request);
				if(!guard.live(generation) || !sessionValid()) return;
				if(!id.equals(detail.id) || !ownsDetail(detail)){ showAccountMismatch(); return; }
				Status status=detail.parsedStatus;
				boolean terminal=status==Status.SUBMITTED || status==Status.REVIEWING || status==Status.APPROVED || status==Status.REJECTED || status==Status.CANCELLED;
				if(terminal){
					// Only the exact application/session is cleaned; latest /me may be a different draft.
					deleteOwnedCapture(detail.captureSessionId);
					if(pendingCapture!=null && detail.captureSessionId.equals(pendingCapture.sessionId())) pendingCapture=null;
					if(currentDetail!=null && id.equals(currentDetail.id)) currentDetail=null;
					if(id.equals(applicationId)) applicationId=null;
					photo=null; captureSession=null; busy=false; localFlow=false;
					if(status==Status.CANCELLED && (detail.submittedAt==null || detail.submittedAt<=0)){
						showMessage(BabyVerificationProgressView.STAGE_INFORMATION, false, getString(R.string.verification_application_cancelled_unsubmitted), getString(R.string.verification_application_cancelled_unsubmitted), false,
								R.string.verification_refresh, v->loadState(), 0, null);
					}else loadState();
				}else if(status==Status.DRAFT && allowRetry){ submitApplication(id, true); }
				else exactOutcomeError(id, null);
			}
			@Override public void onError(ErrorResponse error){
				guard.done(request);
				if(!guard.live(generation) || !sessionValid()) return;
				exactOutcomeError(id, error);
			}
		}).exec(accountID);
	}

	private void exactOutcomeError(String id, ErrorResponse error){
		busy=false;
		showMessage(BabyVerificationProgressView.STAGE_REVIEW, false, getString(R.string.verification_state_error), error==null ? getString(R.string.verification_retry_safe) : errorText(error), false,
				R.string.verification_retry, v->checkExactSubmitOutcome(id, true), R.string.verification_refresh, v->loadState());
	}

	private void confirmDiscardCurrentDraft(){
		if(currentDetail!=null && applicationId!=null && applicationId.equals(currentDetail.id)) confirmDiscardDraft(currentDetail);
		else if(applicationId!=null) discardDraft(applicationId, null);
		else cancelPendingCapture(true);
	}

	private void confirmDiscardDraft(ApplicationDetail detail){
		new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.verification_discard_draft_title)
				.setMessage(R.string.verification_discard_draft_body)
				.setPositiveButton(R.string.verification_discard_draft_confirm, (dialog, which)->discardDraft(detail.id, detail.captureSessionId))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void discardDraft(String id, String captureSessionId){
		beginLocalFlow();
		int token=flowGeneration;
		busy=true;
		showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_draft_title), getString(R.string.verification_retry_safe), true, 0, null, 0, null);
		VerificationRequest<CancelResult> request=VerificationRequest.cancelApplication(id);
		flowRequests.add(request);
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(CancelResult result){
				flowRequests.remove(request);
				if(!flowLive(token)) return;
				if(captureSessionId!=null) deleteOwnedCapture(captureSessionId);
				deleteCurrentLocalFiles();
				currentDetail=null;
				applicationId=null;
				busy=false;
				localFlow=false;
				loadState();
			}
			@Override public void onError(ErrorResponse error){
				flowRequests.remove(request);
				if(!flowLive(token)) return;
				busy=false;
				showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_state_error), errorText(error), false,
						R.string.verification_retry, v->discardDraft(id, captureSessionId), R.string.verification_refresh, v->loadState());
			}
		}).exec(accountID);
	}

	private void cancelPendingCapture(boolean reload){
		flowGeneration++;
		imageGeneration++;
		guard.cancel();
		localFlow=false;
		VerificationPendingCapture capture=pendingCapture;
		if(capture==null && getActivity()!=null) capture=findPendingCapture();
		pendingCapture=null;
		captureSession=null;
		photo=null;
		busy=false;
		if(!ownsCapture(capture)){ if(reload) loadState(); return; }
		capture.delete(getActivity(), accountID, ownerUserId());
		VerificationRequest<CaptureSession> request=VerificationRequest.cancelCapture(capture.sessionId());
		flowRequests.add(request);
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(CaptureSession result){ flowRequests.remove(request); if(reload && viewReady) loadState(); }
			@Override public void onError(ErrorResponse error){ flowRequests.remove(request); if(reload && viewReady) loadState(); }
		}).exec(accountID);
	}

	private void acknowledgeRejection(String id){
		VerificationRequest<ApplicationResult> request=VerificationRequest.acknowledgeRejection(id);
		flowRequests.add(request);
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(ApplicationResult result){ flowRequests.remove(request); }
			@Override public void onError(ErrorResponse error){ flowRequests.remove(request); }
		}).exec(accountID);
	}

	private void openCertificate(Certificate certificate){
		if(certificate==null || !certificate.isActive() || !sessionValid()) return;
		Bundle args=new Bundle();
		args.putString("account", accountID);
		args.putString("certificate", MastodonAPIController.gson.toJson(certificate));
		Nav.go(getActivity(), BabyVerificationCertificateFragment.class, args);
	}

	private String revokedDetail(Certificate certificate){
		return getString(R.string.verification_certificate_revoked_detail, formatTime(certificate.revokedAt), certificate.revokeReason);
	}

	private String applicationSummary(State state){
		return switch(state.status()){
			case APPROVED, NOT_STARTED, CANCELLED -> null;
			case SUBMITTED, REVIEWING -> getString(R.string.verification_review_title);
			case DRAFT -> getString(R.string.verification_draft_title);
			case REJECTED -> getString(R.string.verification_rejected_title)+"："+state.application.decisionNote;
		};
	}

	private String applicationTitle(State state){
		return switch(state.status()){
			case SUBMITTED, REVIEWING -> getString(R.string.verification_review_title);
			case DRAFT -> getString(R.string.verification_draft_title);
			case REJECTED -> getString(R.string.verification_rejected_title);
			case APPROVED -> getString(R.string.verification_status_approved);
			case NOT_STARTED, CANCELLED -> getString(R.string.verification_certificate_title);
		};
	}

	private String formatTime(Long seconds){
		if(seconds==null || seconds<=0) return "—";
		return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()));
	}

	private void showLoading(int stage, int textRes){
		showOnly(loadingState);
		progressSteps.setStage(stage, false);
		loadingText.setText(textRes);
	}

	private void showMessage(int stage, boolean complete, CharSequence title, CharSequence body, boolean showProgress,
			int primaryRes, View.OnClickListener primary, int secondaryRes, View.OnClickListener secondary){
		showOnly(messageCard);
		progressSteps.setStage(stage, complete);
		messageTitle.setText(title);
		messageBody.setText(body==null ? "" : body);
		messageProgress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
		bindButton(messagePrimary, primaryRes, primary);
		bindButton(messageSecondary, secondaryRes, secondary);
		messageTitle.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
	}

	private void bindButton(Button button, int label, View.OnClickListener listener){
		if(label==0 || listener==null){ button.setVisibility(View.GONE); button.setOnClickListener(null); return; }
		button.setText(label);
		button.setOnClickListener(listener);
		button.setVisibility(View.VISIBLE);
	}

	private void showOnly(View target){
		loadingState.setVisibility(target==loadingState ? View.VISIBLE : View.GONE);
		formCard.setVisibility(target==formCard ? View.VISIBLE : View.GONE);
		messageCard.setVisibility(target==messageCard ? View.VISIBLE : View.GONE);
	}

	private void updateStartEnabled(){
		if(startButton==null) return;
		String value=qqInput==null ? "" : qqInput.getText().toString().trim();
		startButton.setEnabled(!busy && formAvailable && adult!=null && adult.isChecked() && value.matches("\\d{5,20}"));
	}

	private void continueCurrentDraft(){
		if(currentDetail!=null) continueDraft(currentDetail);
		else loadState();
	}

	private File localPhoto(ApplicationDetail detail){
		VerificationPendingCapture capture=ownsDetail(detail) ? VerificationPendingCapture.load(getActivity(), detail.captureSessionId, accountID, ownerUserId()) : null;
		// Never open an unowned/legacy file even when a server detail references the same session.
		if(capture!=null){
			try{ if(MediaCameraContract.isControlledFile(getActivity(), capture.photoFile(getActivity()))) return capture.photoFile(getActivity()); }
			catch(java.io.IOException ignored){}
		}
		return new File(getActivity().getNoBackupFilesDir(), "verification-no-owned-photo");
	}

	private void deleteCurrentLocalFiles(){
		if(!sessionValid() || getActivity()==null) return;
		if(ownsDetail(currentDetail)) deleteOwnedCapture(currentDetail.captureSessionId);
		if(pendingCapture!=null) pendingCapture.delete(getActivity(), accountID, ownerUserId());
		pendingCapture=null;
		captureSession=null;
		photo=null;
		currentDetail=null;
		applicationId=null;
		reconcileApplication=false;
	}

	private long ownerUserId(){
		try{ return session!=null && session.self!=null ? Long.parseLong(session.self.id) : 0; }
		catch(NumberFormatException ignored){ return 0; }
	}

	private boolean ownsCapture(VerificationPendingCapture capture){
		return sessionValid() && capture!=null && capture.belongsTo(accountID, ownerUserId())
				&& VerificationPendingCapture.load(getActivity(), capture.sessionId(), accountID, ownerUserId())!=null;
	}

	private boolean ownsDetail(ApplicationDetail detail){ return sessionValid() && detail!=null && detail.userId==ownerUserId(); }

	private VerificationPendingCapture findPendingCapture(){
		return sessionValid() ? VerificationPendingCapture.findPending(getActivity(), accountID, ownerUserId()) : null;
	}

	private void deleteOwnedCapture(String id){
		if(!sessionValid()) return;
		VerificationPendingCapture capture=VerificationPendingCapture.load(getActivity(), id, accountID, ownerUserId());
		if(capture!=null) capture.delete(getActivity(), accountID, ownerUserId());
	}

	private void showAccountMismatch(){
		busy=false;
		if(viewReady) showMessage(BabyVerificationProgressView.STAGE_INFORMATION, false, getString(R.string.verification_state_error), getString(R.string.verification_account_changed), false,
				R.string.verification_refresh, v->loadState(), 0, null);
	}

	private void showExpiredCapture(){
		busy=false;
		showMessage(BabyVerificationProgressView.STAGE_PHOTO, false, getString(R.string.verification_photo_retake_required), getString(R.string.verification_capture_expired), false,
				R.string.verification_continue_capture, v->cancelPendingCapture(true), 0, null);
	}

	private void showPendingOrForm(State state, String note){
		pendingCapture=findPendingCapture();
		if(pendingCapture!=null){ localFlow=true; showPendingCapture(); }
		else showForm(state, note);
	}

	private boolean sessionValid(){
		return viewReady && session!=null && ownerUserId()>0 && accountID.equals(session.getID()) && AccountSessionManager.getInstance().tryGetAccount(accountID)==session;
	}

	private int stageForState(State state){
		if(state==null) return BabyVerificationProgressView.STAGE_INFORMATION;
		return switch(state.status()){
			case DRAFT -> BabyVerificationProgressView.STAGE_PHOTO;
			case SUBMITTED, REVIEWING, APPROVED -> BabyVerificationProgressView.STAGE_REVIEW;
			default -> BabyVerificationProgressView.STAGE_INFORMATION;
		};
	}

	private String errorText(ErrorResponse error){
		if(error instanceof VerificationRequest.VerificationError value){
			if("verification_unavailable".equals(value.code)) return getString(R.string.verification_photo_check_unavailable);
			if(value.code!=null && !value.code.isEmpty() && !"unknown".equals(value.code)) return "["+value.code+"] "+value.error;
		}
		if(error instanceof VerificationUploader.UploadError value){
			if("verification_unavailable".equals(value.code)) return getString(R.string.verification_photo_check_unavailable);
			if(value.code!=null && !value.code.isEmpty()) return "["+value.code+"] "+value.error;
		}
		return error==null ? getString(R.string.verification_state_error) : error.toString();
	}

	@Override
	public void onDestroyView(){
		viewReady=false;
		busy=false;
		localFlow=false;
		flowGeneration++;
		imageGeneration++;
		if(uploader!=null) uploader.cancel();
		uploader=null;
		for(VerificationRequest<?> request:new ArrayList<>(flowRequests)) request.cancel();
		flowRequests.clear();
		guard.cancel();
		scroll=null;
		loadingState=formCard=messageCard=null;
		progressSteps=null;
		loadingText=quotaValue=formNote=messageTitle=messageBody=null;
		qqInput=null;
		adult=null;
		startButton=messagePrimary=messageSecondary=null;
		messageProgress=null;
		super.onDestroyView();
	}
}
