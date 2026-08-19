package com.yonwiplugins.loginscreengifs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The side panel: every setting worth reaching quickly, plus the library itself with a still
 * thumbnail of each GIF. GIFs can arrive by file picker, by dragging them onto the panel, or by
 * being dropped into the folder directly.
 */
@Slf4j
class LoginScreenGifsPanel extends PluginPanel
{
	private static final int THUMBNAIL_WIDTH = 56;
	private static final int THUMBNAIL_HEIGHT = 32;
	private static final int LABEL_WIDTH = 58;
	private static final Color ROW_SELECTED = ColorScheme.BRAND_ORANGE;

	/**
	 * Decoded thumbnails, kept for the life of the client. The list is rebuilt every time a GIF
	 * is picked, so without this the whole library would be decoded again on every click.
	 * Keyed on the file's size and timestamp, so replacing a GIF still refreshes its thumbnail.
	 */
	private static final Map<String, ImageIcon> THUMBNAIL_CACHE = new ConcurrentHashMap<>();

	/** What the panel needs from the plugin. Keeps the panel free of client state. */
	interface Host
	{
		String getCurrentGifName();

		void selectGif(String fileName);

		void cycleBy(int offset);

		LoginScreenGifsConfig getConfig();
	}

	private final GifLibrary library;
	private final Host host;
	private final JPanel listPanel = new JPanel();
	private final JLabel nowPlaying = new JLabel();
	private final JLabel status = new JLabel();

	private final JComboBox<CycleTrigger> triggerBox = new JComboBox<>(CycleTrigger.values());
	private final JComboBox<CycleOrder> orderBox = new JComboBox<>(CycleOrder.values());
	private final JComboBox<ScaleMode> scaleBox = new JComboBox<>(ScaleMode.values());
	private final JSpinner secondsSpinner = new JSpinner(new SpinnerNumberModel(30, 3, 3600, 1));
	private final JPanel timerRow;

	private final ExecutorService thumbnailLoader = Executors.newSingleThreadExecutor(runnable ->
	{
		Thread thread = new Thread(runnable, "login-screen-gifs-thumbnails");
		thread.setDaemon(true);
		return thread;
	});

	/** Guards against the control listeners firing while the controls are being populated. */
	private boolean updatingControls;

	LoginScreenGifsPanel(GifLibrary library, Host host)
	{
		this.library = library;
		this.host = host;

		setLayout(new BorderLayout(0, 8));
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		timerRow = labelledRow("Timer", secondsSpinner);
		add(buildHeader(), BorderLayout.NORTH);

		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(listPanel, BorderLayout.CENTER);

		// Dropping GIFs anywhere on the panel adds them.
		setDropTarget(new DropTarget(this, DnDConstants.ACTION_COPY, new GifDropListener(), true));

		refreshControls();
		reload();
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Login Screen GIFs", SwingConstants.CENTER);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.CENTER_ALIGNMENT);
		header.add(title);

		nowPlaying.setFont(FontManager.getRunescapeSmallFont());
		nowPlaying.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		nowPlaying.setAlignmentX(Component.CENTER_ALIGNMENT);
		nowPlaying.setBorder(new EmptyBorder(4, 0, 6, 0));
		header.add(nowPlaying);

		JPanel cycleButtons = new JPanel(new GridLayout(1, 2, 4, 0));
		cycleButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cycleButtons.add(button("< Prev", "Play the previous GIF", () -> host.cycleBy(-1)));
		cycleButtons.add(button("Next >", "Play the next GIF", () -> host.cycleBy(1)));
		header.add(cycleButtons);

		header.add(sectionLabel("Cycling"));
		header.add(labelledRow("Change", triggerBox));
		header.add(labelledRow("Order", orderBox));
		header.add(timerRow);
		header.add(labelledRow("Sizing", scaleBox));

		triggerBox.setToolTipText("What makes the plugin switch to the next GIF");
		orderBox.setToolTipText("Which GIF comes next");
		scaleBox.setToolTipText("How each frame is fitted to the login screen");
		secondsSpinner.setToolTipText("Seconds each GIF is shown when Change is set to a timer");

		triggerBox.addActionListener(event -> onControlChanged(() ->
			host.getConfig().setCycleTrigger((CycleTrigger) triggerBox.getSelectedItem())));
		orderBox.addActionListener(event -> onControlChanged(() ->
			host.getConfig().setCycleOrder((CycleOrder) orderBox.getSelectedItem())));
		scaleBox.addActionListener(event -> onControlChanged(() ->
			host.getConfig().setScaleMode((ScaleMode) scaleBox.getSelectedItem())));
		secondsSpinner.addChangeListener(event -> onControlChanged(() ->
			host.getConfig().setCycleSeconds((Integer) secondsSpinner.getValue())));

		header.add(sectionLabel("Library"));
		JPanel fileButtons = new JPanel(new GridLayout(1, 2, 4, 0));
		fileButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		fileButtons.add(button("Add GIFs", "Pick GIFs to copy into the library", this::chooseFiles));
		fileButtons.add(button("Folder", "Open the GIF folder", this::openFolder));
		header.add(fileButtons);

		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setAlignmentX(Component.CENTER_ALIGNMENT);
		status.setBorder(new EmptyBorder(6, 0, 2, 0));
		header.add(status);

		return header;
	}

