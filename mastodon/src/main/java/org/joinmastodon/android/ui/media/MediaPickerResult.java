package org.joinmastodon.android.ui.media;

import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;

public final class MediaPickerResult {
	public static final String KEY_URIS="media_picker_uris";

	private MediaPickerResult(){
	}

	public static Bundle create(ArrayList<Uri> uris){
		Bundle result=new Bundle();
		result.putParcelableArrayList(KEY_URIS, uris);
		return result;
	}
}
