package org.joinmastodon.noveleditor;

/** Shared local/cloud-compatible content limits. */
public final class EditorLimits{
	private EditorLimits(){}
	public static final int WORK_TITLE=120;
	public static final int WORK_DESCRIPTION=2000;
	public static final int VOLUME_TITLE=120;
	public static final int CHAPTER_TITLE=160;
	public static final int CHAPTER_CONTENT=500_000;
	public static final int CONTENT_WARNING=500;
	public static final int MAX_SNAPSHOTS_PER_CHAPTER=40;
	public static final int JOURNAL_INSERT=64_000;
}
