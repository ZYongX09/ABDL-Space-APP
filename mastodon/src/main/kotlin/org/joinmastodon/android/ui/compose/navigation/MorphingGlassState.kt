package org.joinmastodon.android.ui.compose.navigation

internal enum class MorphingGlassSide { LEADING, TRAILING }

internal enum class MorphingGlassPhase {
	IDLE,
	PRESSED,
	EXPANDING,
	EXPANDED,
	SELECTING,
	COLLAPSING,
}

internal sealed interface MorphingGlassEvent {
	data object Press : MorphingGlassEvent
	data object BackdropReady : MorphingGlassEvent
	data object ExpansionSettled : MorphingGlassEvent
	data class HoverItem(val index: Int) : MorphingGlassEvent
	data object Release : MorphingGlassEvent
	data object PointerOutside : MorphingGlassEvent
	data object CollapseSettled : MorphingGlassEvent
	data class OpenOther(val side: MorphingGlassSide) : MorphingGlassEvent
}

internal data class MorphingGlassState(
	val side: MorphingGlassSide,
	val phase: MorphingGlassPhase,
	val highlightedIndex: Int? = null,
	val pendingSide: MorphingGlassSide? = null,
) {
	companion object {
		fun idle(side: MorphingGlassSide) = MorphingGlassState(side, MorphingGlassPhase.IDLE)
	}

	fun reduce(event: MorphingGlassEvent): MorphingGlassState = when(event) {
		MorphingGlassEvent.Press -> if(phase==MorphingGlassPhase.IDLE) copy(phase = MorphingGlassPhase.PRESSED) else this
		MorphingGlassEvent.BackdropReady -> if(phase==MorphingGlassPhase.PRESSED) copy(phase = MorphingGlassPhase.EXPANDING) else this
		MorphingGlassEvent.ExpansionSettled -> if(phase==MorphingGlassPhase.EXPANDING) copy(phase = MorphingGlassPhase.EXPANDED) else this
		is MorphingGlassEvent.HoverItem -> if(phase==MorphingGlassPhase.EXPANDED || phase==MorphingGlassPhase.SELECTING) copy(phase = MorphingGlassPhase.SELECTING, highlightedIndex = event.index) else this
		MorphingGlassEvent.Release -> if(phase==MorphingGlassPhase.EXPANDED || phase==MorphingGlassPhase.SELECTING) copy(phase = MorphingGlassPhase.COLLAPSING) else this
		MorphingGlassEvent.PointerOutside -> if(phase!=MorphingGlassPhase.IDLE) copy(phase = MorphingGlassPhase.COLLAPSING, highlightedIndex = null) else this
		MorphingGlassEvent.CollapseSettled -> if(phase==MorphingGlassPhase.COLLAPSING) idle(pendingSide ?: side) else this
		is MorphingGlassEvent.OpenOther -> if(event.side!=side && phase!=MorphingGlassPhase.IDLE) copy(phase = MorphingGlassPhase.COLLAPSING, highlightedIndex = null, pendingSide = event.side) else this
	}
}
