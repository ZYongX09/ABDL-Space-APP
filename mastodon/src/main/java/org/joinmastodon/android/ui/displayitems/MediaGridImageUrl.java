package org.joinmastodon.android.ui.displayitems;

import org.joinmastodon.android.model.Attachment;

final class MediaGridImageUrl{
	private MediaGridImageUrl(){}

	static String select(Attachment.Type type, String url, String previewUrl){
		return switch(type){
			case IMAGE -> previewUrl==null || previewUrl.isEmpty() ? url : previewUrl;
			case VIDEO, GIFV -> previewUrl;
			default -> throw new IllegalStateException("Unexpected value: "+type);
		};
	}
}
