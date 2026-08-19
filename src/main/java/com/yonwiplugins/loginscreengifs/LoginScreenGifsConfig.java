package com.yonwiplugins.loginscreengifs;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(LoginScreenGifsConfig.GROUP)
public interface LoginScreenGifsConfig extends Config
{
	String GROUP = "loginscreengifs";
	String KEY_CURRENT_GIF = "currentGif";
	String KEY_SHUFFLE_SEEN = "shuffleSeen";
	String KEY_SCALE_MODE = "scaleMode";
	String KEY_SHOW_LOGIN_FIRE = "showLoginFire";

	@ConfigSection(
		name = "Cycling",
		description = "When and how the plugin moves on to the next GIF",
		position = 0
	)
	String cyclingSection = "cycling";

	@ConfigSection(
		name = "Appearance",
		description = "How the GIF is drawn behind the login UI",
		position = 1
	)
	String appearanceSection = "appearance";

	@ConfigItem(
		keyName = "cycleTrigger",
		name = "Cycle when",
		description = "What makes the plugin switch to the next GIF. The side panel and the hotkey can always "
			+ "cycle by hand, whatever this is set to.",
		section = cyclingSection,
		position = 0
	)
	default CycleTrigger cycleTrigger()
	{
		return CycleTrigger.LOGIN_SCREEN;
	}

	@Range(min = 3, max = 3600)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "cycleSeconds",
		name = "Timer length",
		description = "How long each GIF is shown, when Cycle when is set to a timer",
		section = cyclingSection,
		position = 1
	)
	default int cycleSeconds()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "cycleOrder",
		name = "Pick order",
		description = "Which GIF comes next. Shuffle plays every GIF once before any of them repeat.",
		section = cyclingSection,
		position = 2
	)
	default CycleOrder cycleOrder()
	{
		return CycleOrder.IN_ORDER;
	}

	@ConfigItem(
		keyName = "nextGifHotkey",
		name = "Next GIF hotkey",
		description = "Skips to the next GIF while the login screen is showing",
		section = cyclingSection,
		position = 3
	)
	default Keybind nextGifHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = KEY_SCALE_MODE,
		name = "Scaling",
		description = "How each frame is fitted to the login screen",
		section = appearanceSection,
		position = 0
	)
	default ScaleMode scaleMode()
	{
		return ScaleMode.COVER;
	}

	@ConfigItem(
		keyName = KEY_SHOW_LOGIN_FIRE,
		name = "Login screen flames",
		description = "Keeps the two burning braziers the stock login screen draws",
		section = appearanceSection,
		position = 1
	)
	default boolean showLoginFire()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pauseWhileInteracting",
		name = "Pause while you interact",
		description = "Holds the current frame for a moment after you click or type, so the world switcher and "
			+ "the authenticator screen stay responsive. Leave this on unless you are sure you do not need it.",
		section = appearanceSection,
		position = 2
	)
	default boolean pauseWhileInteracting()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_CURRENT_GIF,
		name = "",
		description = "",
		hidden = true
	)
	default String currentGif()
	{
		return "";
	}

	void setCurrentGif(String fileName);

	@ConfigItem(
		keyName = KEY_SHUFFLE_SEEN,
		name = "",
		description = "",
		hidden = true
	)
	default String shuffleSeen()
	{
		return "";
	}

	void setShuffleSeen(String seen);
}