	private JLabel sectionLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setBorder(new EmptyBorder(10, 0, 4, 0));
		return label;
	}

	/** A fixed-width caption with the control filling whatever is left. */
	private JPanel labelledRow(String text, JComponent field)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(0, 0, 3, 0));
		row.setMaximumSize(new Dimension(PANEL_WIDTH, 26));

		JLabel caption = new JLabel(text);
		caption.setFont(FontManager.getRunescapeSmallFont());
		caption.setForeground(ColorScheme.TEXT_COLOR);
		caption.setPreferredSize(new Dimension(LABEL_WIDTH, 22));
		row.add(caption, BorderLayout.WEST);

		field.setFont(FontManager.getRunescapeSmallFont());
		row.add(field, BorderLayout.CENTER);
		return row;
	}

	private JButton button(String text, String tooltip, Runnable action)
	{
		JButton button = new JButton(text);
		button.setToolTipText(tooltip);
		button.setFocusPainted(false);
		button.addActionListener(event -> action.run());
		return button;
	}

	private void onControlChanged(Runnable write)
	{
		if (updatingControls)
		{
			return;
		}
		write.run();
		refreshControls();
	}

	/** Pulls the controls back in step with the config, wherever it was changed. */
	void refreshControls()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refreshControls);
			return;
		}

		LoginScreenGifsConfig config = host.getConfig();
		updatingControls = true;
		try
		{
			triggerBox.setSelectedItem(config.cycleTrigger());
			orderBox.setSelectedItem(config.cycleOrder());
			scaleBox.setSelectedItem(config.scaleMode());
			secondsSpinner.setValue(config.cycleSeconds());
			// The timer length only means anything for the timed trigger.
			timerRow.setVisible(config.cycleTrigger() == CycleTrigger.TIMER);
		}
		finally
		{
			updatingControls = false;
		}
	}

	/**
	 * Rebuilds the list from whatever is in the folder. Safe to call from any thread.
	 */
	void reload()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::reload);
			return;
		}

		List<File> files = library.list();
		listPanel.removeAll();

		if (files.isEmpty())
		{
			listPanel.add(emptyState());
		}
		else
		{
			String current = host.getCurrentGifName();
			for (File file : files)
			{
				listPanel.add(buildRow(file, file.getName().equalsIgnoreCase(current)));
			}
		}

		refreshNowPlaying(files.size());
		listPanel.revalidate();
		listPanel.repaint();
	}

	private JLabel emptyState()
	{
		JLabel empty = new JLabel("<html><div style='text-align:center;padding:8px'>"
			+ "No GIFs yet.<br><br>Drag GIFs onto this panel, or use Add GIFs. "
			+ "Any filename works.</div></html>", SwingConstants.CENTER);
		empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		empty.setFont(FontManager.getRunescapeSmallFont());
		return empty;
	}

	private void refreshNowPlaying(int count)
	{
		String current = host.getCurrentGifName();
		nowPlaying.setText(current == null || current.isEmpty() ? "Nothing playing" : "Playing: " + shorten(current));
		nowPlaying.setToolTipText(current);
		status.setText(count == 1 ? "1 GIF in the library" : count + " GIFs in the library");
	}

	private JPanel buildRow(File file, boolean selected)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, selected ? ROW_SELECTED : ColorScheme.DARKER_GRAY_COLOR),
			new EmptyBorder(4, 5, 4, 4)));
		row.setMaximumSize(new Dimension(PANEL_WIDTH, THUMBNAIL_HEIGHT + 16));
		row.setCursor(new Cursor(Cursor.HAND_CURSOR));
		row.setToolTipText(file.getName());

		JLabel thumbnail = new JLabel();
		// Fixed either way, so a row never changes height when its thumbnail arrives.
		thumbnail.setPreferredSize(new Dimension(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT));
		thumbnail.setMinimumSize(new Dimension(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT));
		thumbnail.setHorizontalAlignment(SwingConstants.CENTER);
		row.add(thumbnail, BorderLayout.WEST);
		showThumbnail(file, thumbnail);

		JLabel name = new JLabel(shorten(file.getName()));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(selected ? ROW_SELECTED : ColorScheme.TEXT_COLOR);
		row.add(name, BorderLayout.CENTER);

		JButton delete = new JButton("x");
		delete.setToolTipText("Delete " + file.getName());
		delete.setFont(FontManager.getRunescapeSmallFont());
		delete.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		delete.setFocusPainted(false);
		delete.setMargin(new Insets(0, 4, 0, 4));
		delete.addActionListener(event -> confirmDelete(file));
		row.add(delete, BorderLayout.EAST);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent event)
			{
				host.selectGif(file.getName());
				reload();
			}

			@Override
			public void mouseEntered(MouseEvent event)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent event)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		return row;
	}

	private void confirmDelete(File file)
	{
		int answer = JOptionPane.showConfirmDialog(
			this,
			"Delete " + file.getName() + " from the GIF folder?",
			"Delete GIF",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);

		if (answer != JOptionPane.YES_OPTION)
		{
			return;
		}

		if (library.remove(file.getName()))
		{
			THUMBNAIL_CACHE.remove(cacheKey(file));
			if (file.getName().equalsIgnoreCase(host.getCurrentGifName()))
			{
				host.selectGif(null);
			}
		}
		else
		{
			showError("Could not delete " + file.getName());
		}
		reload();
	}

	private void chooseFiles()
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Add GIFs");
		chooser.setMultiSelectionEnabled(true);
		chooser.setAcceptAllFileFilterUsed(false);
		chooser.setFileFilter(new FileNameExtensionFilter("GIF images", "gif"));

		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
		{
			importFiles(chooser.getSelectedFiles());
		}
	}

	private void openFolder()
	{
		library.ensureFolder();
		LinkBrowser.open(library.getFolder().getAbsolutePath());
	}

	/**
	 * Copies dropped or picked files into the library, reporting anything that would not take.
	 */
	private void importFiles(File[] candidates)
	{
		if (candidates == null || candidates.length == 0)
		{
			return;
		}

		List<String> failures = new ArrayList<>();
		File firstAdded = null;
		for (File candidate : candidates)
		{
			try
			{
				File added = library.add(candidate);
				if (firstAdded == null)
				{
					firstAdded = added;
				}
			}
			catch (Exception ex)
			{
				log.debug("Could not add {}", candidate, ex);
				failures.add(candidate.getName() + " (" + ex.getMessage() + ")");
			}
		}

		// A first GIF with nothing playing should start playing straight away.
		String current = host.getCurrentGifName();
		if (firstAdded != null && (current == null || current.isEmpty()))
		{
			host.selectGif(firstAdded.getName());
		}

		reload();

		if (!failures.isEmpty())
		{
			showError("These files were not added:\n" + String.join("\n", failures));
		}
	}

	/**
	 * Puts a still thumbnail on the row. A cached one goes straight on, so rebuilding the list
	 * costs nothing; anything new is decoded once on a background thread.
	 */
	private void showThumbnail(File file, JLabel target)
	{
		String key = cacheKey(file);
		ImageIcon cached = THUMBNAIL_CACHE.get(key);
		if (cached != null)
		{
			target.setIcon(cached);
			return;
		}

		thumbnailLoader.submit(() ->
		{
			ImageIcon icon = decodeThumbnail(file);
			if (icon == null)
			{
				return;
			}

			THUMBNAIL_CACHE.put(key, icon);
			SwingUtilities.invokeLater(() -> target.setIcon(icon));
		});
	}

	/** Identifies a file by what it is, so replacing a GIF refreshes its thumbnail. */
	private static String cacheKey(File file)
	{
		return file.getName() + ":" + file.length() + ":" + file.lastModified();
	}

	/**
	 * Reads the first frame only. The plugin decoder is used rather than the one in the JRE, so
	 * a thumbnail appears for every GIF the login screen can actually play.
	 */
	private static ImageIcon decodeThumbnail(File file)
	{
		try (GifDecoder decoder = GifDecoder.open(file))
		{
			GifDecoder.Frame frame = decoder.nextFrame();
			if (frame == null)
			{
				return null;
			}
			return new ImageIcon(scale(frame.getArgb(), decoder.getWidth(), decoder.getHeight()));
		}
		catch (Exception ex)
		{
			log.debug("Could not build a thumbnail for {}", file, ex);
			return null;
		}
	}

	private static BufferedImage scale(int[] argb, int sourceWidth, int sourceHeight)
	{
		BufferedImage source = new BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
		System.arraycopy(argb, 0, pixels, 0, Math.min(argb.length, pixels.length));

		BufferedImage scaled = new BufferedImage(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = scaled.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			// Fitted rather than cropped, so a tall GIF is still recognisable at this size.
			double scale = Math.min(
				(double) THUMBNAIL_WIDTH / sourceWidth,
				(double) THUMBNAIL_HEIGHT / sourceHeight);
			int width = Math.max(1, (int) Math.round(sourceWidth * scale));
			int height = Math.max(1, (int) Math.round(sourceHeight * scale));
			graphics.drawImage(source, (THUMBNAIL_WIDTH - width) / 2, (THUMBNAIL_HEIGHT - height) / 2,
				width, height, null);
		}
		finally
		{
			graphics.dispose();
		}
		return scaled;
	}

	@Override
	public void onActivate()
	{
		super.onActivate();
		reload();
		refreshControls();
	}

	private void showError(String message)
	{
		JOptionPane.showMessageDialog(this, message, "Login Screen GIFs", JOptionPane.WARNING_MESSAGE);
	}

	static String shorten(String name)
	{
		if (name == null)
		{
			return "";
		}
		String withoutExtension = GifLibrary.hasGifExtension(name)
			? name.substring(0, name.length() - GifLibrary.EXTENSION.length())
			: name;
		return withoutExtension.length() <= 18 ? withoutExtension : withoutExtension.substring(0, 17) + "...";
	}

	void shutDown()
	{
		thumbnailLoader.shutdownNow();
	}

	/**
	 * Accepts GIFs dragged onto the panel from a file manager or a browser download bar.
	 */
	private final class GifDropListener implements DropTargetListener
	{
		@Override
		public void dragEnter(DropTargetDragEvent event)
		{
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE, 2),
				new EmptyBorder(6, 6, 6, 6)));
		}

		@Override
		public void dragOver(DropTargetDragEvent event)
		{
		}

		@Override
		public void dropActionChanged(DropTargetDragEvent event)
		{
		}

		@Override
		public void dragExit(DropTargetEvent event)
		{
			resetBorder();
		}

		@Override
		public void drop(DropTargetDropEvent event)
		{
			resetBorder();
			try
			{
				if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
				{
					event.rejectDrop();
					return;
				}

				event.acceptDrop(DnDConstants.ACTION_COPY);
				Object data = event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
				List<?> dropped = (List<?>) data;

				List<File> files = new ArrayList<>(dropped.size());
				for (Object item : dropped)
				{
					if (item instanceof File)
					{
						files.add((File) item);
					}
				}

				importFiles(files.toArray(new File[0]));
				event.dropComplete(true);
			}
			catch (Exception ex)
			{
				log.warn("Could not accept the dropped files", ex);
				event.dropComplete(false);
			}
		}

		private void resetBorder()
		{
			setBorder(new EmptyBorder(8, 8, 8, 8));
		}
	}
}
