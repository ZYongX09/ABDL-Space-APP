package org.joinmastodon.noveleditor;

/** Applies bounded edit operations during crash recovery. */
public final class EditorJournal{
	private EditorJournal(){}

	public static String apply(String base, int start, int deletedCount, String inserted){
		if(base==null || inserted==null || start<0 || deletedCount<0 || start>base.length() || start+deletedCount>base.length()
				|| inserted.length()>EditorLimits.JOURNAL_INSERT) throw new IllegalArgumentException("Invalid edit operation");
		return base.substring(0, start)+inserted+base.substring(start+deletedCount);
	}
}
