package org.joinmastodon.android.sponsors;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Persisted retry identities, NOT permissions. Every ticket must still be authorized by the server. */
public final class SponsorOriginalTickets{
	public interface Store{
		String read();
		boolean write(String value);
	}
	private static final int MAX_RECORDS=100;
	private final Store store;

	public SponsorOriginalTickets(Store store){ this.store=store; }

	/** Call only with a validated quota from this account's server. Never derive the day locally. */
	public String operation(String account, String media, String serverDay, long serverReset, long nowSeconds){
		validateQuota(serverDay, serverReset);
		// Expired data never rolls an identity using a locally invented day. Refetch the server quota.
		if(nowSeconds>=serverReset) throw new IllegalArgumentException("Expired server quota");
		synchronized(SponsorOriginalTickets.class){
			LinkedHashMap<String, Ticket> tickets=read();
			String scope=SponsorOperation.scope(account, serverDay, media);
			Ticket existing=tickets.remove(scope);
			String id=existing==null || nowSeconds>=existing.reset ? SponsorOperation.newId() : existing.id;
			tickets.put(scope, new Ticket(id, serverReset));
			persist(tickets);
			return id;
		}
	}

	/** Existence is only a reason to ask the server to replay, never permission to download. */
	public boolean hasRetry(String account, String media, String serverDay, long serverReset, long nowSeconds){
		validateQuota(serverDay, serverReset);
		if(nowSeconds>=serverReset) return false;
		synchronized(SponsorOriginalTickets.class){
			Ticket ticket=read().get(SponsorOperation.scope(account, serverDay, media));
			return ticket!=null && nowSeconds<ticket.reset;
		}
	}

	/** A request may cross midnight: bind the identity to the response's trusted server day, too. */
	public void authorized(String account, String media, String operation, String serverDay, long serverReset){
		validateQuota(serverDay, serverReset);
		UUID.fromString(operation);
		synchronized(SponsorOriginalTickets.class){
			LinkedHashMap<String, Ticket> tickets=read();
			String scope=SponsorOperation.scope(account, serverDay, media);
			tickets.remove(scope);
			tickets.put(scope, new Ticket(operation, serverReset));
			persist(tickets);
		}
	}

	private static void validateQuota(String day, long reset){
		if(day==null || !day.matches("\\d{4}-\\d{2}-\\d{2}") || reset<=0) throw new IllegalArgumentException("Untrusted quota");
	}

	private LinkedHashMap<String, Ticket> read(){
		LinkedHashMap<String, Ticket> tickets=new LinkedHashMap<>();
		String encoded=store.read();
		if(encoded==null || encoded.length()>32768) return tickets;
		for(String line:encoded.split("\n")){
			String[] fields=line.split(" ");
			if(fields.length!=3 || !fields[0].matches("[a-f0-9]{64}\\.\\d{4}-\\d{2}-\\d{2}\\.[a-f0-9]{64}")) continue;
			try{
				if(!UUID.fromString(fields[1]).toString().equals(fields[1])) continue;
				long reset=Long.parseLong(fields[2]);
				if(reset>0) tickets.put(fields[0], new Ticket(fields[1], reset));
			}catch(IllegalArgumentException ignored){}
		}
		return tickets;
	}

	private void persist(LinkedHashMap<String, Ticket> tickets){
		Iterator<String> oldest=tickets.keySet().iterator();
		while(tickets.size()>MAX_RECORDS){ oldest.next(); oldest.remove(); }
		StringBuilder encoded=new StringBuilder();
		for(Map.Entry<String, Ticket> entry:tickets.entrySet())
			encoded.append(entry.getKey()).append(' ').append(entry.getValue().id).append(' ').append(entry.getValue().reset).append('\n');
		// Durability before POST matters: a failed write must not create an unrepeatable debit.
		if(!store.write(encoded.toString())) throw new IllegalStateException("Unable to persist original retry ticket");
	}

	private static final class Ticket{
		final String id;
		final long reset;
		Ticket(String id, long reset){ this.id=id; this.reset=reset; }
	}
}
