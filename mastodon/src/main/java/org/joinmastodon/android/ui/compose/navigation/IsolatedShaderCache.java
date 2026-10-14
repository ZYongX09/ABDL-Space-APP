package org.joinmastodon.android.ui.compose.navigation;

import top.yukonga.miuix.kmp.blur.RuntimeShaderCache;
import top.yukonga.miuix.kmp.blur.RuntimeShaderCacheImpl;

/** Highlight paints retain mutable shader uniforms independently from RenderEffect snapshots. */
public final class IsolatedShaderCache{
	private IsolatedShaderCache(){}
	public static RuntimeShaderCache create(){ return new RuntimeShaderCacheImpl(); }
}
