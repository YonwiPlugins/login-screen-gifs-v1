package com.yonwiplugins.loginscreengifs;

import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.BeforeRender;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@PluginDescriptor(
	name = "Login Screen GIFs",
	description = "Plays your own animated GIFs behind the login screen",
	tags = {"login", "gif", "animated", "background", "screen", "custom", "wallpaper"}
)
@Slf4j
public class LoginScreenGifsPlugin extends Plugin implements KeyListener, LoginScreenGifsPanel.Host
{
	/** Login index 4 is the authenticator form, per the client API. */
	private static final int LOGIN_INDEX_AUTHENTICATOR = 4;
	/**
	 * How long the current frame is held after a click or a keystroke. The login screen serves
	 * its own clicks and key input on the client thread, so leaving that thread alone for a
	 * moment is what keeps the world switcher and the authenticator responsive.
	 */
	private static final long INTERACTION_HOLD_MILLIS = 500L;
	/** The stock login screen size; anything smaller is not worth rendering. */
	private static final int MIN_WIDTH = 765;
	private static final int MIN_HEIGHT = 503;
	/** Caps the per-frame pixel work regardless of how large the window is. */
	private static final int MAX_WIDTH = 1280;
	private static final int MAX_HEIGHT = 720;
	/** A resize under this many pixels is not worth restarting the decoder for. */
	private static final int RESIZE_TOLERANCE = 64;
	/** Neither Windows nor Linux allows a forward slash in a filename, so it is a safe joiner. */
	private static final String BAG_SEPARATOR = "/";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private LoginScreenGifsConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private GifLibrary library;

	private final Set<String> shuffleBag = new LinkedHashSet<>();
	private final Random random = new Random();

	private LoginScreenGifsPanel panel;
	private NavigationButton navButton;
	private GifPlayer player;
	private Dimension builtForCanvas;

	private volatile String currentGifName;
	private volatile boolean playbackDirty;
	private volatile long holdUntilNanos;

	private boolean backgroundApplied;
	private boolean loginScreenShowing;
	private long nextFrameAtNanos;
	private long cycleDueAtNanos;

