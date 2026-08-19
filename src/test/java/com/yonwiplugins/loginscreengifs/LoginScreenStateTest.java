package com.yonwiplugins.loginscreengifs;

import net.runelite.api.GameState;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the regression that stopped the GIFs playing at all: the plugin used to wait for a
 * GameStateChanged event before drawing anything, so enabling it while already sitting on the
 * login screen produced no background, because no state change ever happened.
 */
public class LoginScreenStateTest
{
	@Test
	public void drawsOnTheOrdinaryLoginScreen()
	{
		assertTrue(LoginScreenGifsPlugin.showsLoginBackground(GameState.LOGIN_SCREEN));
	}

	@Test
	public void keepsDrawingOnTheAuthenticatorForm()
	{
		assertTrue(LoginScreenGifsPlugin.showsLoginBackground(GameState.LOGIN_SCREEN_AUTHENTICATOR));
	}

	@Test
	public void keepsDrawingWhileLoggingIn()
	{
		// Still the login screen, with the loading text over the top of it.
		assertTrue(LoginScreenGifsPlugin.showsLoginBackground(GameState.LOGGING_IN));
	}

	@Test
	public void stopsOnceTheGameIsUp()
	{
		assertFalse(LoginScreenGifsPlugin.showsLoginBackground(GameState.LOGGED_IN));
		assertFalse(LoginScreenGifsPlugin.showsLoginBackground(GameState.HOPPING));
		assertFalse(LoginScreenGifsPlugin.showsLoginBackground(GameState.LOADING));
	}

	@Test
	public void staysOffBeforeTheClientIsReady()
	{
		assertFalse(LoginScreenGifsPlugin.showsLoginBackground(GameState.STARTING));
		assertFalse(LoginScreenGifsPlugin.showsLoginBackground(GameState.UNKNOWN));
		assertFalse(LoginScreenGifsPlugin.showsLoginBackground(GameState.CONNECTION_LOST));
	}
}
