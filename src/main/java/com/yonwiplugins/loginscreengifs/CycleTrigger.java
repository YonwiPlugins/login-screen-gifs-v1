package com.yonwiplugins.loginscreengifs;

import lombok.AllArgsConstructor;

/**
 * What makes the plugin move on to the next GIF. Cycling by hand from the side panel or the
 * hotkey works whichever of these is chosen.
 */
@AllArgsConstructor
public enum CycleTrigger
{
	OFF("Never (manual only)"),
	SESSION("Once per client start"),
	LOGIN_SCREEN("Every login screen"),
	LOOP("Every full GIF loop"),
	TIMER("On a timer");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