	private final MouseAdapter mouseAdapter = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			holdFrame();
			return event;
		}
	};

	@Override
	protected void startUp()
	{
		library.ensureFolder();
		loadShuffleBag();
		currentGifName = config.currentGif();

		panel = new LoginScreenGifsPanel(library, this);
		navButton = NavigationButton.builder()
			.tooltip("Login Screen GIFs")
			.icon(createPanelIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		keyManager.registerKeyListener(this);
		mouseManager.registerMouseListener(mouseAdapter);

		if (config.cycleTrigger() == CycleTrigger.SESSION)
		{
			advance();
		}
		playbackDirty = true;
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(this);
		mouseManager.unregisterMouseListener(mouseAdapter);

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (panel != null)
		{
			panel.shutDown();
			panel = null;
		}

		stopDecoder();
		loginScreenShowing = false;

		clientThread.invoke(() ->
		{
			client.setLoginScreen(null);
			client.setShouldRenderLoginScreenFire(true);
			backgroundApplied = false;
		});
	}

	/**
	 * Whether the login screen background is on show. LOGGING_IN is included because that is
	 * still the login screen, just with the loading text over the top of it.
	 */
	static boolean showsLoginBackground(GameState state)
	{
		return state == GameState.LOGIN_SCREEN
			|| state == GameState.LOGIN_SCREEN_AUTHENTICATOR
			|| state == GameState.LOGGING_IN;
	}

	/**
	 * The render pump. Runs on the client thread once per drawn frame, and does no more than
	 * wrap an already-decoded array in a sprite.
	 *
	 * <p>The login state is read here rather than tracked from GameStateChanged, because
	 * enabling the plugin while already sitting on the login screen fires no state change at
	 * all. Waiting for one meant the background never appeared in the most ordinary case
	 * there is.</p>
	 */
	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		boolean showing = showsLoginBackground(client.getGameState());
		if (!showing)
		{
			if (loginScreenShowing)
			{
				loginScreenShowing = false;
				stopDecoder();
				restoreBackground();
			}
			return;
		}

		if (!loginScreenShowing)
		{
			loginScreenShowing = true;
			onLoginScreenShown();
		}

		if (playbackDirty || player == null || canvasHasResized())
		{
			startPlayback();
		}
		if (player == null)
		{
			return;
		}

		long now = System.nanoTime();
		if (config.cycleTrigger() == CycleTrigger.TIMER && cycleDueAtNanos > 0L && now >= cycleDueAtNanos)
		{
			advance();
			return;
		}

		if (isHoldingFrame(now) || (backgroundApplied && now < nextFrameAtNanos))
		{
			return;
		}

		GifPlayer.Frame frame = player.poll();
		if (frame == null)
		{
			return;
		}

		client.setLoginScreen(client.createSpritePixels(frame.getPixels(), frame.getWidth(), frame.getHeight()));
		if (!backgroundApplied)
		{
			client.setShouldRenderLoginScreenFire(config.showLoginFire());
			backgroundApplied = true;
		}
		nextFrameAtNanos = now + TimeUnit.MILLISECONDS.toNanos(frame.getDurationMillis());

		if (frame.isLastInLoop() && config.cycleTrigger() == CycleTrigger.LOOP)
		{
			advance();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!LoginScreenGifsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if (LoginScreenGifsConfig.KEY_SCALE_MODE.equals(event.getKey()))
		{
			playbackDirty = true;
		}
		else if (LoginScreenGifsConfig.KEY_SHOW_LOGIN_FIRE.equals(event.getKey()))
		{
			clientThread.invoke(() ->
			{
				if (backgroundApplied)
				{
					client.setShouldRenderLoginScreenFire(config.showLoginFire());
				}
			});
		}
		else
		{
			resetCycleTimer();
		}
	}

	/**
	 * Freezes the animation while the login screen has something interactive on it. The
	 * authenticator is held outright, and any click or keystroke holds the frame briefly, so
	 * the client thread is free to serve the input rather than a new background.
	 */
	private boolean isHoldingFrame(long now)
	{
		if (client.getGameState() == GameState.LOGIN_SCREEN_AUTHENTICATOR
			|| client.getLoginIndex() == LOGIN_INDEX_AUTHENTICATOR)
		{
			return true;
		}

		return config.pauseWhileInteracting() && now < holdUntilNanos;
	}

	private void holdFrame()
	{
		holdUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INTERACTION_HOLD_MILLIS);
	}

	private void onLoginScreenShown()
	{
		if (config.cycleTrigger() == CycleTrigger.LOGIN_SCREEN)
		{
			advance();
		}
		resetCycleTimer();
	}

	/** Moves to whichever GIF the configured pick order says is next. */
	private void advance()
	{
		List<String> names = GifRotation.names(library.list());
		String next = GifRotation.next(names, currentGifName, config.cycleOrder(), shuffleBag, random);
		if (next != null)
		{
			saveShuffleBag();
			selectGif(next);
		}
		resetCycleTimer();
	}

	private void resetCycleTimer()
	{
		cycleDueAtNanos = config.cycleTrigger() == CycleTrigger.TIMER
			? System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, config.cycleSeconds()))
			: 0L;
	}

	private void startPlayback()
	{
		stopDecoder();
		playbackDirty = false;

		File file = resolveCurrentFile();
		if (file == null)
		{
			restoreBackground();
			return;
		}

		Dimension canvas = canvasSize();
		Dimension output = outputSize(canvas);
		player = new GifPlayer(file, output.width, output.height, config.scaleMode());
		player.start();
		builtForCanvas = canvas;
		nextFrameAtNanos = 0L;
	}

	private void stopDecoder()
	{
		if (player != null)
		{
			player.stop();
			player = null;
		}
		builtForCanvas = null;
	}

	private void restoreBackground()
	{
		if (backgroundApplied)
		{
			client.setLoginScreen(null);
			client.setShouldRenderLoginScreenFire(true);
			backgroundApplied = false;
		}
	}

	/**
	 * Falls back to the first GIF in the folder when the stored one has been deleted or renamed,
	 * so the plugin keeps working after the folder is tidied up.
	 */
	private File resolveCurrentFile()
	{
		File file = library.resolve(currentGifName);
		if (file != null)
		{
			return file;
		}

		List<File> files = library.list();
		if (files.isEmpty())
		{
			return null;
		}

		file = files.get(0);
		currentGifName = file.getName();
		config.setCurrentGif(currentGifName);
		refreshPanel();
		return file;
	}

	private Dimension canvasSize()
	{
		Canvas canvas = client.getCanvas();
		int width = canvas == null ? 0 : canvas.getWidth();
		int height = canvas == null ? 0 : canvas.getHeight();
		if (width <= 0 || height <= 0)
		{
			return new Dimension(MIN_WIDTH, MIN_HEIGHT);
		}
		return new Dimension(width, height);
	}

	/**
	 * Renders at the size the window actually is, capped so a large monitor cannot push the
	 * per-frame cost up without limit. The client scales whatever sprite it is handed.
	 */
	static Dimension outputSize(Dimension canvas)
	{
		int width = Math.max(MIN_WIDTH, canvas.width);
		int height = Math.max(MIN_HEIGHT, canvas.height);
		double scale = Math.min(1.0, Math.min((double) MAX_WIDTH / width, (double) MAX_HEIGHT / height));
		return new Dimension(
			Math.max(1, (int) Math.round(width * scale)),
			Math.max(1, (int) Math.round(height * scale)));
	}

	private boolean canvasHasResized()
	{
		if (builtForCanvas == null)
		{
			return true;
		}

		Dimension canvas = canvasSize();
		return Math.abs(canvas.width - builtForCanvas.width) > RESIZE_TOLERANCE
			|| Math.abs(canvas.height - builtForCanvas.height) > RESIZE_TOLERANCE;
	}

	private void loadShuffleBag()
	{
		shuffleBag.clear();
		String stored = config.shuffleSeen();
		if (stored != null && !stored.isEmpty())
		{
			shuffleBag.addAll(Arrays.asList(stored.split(BAG_SEPARATOR)));
		}
	}

	private void saveShuffleBag()
	{
		config.setShuffleSeen(String.join(BAG_SEPARATOR, new ArrayList<>(shuffleBag)));
	}

	private void refreshPanel()
	{
		LoginScreenGifsPanel current = panel;
		if (current != null)
		{
			current.reload();
		}
	}

	private static BufferedImage createPanelIcon()
	{
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = icon.createGraphics();
		try
		{
			graphics.setColor(new Color(220, 138, 0));
			graphics.fillRect(1, 3, 14, 10);
			graphics.setColor(Color.BLACK);
			graphics.drawRect(1, 3, 13, 9);
			// A small play triangle, so the button reads as moving pictures.
			graphics.setColor(Color.BLACK);
			graphics.fillPolygon(new int[]{6, 6, 11}, new int[]{5, 11, 8}, 3);
		}
		finally
		{
			graphics.dispose();
		}
		return icon;
	}

	@Override
	public String getCurrentGifName()
	{
		return currentGifName;
	}

	@Override
	public void selectGif(String fileName)
	{
		currentGifName = fileName == null ? "" : fileName;
		config.setCurrentGif(currentGifName);
		// The render pump rebuilds the decoder on the client thread next frame.
		playbackDirty = true;
		refreshPanel();
	}

	@Override
	public void cycleBy(int offset)
	{
		String next = GifRotation.step(GifRotation.names(library.list()), currentGifName, offset);
		if (next != null)
		{
			selectGif(next);
		}
	}

	@Override
	public boolean isEnabledOnLoginScreen()
	{
		return true;
	}

	@Override
	public void keyTyped(KeyEvent event)
	{
	}

	@Override
	public void keyPressed(KeyEvent event)
	{
		holdFrame();
		if (config.nextGifHotkey().matches(event))
		{
			cycleBy(1);
		}
	}

	@Override
	public void keyReleased(KeyEvent event)
	{
		holdFrame();
	}

	@Provides
	LoginScreenGifsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LoginScreenGifsConfig.class);
	}
}
