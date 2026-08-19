package com.yonwiplugins.loginscreengifs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Works out which GIF plays next. Kept free of client and file-system state so the cycling
 * rules can be tested on their own.
 */
final class GifRotation
{
	private GifRotation()
	{
	}

	static List<String> names(List<File> files)
	{
		List<String> names = new ArrayList<>(files.size());
		for (File file : files)
		{
			names.add(file.getName());
		}
		return names;
	}

	static int indexOf(List<String> names, String name)
	{
		if (name == null)
		{
			return -1;
		}

		for (int index = 0; index < names.size(); index++)
		{
			if (names.get(index).equalsIgnoreCase(name))
			{
				return index;
			}
		}
		return -1;
	}

	/**
	 * Moves a given number of places through the list, wrapping at both ends. Used by the
	 * previous and next buttons in the side panel.
	 */
	static String step(List<String> names, String current, int offset)
	{
		if (names.isEmpty())
		{
			return null;
		}

		int index = indexOf(names, current);
		int from = index < 0 ? 0 : index;
		return names.get(Math.floorMod(from + offset, names.size()));
	}

	/**
	 * Picks the next GIF for the configured order.
	 *
	 * @param seen the shuffle bag, updated in place; ignored by the other orders
	 */
	static String next(List<String> names, String current, CycleOrder order, Set<String> seen, Random random)
	{
		if (names.isEmpty())
		{
			return null;
		}
		if (names.size() == 1)
		{
			return names.get(0);
		}

		switch (order)
		{
			case RANDOM:
				return randomOther(names, current, random);
			case SHUFFLE:
				return shuffleNext(names, current, seen, random);
			case IN_ORDER:
			default:
				return step(names, current, 1);
		}
	}

	private static String randomOther(List<String> names, String current, Random random)
	{
		int currentIndex = indexOf(names, current);
		if (currentIndex < 0)
		{
			return names.get(random.nextInt(names.size()));
		}

		// Draw from the list with the current entry removed, so a random pick never repeats
		// the GIF that is already on screen.
		int drawn = random.nextInt(names.size() - 1);
		return names.get(drawn >= currentIndex ? drawn + 1 : drawn);
	}

	/**
	 * Plays every GIF once before any of them come round again. The bag is refilled once it is
	 * empty, and entries for deleted files are dropped so a removed GIF cannot wedge it.
	 */
	private static String shuffleNext(List<String> names, String current, Set<String> seen, Random random)
	{
		seen.retainAll(new LinkedHashSet<>(names));

		List<String> unseen = remaining(names, current, seen);
		if (unseen.isEmpty())
		{
			seen.clear();
			unseen = remaining(names, current, seen);
		}

		String pick = unseen.get(random.nextInt(unseen.size()));
		if (current != null)
		{
			seen.add(current);
		}
		seen.add(pick);
		return pick;
	}

	private static List<String> remaining(List<String> names, String current, Set<String> seen)
	{
		List<String> remaining = new ArrayList<>(names.size());
		for (String name : names)
		{
			if (!seen.contains(name) && !name.equalsIgnoreCase(current))
			{
				remaining.add(name);
			}
		}
		return remaining;
	}
}
