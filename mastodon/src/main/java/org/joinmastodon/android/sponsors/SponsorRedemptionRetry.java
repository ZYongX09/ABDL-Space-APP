package org.joinmastodon.android.sponsors;

/** A persisted request identity survives view recreation; the redemption code itself is never stored. */
public final class SponsorRedemptionRetry{
	public interface Store{
		String read(String key);
		boolean write(String key, String value);
	}
	private final Store store;

	public SponsorRedemptionRetry(Store store){ this.store=store; }

	public static String normalize(String code){
		StringBuilder normalized=new StringBuilder();
		for(int i=0;i<code.length();i++){
			char c=code.charAt(i);
			if(!Character.isWhitespace(c)) normalized.append(c);
		}
		return normalized.toString().toUpperCase(java.util.Locale.ROOT);
	}

	public String operation(String accountID, String code){
		String key=SponsorOperation.hash(accountID);
		String fingerprint=SponsorOperation.hash(normalize(code));
		String stored=store.read(key);
		if(stored!=null && stored.startsWith(fingerprint+":")){
			String id=stored.substring(fingerprint.length()+1);
			try{ if(java.util.UUID.fromString(id).toString().equals(id)) return id; }
			catch(IllegalArgumentException ignored){}
		}
		String id=SponsorOperation.newId();
		if(!store.write(key, fingerprint+":"+id)) throw new IllegalStateException("Unable to persist redemption request identity");
		return id;
	}
}
