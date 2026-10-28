package org.joinmastodon.android.auth;

import static org.joinmastodon.android.auth.HumanCheckTestSupport.*;
import static org.junit.Assert.*;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.auth.HumanCheckRequest;
import org.joinmastodon.android.auth.HumanCheckCoordinator.Action;
import org.joinmastodon.android.auth.HumanCheckCoordinator.Handle;
import org.joinmastodon.android.auth.HumanCheckCoordinator.Phase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, shadows={Requests.class,Sessions.class})
public class HumanCheckCoordinatorTest{
	private HumanCheckCoordinator flow;
	@Before public void setup() throws Exception{ MastodonApp.context=RuntimeEnvironment.getApplication(); reset(); flow=HumanCheckCoordinator.getInstance(); }
	private Handle begin() { return flow.begin(EMAIL,Action.SEND,()->{}); }
	private Handle prompt(Handle entry) throws Exception{ required(last()); return flow.attach(entry.generation,entry.owner,()->{}); }
	private Handle loading(Handle entry) throws Exception{ Handle ui=prompt(entry); assertTrue(flow.start(ui)); challenge(last()); return ui; }
	private void event(Handle ui, String event){ flow.webMessage(ui,flow.snapshot(ui).attempt,HumanCheckRequest.ORIGIN,true,"{\"event\":\""+event+"\",\"challenge_id\":\""+CID+"\"}"); }
	@Test public void policyOffIsOneTypedSendAndNoPrompt() throws Exception{
		Handle entry=begin(); HumanCheckRequest send=last(); assertEquals(HumanCheckRequest.Endpoint.SEND_CODE,send.endpoint);
		assertEquals(Set.of("email","type","operation_id","code_challenge"),body(send).keySet()); assertEquals("register",body(send).get("type").getAsString());
		sent(send); assertEquals(Phase.SENT,flow.snapshot(entry).phase); assertEquals(1,sent.size()); assertEquals(0,factoryCalls);
		assertTrue(flow.consumeSent(entry,EMAIL,Action.SEND,entry.generation,entry.owner)); assertFalse(flow.consumeSent(entry,EMAIL,Action.SEND,entry.generation,entry.owner));
	}
	@Test public void requiredOnlyPromptsAndNoChallengeBeforeStart() throws Exception{
		Handle entry=begin(); Handle ui=prompt(entry); assertNotNull(ui); assertEquals(Phase.PROMPT,flow.snapshot(ui).phase); assertEquals(1,sent.size()); assertEquals(0,factoryCalls);
		assertTrue(flow.start(ui)); assertEquals(HumanCheckRequest.Endpoint.CHALLENGE,last().endpoint); assertFalse(flow.start(ui)); assertEquals(2,sent.size());
	}
	@Test public void readyAndVerifyingNeverCountAsSent() throws Exception{
		Handle entry=begin(), ui=loading(entry); event(ui,"verified"); assertEquals(Phase.LOADING,flow.snapshot(ui).phase);
		event(ui,"ready"); assertEquals(Phase.READY,flow.snapshot(ui).phase); event(ui,"verifying"); assertEquals(Phase.VERIFYING,flow.snapshot(ui).phase); assertEquals(2,sent.size());
		assertFalse(flow.consumeSent(entry,EMAIL,Action.SEND,entry.generation,entry.owner));
	}
	@Test public void serverCompleteIsSingleFlightAndOnlyBusinessSentCanBeConsumed() throws Exception{
		Handle entry=begin(), ui=loading(entry); event(ui,"ready"); event(ui,"verified"); HumanCheckRequest complete=last();
		assertEquals(HumanCheckRequest.Endpoint.COMPLETE,complete.endpoint); event(ui,"verified"); assertEquals(3,sent.size());
		status(complete,"processing"); assertEquals(Phase.PROCESSING,flow.snapshot(ui).phase); assertFalse(flow.issueSentResult(ui));
		flow.query(ui); HumanCheckRequest query=last(); assertEquals(HumanCheckRequest.Endpoint.STATUS,query.endpoint); status(query,"sent");
		assertTrue(flow.issueSentResult(ui)); assertFalse(flow.issueSentResult(ui)); assertTrue(flow.consumeSent(entry,EMAIL,Action.SEND,entry.generation,entry.owner));
	}
	@Test public void closingModalCancelsAttemptAndLeavesParentForExplicitRestart() throws Exception{
		Handle entry=begin(), ui=loading(entry); flow.cancelAttempt(ui,R.string.human_check_failed);
		HumanCheckRequest cancel=last(); assertEquals(HumanCheckRequest.Endpoint.CHALLENGE_CANCEL,cancel.endpoint); assertEquals(Phase.PROMPT,flow.snapshot(ui).phase);
		assertFalse(flow.start(ui)); cancelled(cancel); assertTrue(flow.start(ui)); assertEquals(HumanCheckRequest.Endpoint.CHALLENGE,last().endpoint);
	}
	@Test public void closeWhileChallengeInFlightCancelsItsEventualIdInsteadOfLoading() throws Exception{
		Handle entry=begin(), ui=prompt(entry); flow.start(ui); HumanCheckRequest challenge=last(); flow.cancelAttempt(ui,R.string.human_check_failed);
		challenge(challenge); assertEquals(HumanCheckRequest.Endpoint.CHALLENGE_CANCEL,last().endpoint); assertEquals(Phase.PROMPT,flow.snapshot(ui).phase);
		assertEquals(0,factoryCalls);
	}
	@Test public void promptBackCancelsParentNeverDispatchesMail() throws Exception{
		Handle entry=begin(), ui=prompt(entry); flow.cancel(ui); assertEquals(HumanCheckRequest.Endpoint.CANCEL,last().endpoint); cancelled(last());
		assertEquals(Phase.CANCELLED,flow.snapshot(entry).phase); assertFalse(flow.consumeSent(entry,EMAIL,Action.SEND,entry.generation,entry.owner));
		assertEquals(2,sent.size());
	}
	@Test public void lateVerifiedAfterCloseOrParentCancelCannotComplete() throws Exception{
		Handle entry=begin(), ui=loading(entry); long oldAttempt=flow.snapshot(ui).attempt;
		flow.cancelAttempt(ui,R.string.human_check_failed); int count=sent.size();
		flow.webMessage(ui,oldAttempt,HumanCheckRequest.ORIGIN,true,"{\"event\":\"verified\",\"challenge_id\":\""+CID+"\"}"); assertEquals(count,sent.size());
		cancelled(last()); flow.cancel(ui); count=sent.size();
		flow.webMessage(ui,oldAttempt,HumanCheckRequest.ORIGIN,true,"{\"event\":\"verified\",\"challenge_id\":\""+CID+"\"}"); assertEquals(count,sent.size());
	}
	@Test public void forgedOriginFrameCidEventAndDuplicateMembersAreRejected() throws Exception{
		Handle entry=begin(), ui=loading(entry); long attempt=flow.snapshot(ui).attempt;
		String ready="{\"event\":\"ready\",\"challenge_id\":\""+CID+"\"}";
		flow.webMessage(ui,attempt,"https://api.abdl-space.top.evil",true,ready); flow.webMessage(ui,attempt,HumanCheckRequest.ORIGIN,false,ready);
		for(String json:new String[]{ready.replace(CID,"B".repeat(42)+"A"),ready.replace("ready","pass"),ready.replace("\"ready\"","\"ready\",\"event\":\"verified\""),ready.replace("\"ready\"","\"ready\",\"token\":\"secret\"")})
			flow.webMessage(ui,attempt,HumanCheckRequest.ORIGIN,true,json);
		assertEquals(Phase.LOADING,flow.snapshot(ui).phase); assertEquals(2,sent.size());
	}
	@Test public void expiredProofStopsAndDoesNotMutateOnEverySnapshot() throws Exception{
		Handle entry=begin(), ui=loading(entry); ShadowSystemClock.advanceBy(Duration.ofMinutes(6));
		var expired=flow.snapshot(ui); assertEquals(Phase.FAILED,expired.phase); assertEquals(R.string.human_check_expired,expired.message);
		assertEquals(expired.attempt,flow.snapshot(ui).attempt); assertFalse(flow.start(ui)); event(ui,"verified"); assertEquals(2,sent.size());
	}
	@Test public void networkAnd503DispatchFailuresAreQueryOnlyNeverAutomaticResend() throws Exception{
		Handle entry=begin(); callback(last()).onError(new HumanCheckRequest.Failure(0,"NETWORK")); assertEquals(Phase.UNKNOWN,flow.snapshot(entry).phase);
		Handle again=flow.begin(EMAIL,Action.SEND,()->{}); assertNotNull(again); assertEquals(1,sent.size()); assertNull(flow.begin("other@example.test",Action.SEND,()->{}));
		flow.query(again); callback(last()).onError(new HumanCheckRequest.Failure(503,"EMAIL_DELIVERY_UNAVAILABLE")); assertEquals(Phase.UNKNOWN,flow.snapshot(again).phase);
		assertEquals(HumanCheckRequest.Endpoint.STATUS,last().endpoint);
	}
	@Test public void cancelAfterDispatchNeverTurnsLateSentIntoSuccess() throws Exception{
		Handle entry=begin(), ui=loading(entry); event(ui,"ready"); event(ui,"verified"); HumanCheckRequest complete=last();
		flow.cancel(ui); HumanCheckRequest cancel=last(); sent(complete); callback(cancel).onError(new HumanCheckRequest.Failure(409,"HUMAN_CHECK_DELIVERY_UNKNOWN"));
		assertEquals(Phase.CANCEL_UNKNOWN,flow.snapshot(entry).phase); assertFalse(flow.consumeSent(entry,EMAIL,Action.SEND,entry.generation,entry.owner));
		assertEquals(HumanCheckRequest.Endpoint.CANCEL,cancel.endpoint);
	}
	@Test public void policyChangedRequiresExplicitRestartNoMailAndOldOwnerCannotResume() throws Exception{
		Handle entry=begin(), ui=prompt(entry); flow.start(ui); callback(last()).onError(new HumanCheckRequest.Failure(409,"HUMAN_CHECK_POLICY_CHANGED"));
		assertEquals(Phase.FAILED,flow.snapshot(ui).phase); assertEquals(R.string.human_check_policy_changed,flow.snapshot(ui).message); assertFalse(flow.start(ui));
		Handle next=begin(); assertNotEquals(entry.generation,next.generation); assertNull(flow.snapshot(ui));
	}
	@Test public void accountChangeAndParallelOwnersInvalidateCallbacks() throws Exception{
		Handle first=begin(); HumanCheckRequest send=last(); Sessions.active="account-switched"; sent(send); assertNull(flow.snapshot(first));
		Sessions.active=null; Handle next=begin(); Handle ui=prompt(next); assertNull(flow.attach(next.generation,next.owner,()->{}));
		assertFalse(flow.isEntry(next,"different@example.test",Action.SEND)); assertFalse(flow.isEntry(next,EMAIL,Action.RESEND));
		flow.invalidateGeneration(next.generation); assertNull(flow.snapshot(ui));
	}
	@Test public void consumedOffSentRemainsTerminalAfterOperationTtlAndResendStartsNormally() throws Exception{
		Handle entry=begin(); HumanCheckRequest first=last(); sent(first);
		assertTrue(flow.consumeSent(entry,EMAIL,Action.SEND,entry.generation,entry.owner));
		ShadowSystemClock.advanceBy(Duration.ofMillis(HumanCheckCoordinator.OPERATION_TTL+1));
		assertEquals(Phase.SENT,flow.snapshot(entry).phase);
		Handle resend=flow.begin(EMAIL,Action.RESEND,()->{}); assertNotNull(resend);
		assertNotEquals(entry.generation,resend.generation); assertEquals(Phase.SENDING,flow.snapshot(resend).phase);
		assertEquals(HumanCheckRequest.Endpoint.SEND_CODE,last().endpoint); assertEquals(2,sent.size()); assertNotEquals(op(first),op(last()));
	}
	@Test public void consumedChallengeSentRemainsTerminalAfterChallengeTtlAndResendStartsNormally() throws Exception{
		Handle entry=begin(), ui=loading(entry); event(ui,"ready"); event(ui,"verified"); HumanCheckRequest complete=last(); sent(complete);
		assertTrue(flow.issueSentResult(ui)); assertTrue(flow.consumeSent(entry,EMAIL,Action.SEND,entry.generation,entry.owner));
		ShadowSystemClock.advanceBy(Duration.ofMillis(HumanCheckCoordinator.ATTEMPT_TTL+1));
		assertEquals(Phase.SENT,flow.snapshot(entry).phase);
		Handle resend=flow.begin(EMAIL,Action.RESEND,()->{}); assertNotNull(resend);
		assertNotEquals(entry.generation,resend.generation); assertEquals(Phase.SENDING,flow.snapshot(resend).phase);
		assertEquals(HumanCheckRequest.Endpoint.SEND_CODE,last().endpoint); assertEquals(4,sent.size()); assertNotEquals(op(complete),op(last()));
	}
	@Test public void unresolvedDispatchExpiryStillForbidsNewResendAndDoesNotRepeatSend() throws Exception{
		Handle entry=begin(); callback(last()).onError(new HumanCheckRequest.Failure(0,"NETWORK"));
		ShadowSystemClock.advanceBy(Duration.ofMillis(HumanCheckCoordinator.OPERATION_TTL+1));
		assertEquals(Phase.UNKNOWN,flow.snapshot(entry).phase); assertFalse(flow.snapshot(entry).canQuery);
		assertNull(flow.begin(EMAIL,Action.RESEND,()->{})); assertEquals(1,sent.size());
		assertFalse(flow.consumeSent(entry,EMAIL,Action.SEND,entry.generation,entry.owner));
	}
	@Test public void processDeathHasNoProofAndCannotAttachOrCreateNetwork() throws Exception{
		Handle entry=begin(); prompt(entry); int count=sent.size(); field(HumanCheckCoordinator.class,"pending").set(flow,null);
		assertNull(flow.attach(entry.generation,entry.owner,()->{})); assertNull(flow.snapshot(entry)); assertEquals(count,sent.size());
	}
}
