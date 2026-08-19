package com.yonwiplugins.loginscreengifs;

import java.awt.Dimension;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The old plugin rendered every frame at 1536x864 no matter the window size, which is roughly
 * three and a half times the pixel work the stock login screen needs. These cover the cap that
 * replaced it.
 */
public class LoginScreenOutputSizeTest
{
	@Test
	public void rendersAtTheWindowSizeWhenItIsModest()
	{
		Dimension output = LoginScreenGifsPlugin.outputSize(new Dimension(1024, 640));

		assertEquals(1024, output.width);
		assertEquals(640, output.height);
	}

	@Test
	public void neverGoesBelowTheStockLoginScreenSize()
	{
		Dimension output = LoginScreenGifsPlugin.outputSize(new Dimension(320, 200));

		assertEquals(765, output.width);
		assertEquals(503, output.height);
	}

	@Test
	public void capsTheWorkOnALargeMonitor()
	{
		Dimension output = LoginScreenGifsPlugin.outputSize(new Dimension(3840, 2160));

		assertTrue("width should be capped", output.width <= 1280);
		assertTrue("height should be capped", output.height <= 720);
		// The cap keeps the shape of the window rather than squashing it.
		assertEquals(3840d / 2160d, (double) output.width / output.height, 0.01d);
	}

	@Test
	public void capsAnUltrawideWithoutLosingItsShape()
	{
		Dimension output = LoginScreenGifsPlugin.outputSize(new Dimension(3440, 1440));

		assertTrue(output.width <= 1280);
		assertTrue(output.height <= 720);
		assertEquals(3440d / 1440d, (double) output.width / output.height, 0.01d);
	}

	@Test
	public void staysWellUnderTheOldPerFrameCost()
	{
		long capped = (long) LoginScreenGifsPlugin.outputSize(new Dimension(2560, 1440)).width
			* LoginScreenGifsPlugin.outputSize(new Dimension(2560, 1440)).height;

		assertTrue("a frame should cost less than the old fixed 1536x864", capped < 1536L * 864L);
	}
}
