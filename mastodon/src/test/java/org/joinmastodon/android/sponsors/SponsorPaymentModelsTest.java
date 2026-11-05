package org.joinmastodon.android.sponsors;

import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.*;
import org.junit.Test;

import java.util.List;

import static org.joinmastodon.android.sponsors.SponsorPaymentFixtures.*;
import static org.junit.Assert.*;

public class SponsorPaymentModelsTest{
	@Test public void onlyClosedCanAdvertiseSwitchAndSdkSignedDataRequiresPendingPaymentAndFulfillment() throws Exception{
		Order closed=order("CLOSED", "PENDING"); closed.postprocess();
		for(String state:List.of("PENDING_PAYMENT", "UNKNOWN", "PAID")){
			Order invalid=order(state, "PENDING"); invalid.canSwitchChannel=true;
			assertThrows(ObjectValidationException.class, invalid::postprocess);
		}
		Order review=order("PENDING_PAYMENT", "MANUAL_REVIEW"); review.orderStr="fixture-signed-not-allowed";
		assertThrows(ObjectValidationException.class, review::postprocess);
		Order granted=order("PAID", "GRANTED"); granted.orderStr="fixture-signed-not-allowed";
		assertThrows(ObjectValidationException.class, granted::postprocess);
	}
	@Test public void unknownDisabledReasonsAreValidButMissingAvailabilityNeverDefaultsToPayable() throws Exception{
		Options unavailable=options("unknown_future_policy"); unavailable.postprocess(); assertFalse(unavailable.channels.get(0).available);
		unavailable.channels.get(0).available=null; assertThrows(ObjectValidationException.class, unavailable::postprocess);
		Options invalid=options(null); invalid.channels.get(0).reason="limits_unverified";
		assertThrows(ObjectValidationException.class, invalid::postprocess);
	}
	@Test public void signedDataAndMeCannotLeakThroughNonCreateNonGrantedProjection(){
		Options options=options(null); options.activeOrder=order("PENDING_PAYMENT", "PENDING"); options.activeOrder.orderStr="fixture-secret";
		assertThrows(ObjectValidationException.class, options::postprocess);
		CurrentOrder current=current(order("PENDING_PAYMENT", "PENDING")); current.order.orderStr="fixture-secret";
		assertThrows(ObjectValidationException.class, current::postprocess);
		Order unpaid=order("PENDING_PAYMENT", "PENDING"); unpaid.me=me(true); assertThrows(ObjectValidationException.class, unpaid::postprocess);
	}
}
