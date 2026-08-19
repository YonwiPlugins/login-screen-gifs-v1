package com.yonwiplugins.loginscreengifs;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LoginScreenGifsPanelTest
{
	@Test
	public void dropsTheExtensionFromTheLabel()
	{
		assertEquals("holiday", LoginScreenGifsPanel.shorten("holiday.gif"));
		assertEquals("holiday", LoginScreenGifsPanel.shorten("holiday.GIF"));
	}

	@Test
	public void keepsLongNamesInsideTheSidePanel()
	{
		String label = LoginScreenGifsPanel.shorten("an extremely long filename that will not fit.gif");

		assertTrue(label.endsWith("..."));
		assertEquals(20, label.length());
	}

	@Test
	public void copesWithNothingPlaying()
	{
		assertEquals("", LoginScreenGifsPanel.shorten(null));
	}
}
