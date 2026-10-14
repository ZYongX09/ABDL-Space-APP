package org.joinmastodon.android.ui.map;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapSnapshotGateTest{
	@Test public void allowsOneRequestAndRejectsLateCallbacks(){
		MapSnapshotGate gate=new MapSnapshotGate();
		long first=gate.begin(); assertTrue(first>0); assertEquals(0,gate.begin());
		assertFalse(gate.finish(first+1)); assertTrue(gate.busy());
		assertTrue(gate.finish(first)); assertFalse(gate.busy());
	}
	@Test public void resetInvalidatesOldRequest(){
		MapSnapshotGate gate=new MapSnapshotGate(); long first=gate.begin(); gate.reset();
		assertFalse(gate.finish(first)); long second=gate.begin(); assertTrue(second>first); assertTrue(gate.finish(second));
	}
}
