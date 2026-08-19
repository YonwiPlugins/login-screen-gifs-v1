package com.yonwiplugins.loginscreengifs;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GifRotationTest
{
	private static final List<String> THREE = Arrays.asList("a.gif", "b.gif", "c.gif");

	@Test
	public void stepWrapsBothWays()
	{
		assertEquals("b.gif", GifRotation.step(THREE, "a.gif", 1));
		assertEquals("a.gif", GifRotation.step(THREE, "c.gif", 1));
		assertEquals("c.gif", GifRotation.step(THREE, "a.gif", -1));
	}

	@Test
	public void stepStartsAtTheTopWhenNothingIsPlaying()
	{
		assertEquals("a.gif", GifRotation.step(THREE, null, 0));
		assertEquals("b.gif", GifRotation.step(THREE, "missing.gif", 1));
	}

	@Test
	public void anEmptyLibraryHasNoNextGif()
	{
		assertNull(GifRotation.step(Collections.emptyList(), null, 1));
		assertNull(GifRotation.next(Collections.emptyList(), null, CycleOrder.IN_ORDER, bag(), new Random(1)));
	}

	@Test
	public void aSingleGifKeepsPlaying()
	{
		List<String> one = Collections.singletonList("only.gif");

		assertEquals("only.gif", GifRotation.next(one, "only.gif", CycleOrder.RANDOM, bag(), new Random(1)));
		assertEquals("only.gif", GifRotation.next(one, "only.gif", CycleOrder.SHUFFLE, bag(), new Random(1)));
	}

	@Test
	public void inOrderWalksTheListAndWraps()
	{
		assertEquals("b.gif", GifRotation.next(THREE, "a.gif", CycleOrder.IN_ORDER, bag(), new Random(1)));
		assertEquals("a.gif", GifRotation.next(THREE, "c.gif", CycleOrder.IN_ORDER, bag(), new Random(1)));
	}

	@Test
	public void randomNeverRepeatsTheGifAlreadyOnScreen()
	{
		Random random = new Random(20260820L);
		for (int attempt = 0; attempt < 200; attempt++)
		{
			assertNotEquals("b.gif", GifRotation.next(THREE, "b.gif", CycleOrder.RANDOM, bag(), random));
		}
	}

	@Test
	public void shufflePlaysEveryGifBeforeAnyRepeat()
	{
		Random random = new Random(7L);
		Set<String> seen = bag();
		Set<String> played = new HashSet<>();

		String current = "a.gif";
		played.add(current);
		// Two more picks are all it takes to have played the whole library once.
		for (int pick = 0; pick < 2; pick++)
		{
			current = GifRotation.next(THREE, current, CycleOrder.SHUFFLE, seen, random);
			assertTrue("shuffle repeated " + current + " before the bag was empty", played.add(current));
		}

		assertEquals(new HashSet<>(THREE), played);
	}

	@Test
	public void shuffleRefillsOnceEveryGifHasPlayed()
	{
		Random random = new Random(11L);
		Set<String> seen = bag();
		seen.addAll(THREE);

		// The bag is full, so the next pick has to reset it rather than return nothing.
		String next = GifRotation.next(THREE, "a.gif", CycleOrder.SHUFFLE, seen, random);

		assertTrue(THREE.contains(next));
		assertNotEquals("a.gif", next);
	}

	@Test
	public void shuffleForgetsGifsThatHaveBeenDeleted()
	{
		Set<String> seen = bag();
		seen.addAll(Arrays.asList("a.gif", "deleted.gif"));

		GifRotation.next(THREE, "a.gif", CycleOrder.SHUFFLE, seen, new Random(3L));

		assertTrue("a stale entry would slowly wedge the bag", !seen.contains("deleted.gif"));
	}

	@Test
	public void namesAreMatchedIgnoringCase()
	{
		assertEquals(1, GifRotation.indexOf(THREE, "B.GIF"));
		assertEquals(-1, GifRotation.indexOf(THREE, "nope.gif"));
		assertEquals(-1, GifRotation.indexOf(THREE, null));
	}

	private static Set<String> bag()
	{
		return new LinkedHashSet<>();
	}
}
