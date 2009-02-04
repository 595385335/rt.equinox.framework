/*******************************************************************************
 * Copyright (c) 2006, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.tests.bundles;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import junit.framework.Test;
import junit.framework.TestSuite;
import org.eclipse.osgi.tests.OSGiTestsActivator;
import org.osgi.framework.*;
import org.osgi.service.startlevel.StartLevel;

public class ClassLoadingBundleTests extends AbstractBundleTests {
	public static Test suite() {
		return new TestSuite(ClassLoadingBundleTests.class);
	}

	public void testSimple() throws Exception {
		Bundle test = installer.installBundle("test");
		test.start();
		installer.shutdown();

		Object[] expectedEvents = new Object[6];
		expectedEvents[0] = new BundleEvent(BundleEvent.INSTALLED, test);
		expectedEvents[1] = new BundleEvent(BundleEvent.RESOLVED, test);
		expectedEvents[2] = new BundleEvent(BundleEvent.STARTED, test);
		expectedEvents[3] = new BundleEvent(BundleEvent.STOPPED, test);
		expectedEvents[4] = new BundleEvent(BundleEvent.UNRESOLVED, test);
		expectedEvents[5] = new BundleEvent(BundleEvent.UNINSTALLED, test);

		Object[] actualEvents = listenerResults.getResults(expectedEvents.length);
		compareResults(expectedEvents, actualEvents);
	}

	public void testChainDepedencies() throws Exception {
		Bundle chainTest = installer.installBundle("chain.test");
		Bundle chainTestA = installer.installBundle("chain.test.a");
		Bundle chainTestB = installer.installBundle("chain.test.b");
		installer.installBundle("chain.test.c");
		installer.installBundle("chain.test.d");
		((ITestRunner) chainTest.loadClass("chain.test.TestSingleChain").newInstance()).testIt();


		Object[] expectedEvents = new Object[6];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, chainTestB);
		expectedEvents[1] = new BundleEvent(BundleEvent.STARTED, chainTestA);
		expectedEvents[2] = new BundleEvent(BundleEvent.STOPPED, chainTestA);
		expectedEvents[3] = new BundleEvent(BundleEvent.STOPPED, chainTestB);
		expectedEvents[4] = new BundleEvent(BundleEvent.STARTED, chainTestB);
		expectedEvents[5] = new BundleEvent(BundleEvent.STARTED, chainTestA);

		installer.refreshPackages(new Bundle[] {chainTestB});

		((ITestRunner) chainTest.loadClass("chain.test.TestSingleChain").newInstance()).testIt();

		Object[] actualEvents = simpleResults.getResults(6);
		compareResults(expectedEvents, actualEvents);
	}

	public void testMultiChainDepedencies() throws Exception {
		Bundle chainTest = installer.installBundle("chain.test");
		Bundle chainTestA = installer.installBundle("chain.test.a");
		Bundle chainTestB = installer.installBundle("chain.test.b");
		Bundle chainTestC = installer.installBundle("chain.test.c");
		Bundle chainTestD = installer.installBundle("chain.test.d");
		chainTest.loadClass("chain.test.TestMultiChain").newInstance();


		Object[] expectedEvents = new Object[8];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, chainTestD);
		expectedEvents[1] = new BundleEvent(BundleEvent.STARTED, chainTestB);
		expectedEvents[2] = new BundleEvent(BundleEvent.STARTED, chainTestC);
		expectedEvents[3] = new BundleEvent(BundleEvent.STARTED, chainTestA);
		expectedEvents[4] = new BundleEvent(BundleEvent.STOPPED, chainTestA);
		expectedEvents[5] = new BundleEvent(BundleEvent.STOPPED, chainTestB);
		expectedEvents[6] = new BundleEvent(BundleEvent.STOPPED, chainTestC);
		expectedEvents[7] = new BundleEvent(BundleEvent.STOPPED, chainTestD);

		installer.refreshPackages(new Bundle[] {chainTestC, chainTestD});

		Object[] actualEvents = simpleResults.getResults(8);
		compareResults(expectedEvents, actualEvents);
	}

	public void testClassCircularityError() throws Exception {
		Bundle circularityTest = installer.installBundle("circularity.test");
		Bundle circularityTestA = installer.installBundle("circularity.test.a");
		circularityTest.loadClass("circularity.test.TestCircularity");

		Object[] expectedEvents = new Object[2];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, circularityTest);
		expectedEvents[1] = new BundleEvent(BundleEvent.STARTED, circularityTestA);
		Object[] actualEvents = simpleResults.getResults(2);
		compareResults(expectedEvents, actualEvents);
	}

	public void testFragmentPackageAccess() throws Exception {
		Bundle hostA = installer.installBundle("fragment.test.attach.host.a");
		Bundle fragA = installer.installBundle("fragment.test.attach.frag.a");
		assertTrue("Host/Frag resolve", installer.resolveBundles(new Bundle[] {hostA, fragA}));

		ITestRunner testRunner = (ITestRunner) hostA.loadClass("fragment.test.attach.host.a.internal.test.TestPackageAccess").newInstance();
		try {
			testRunner.testIt();
		} catch (Exception e) {
			fail("Failed package access test: " + e.getMessage());
		}
	}

	public void testLegacyLazyStart() throws Exception {
		Bundle legacy = installer.installBundle("legacy.lazystart");
		Bundle legacyA = installer.installBundle("legacy.lazystart.a");
		Bundle legacyB = installer.installBundle("legacy.lazystart.b");
		Bundle legacyC = installer.installBundle("legacy.lazystart.c");
		assertTrue("legacy lazy start resolve", installer.resolveBundles(new Bundle[] {legacy, legacyA, legacyB, legacyC}));

		((ITestRunner) legacy.loadClass("legacy.lazystart.SimpleLegacy").newInstance()).testIt();
		Object[] expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, legacyA);
		Object[] actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);

		((ITestRunner) legacy.loadClass("legacy.lazystart.TrueExceptionLegacy1").newInstance()).testIt();
		assertTrue("exceptions no event", simpleResults.getResults(0).length == 0);
		((ITestRunner) legacy.loadClass("legacy.lazystart.TrueExceptionLegacy2").newInstance()).testIt();
		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, legacyB);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);

		((ITestRunner) legacy.loadClass("legacy.lazystart.FalseExceptionLegacy1").newInstance()).testIt();
		assertTrue("exceptions no event", simpleResults.getResults(0).length == 0);
		((ITestRunner) legacy.loadClass("legacy.lazystart.FalseExceptionLegacy2").newInstance()).testIt();
		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, legacyC);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);
	}

	public void testLegacyLoadActivation() throws Exception {
		// test that calling loadClass from a non-lazy start bundle does not activate the bundle
		Bundle test = installer.installBundle("test");
		try {
			test.loadClass("does.not.exist.Test");
		} catch (ClassNotFoundException e) {
			// expected
		}
		Object[] expectedEvents = new Object[0];
		Object[] actualEvents = simpleResults.getResults(0);
		compareResults(expectedEvents, actualEvents);

		// test that calling loadClass from a lazy start bundle activates a bundle
		Bundle legacyA = installer.installBundle("legacy.lazystart.a");
		try {
			legacyA.loadClass("does.not.exist.Test");
		} catch (ClassNotFoundException e) {
			// expected
		}
		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, legacyA);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);
	}

	public void testOSGiLazyStart() throws Exception {
		Bundle osgi = installer.installBundle("osgi.lazystart");
		Bundle osgiA = installer.installBundle("osgi.lazystart.a");
		Bundle osgiB = installer.installBundle("osgi.lazystart.b");
		Bundle osgiC = installer.installBundle("osgi.lazystart.c");
		assertTrue("osgi lazy start resolve", installer.resolveBundles(new Bundle[] {osgi, osgiA, osgiB, osgiC}));

		((ITestRunner) osgi.loadClass("osgi.lazystart.LazySimple").newInstance()).testIt();
		Object[] expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, osgiA);
		Object[] actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);

		((ITestRunner) osgi.loadClass("osgi.lazystart.LazyExclude1").newInstance()).testIt();
		assertTrue("exceptions no event", simpleResults.getResults(0).length == 0);
		((ITestRunner) osgi.loadClass("osgi.lazystart.LazyExclude2").newInstance()).testIt();
		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, osgiB);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);

		((ITestRunner) osgi.loadClass("osgi.lazystart.LazyInclude1").newInstance()).testIt();
		assertTrue("exceptions no event", simpleResults.getResults(0).length == 0);
		((ITestRunner) osgi.loadClass("osgi.lazystart.LazyInclude2").newInstance()).testIt();
		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, osgiC);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);
	}

	public void testStartTransientByLoadClass() throws Exception {
		// install a bundle and set its start-level high, then crank up the framework start-level.  This should result in no events
		Bundle osgiA = installer.installBundle("osgi.lazystart.a");
		installer.resolveBundles(new Bundle[] {osgiA});
		StartLevel startLevel = installer.getStartLevel();
		startLevel.setBundleStartLevel(osgiA, startLevel.getStartLevel() + 10);

		// test transient start by loadClass
		startLevel.setStartLevel(startLevel.getStartLevel() + 15);
		Object[] expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		Object[] actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);
	
		startLevel.setStartLevel(startLevel.getStartLevel() - 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		Object[] expectedEvents = new Object[0];
		Object[] actualEvents = simpleResults.getResults(0);
		compareResults(expectedEvents, actualEvents);

		// now load a class from it before the start-level is met.  This should result in no events
		osgiA.loadClass("osgi.lazystart.a.ATest");
		expectedEvents = new Object[0];
		actualEvents = simpleResults.getResults(0);
		compareResults(expectedEvents, actualEvents);

		startLevel.setStartLevel(startLevel.getStartLevel() + 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		startLevel.setStartLevel(startLevel.getStartLevel() - 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		expectedEvents = new Object[0];
		actualEvents = simpleResults.getResults(0);
		compareResults(expectedEvents, actualEvents);

		// now load a class while start-level is met.
		startLevel.setStartLevel(startLevel.getStartLevel() + 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		osgiA.loadClass("osgi.lazystart.a.ATest");
		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, osgiA);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);

		startLevel.setStartLevel(startLevel.getStartLevel() - 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STOPPED, osgiA);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);
	}

	public void testStartTransient() throws Exception {
		// install a bundle and set its start-level high, then crank up the framework start-level.  This should result in no events
		Bundle osgiA = installer.installBundle("osgi.lazystart.a");
		installer.resolveBundles(new Bundle[] {osgiA});
		StartLevel startLevel = installer.getStartLevel();
		startLevel.setBundleStartLevel(osgiA, startLevel.getStartLevel() + 10);

		// test transient start Bundle.start(START_TRANSIENT)
		startLevel.setStartLevel(startLevel.getStartLevel() + 15);
		Object[] expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		Object[] actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);
	
		startLevel.setStartLevel(startLevel.getStartLevel() - 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		Object[] expectedEvents = new Object[0];
		Object[] actualEvents = simpleResults.getResults(0);
		compareResults(expectedEvents, actualEvents);

		// now call start(START_TRANSIENT) before the start-level is met.  This should result in no events
		try {
			osgiA.start(Bundle.START_TRANSIENT);
			assertFalse("Bundle is started!!", osgiA.getState() == Bundle.ACTIVE);
		} catch (BundleException e) {
			// expected
		}
		expectedEvents = new Object[0];
		actualEvents = simpleResults.getResults(0);
		compareResults(expectedEvents, actualEvents);

		startLevel.setStartLevel(startLevel.getStartLevel() + 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		startLevel.setStartLevel(startLevel.getStartLevel() - 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		expectedEvents = new Object[0];
		actualEvents = simpleResults.getResults(0);
		compareResults(expectedEvents, actualEvents);

		// now call start(START_TRANSIENT) while start-level is met.
		startLevel.setStartLevel(startLevel.getStartLevel() + 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		osgiA.start(Bundle.START_TRANSIENT);

		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, osgiA);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);

		startLevel.setStartLevel(startLevel.getStartLevel() - 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STOPPED, osgiA);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);
	}

	public void testStopTransient() throws Exception {
		Bundle osgiA = installer.installBundle("osgi.lazystart.a");
		installer.resolveBundles(new Bundle[] {osgiA});
		StartLevel startLevel = installer.getStartLevel();
		startLevel.setBundleStartLevel(osgiA, startLevel.getStartLevel() + 10);
		// persistently start the bundle
		osgiA.start();

		// test that the bundle is started when start-level is met
		startLevel.setStartLevel(startLevel.getStartLevel() + 15);
		Object[] expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		Object[] actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		Object[] expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, osgiA);
		Object[] actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);

		startLevel.setStartLevel(startLevel.getStartLevel() - 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STOPPED, osgiA);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);

		// now call stop(STOP_TRANSIENT) while the start-level is met.
		startLevel.setStartLevel(startLevel.getStartLevel() + 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, osgiA);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);

		osgiA.stop(Bundle.STOP_TRANSIENT);

		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STOPPED, osgiA);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);

		startLevel.setStartLevel(startLevel.getStartLevel() - 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		// now make sure the bundle still restarts when start-level is met
		startLevel.setStartLevel(startLevel.getStartLevel() + 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STARTED, osgiA);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);

		startLevel.setStartLevel(startLevel.getStartLevel() - 15);
		expectedFrameworkEvents = new Object[1];
		expectedFrameworkEvents[0] = new FrameworkEvent(FrameworkEvent.STARTLEVEL_CHANGED, OSGiTestsActivator.getContext().getBundle(0), null);
		actualFrameworkEvents = frameworkListenerResults.getResults(1);
		compareResults(expectedFrameworkEvents, actualFrameworkEvents);

		expectedEvents = new Object[1];
		expectedEvents[0] = new BundleEvent(BundleEvent.STOPPED, osgiA);
		actualEvents = simpleResults.getResults(1);
		compareResults(expectedEvents, actualEvents);
	}

	public void testThreadLock() throws Exception {
		Bundle threadLockTest = installer.installBundle("thread.locktest");
		threadLockTest.loadClass("thread.locktest.ATest");

		Object[] expectedEvents = new Object[2];
		expectedEvents[0] = new Long(5000);
		expectedEvents[1] = new BundleEvent(BundleEvent.STARTED, threadLockTest);
		Object[] actualEvents = simpleResults.getResults(2);
		compareResults(expectedEvents, actualEvents);
		
	}

	public void testURLsBug164077() throws Exception {
		Bundle test = installer.installBundle("test");
		installer.resolveBundles(new Bundle[] {test});
		URL[] urls = new URL[2];
		urls[0] = test.getResource("a/b/c/d");
		urls[1] = test.getEntry("a/b/c/d");
		assertNotNull("resource", urls[0]);
		assertNotNull("entry", urls[1]);
		for (int i = 0; i < urls.length; i++) {
			URL testURL = new URL(urls[i], "g");
			assertEquals("g", "/a/b/c/g", testURL.getPath());
			testURL = new URL(urls[i], "./g");
			assertEquals("./g", "/a/b/c/g", testURL.getPath());
			testURL = new URL(urls[i], "g/");
			assertEquals("g/", "/a/b/c/g/", testURL.getPath());
			testURL = new URL(urls[i], "/g");
			assertEquals("/g", "/g", testURL.getPath());
			testURL = new URL(urls[i], "?y");
			assertEquals("?y", "/a/b/c/?y", testURL.getPath());
			testURL = new URL(urls[i], "g?y");
			assertEquals("g?y", "/a/b/c/g?y", testURL.getPath());
			testURL = new URL(urls[i], "g#s");
			assertEquals("g#s", "/a/b/c/g#s", testURL.getPath() + "#s");
			testURL = new URL(urls[i], "g?y#s");
			assertEquals("g?y#s", "/a/b/c/g?y#s", testURL.getPath() + "#s");
			testURL = new URL(urls[i], ";x");
			assertEquals(";x", "/a/b/c/;x", testURL.getPath());
			testURL = new URL(urls[i], "g;x");
			assertEquals("g;x", "/a/b/c/g;x", testURL.getPath());
			testURL = new URL(urls[i], "g;x?y#s");
			assertEquals("g;x?y#s", "/a/b/c/g;x?y#s", testURL.getPath() + "#s");
			testURL = new URL(urls[i], ".");
			assertEquals(".", "/a/b/c/", testURL.getPath());
			testURL = new URL(urls[i], "./");
			assertEquals("./", "/a/b/c/", testURL.getPath());
			testURL = new URL(urls[i], "..");
			assertEquals("..", "/a/b/", testURL.getPath());
			testURL = new URL(urls[i], "../");
			assertEquals("../", "/a/b/", testURL.getPath());
			testURL = new URL(urls[i], "../g");
			assertEquals("../g", "/a/b/g", testURL.getPath());
			testURL = new URL(urls[i], "../..");
			assertEquals("../..", "/a/", testURL.getPath());
			testURL = new URL(urls[i], "../../");
			assertEquals("../../", "/a/", testURL.getPath());
			testURL = new URL(urls[i], "../../g");
			assertEquals("../../g", "/a/g", testURL.getPath());
		}
	}

	public void testBootGetResources() throws Exception{
		if (System.getProperty(Constants.FRAMEWORK_BOOTDELEGATION) != null)
			return; // cannot really test this if this property is set
		// make sure there is only one manifest found
		Bundle test = installer.installBundle("test");
		Enumeration manifests = test.getResources("META-INF/MANIFEST.MF");
		assertNotNull("manifests", manifests);
		ArrayList manifestURLs = new ArrayList();
		while(manifests.hasMoreElements())
			manifestURLs.add(manifests.nextElement());
		assertEquals("manifest number", 1, manifestURLs.size());
		URL manifest = (URL) manifestURLs.get(0);
		assertEquals("host id", test.getBundleId(), Long.parseLong(manifest.getHost()));
	}

	public void testMultipleGetResources01() throws Exception {
		Bundle test = installer.installBundle("test");
		// test that we can get multiple resources from a bundle
		Enumeration resources = test.getResources("data/resource1");
		assertNotNull("resources", resources);
		ArrayList resourceURLs = new ArrayList();
		while(resources.hasMoreElements())
			resourceURLs.add(resources.nextElement());
		assertEquals("resource number", 2, resourceURLs.size());
		assertEquals("root resource", "root classpath", readURL((URL) resourceURLs.get(0)));
		assertEquals("stuff resource", "stuff classpath", readURL((URL) resourceURLs.get(1)));
	}

	public void testMultipleGetResources02() throws Exception {
		installer.installBundle("test");
		Bundle test2 = installer.installBundle("test2");
		// test that we can get multiple resources from a bundle
		Enumeration resources = test2.getResources("data/resource1");
		assertNotNull("resources", resources);
		ArrayList resourceURLs = new ArrayList();
		while(resources.hasMoreElements())
			resourceURLs.add(resources.nextElement());
		assertEquals("resource number", 4, resourceURLs.size());
		assertEquals("root resource", "root classpath", readURL((URL) resourceURLs.get(0)));
		assertEquals("stuff resource", "stuff classpath", readURL((URL) resourceURLs.get(1)));
		assertEquals("root resource", "root classpath test2", readURL((URL) resourceURLs.get(2)));
		assertEquals("stuff resource", "stuff classpath test2", readURL((URL) resourceURLs.get(3)));
	}

	public void testBug207847() throws BundleException {
		Bundle test = installer.installBundle("test"); //$NON-NLS-1$
		installer.resolveBundles(new Bundle[] {test});
		test.start();

		Bundle frag1 = installer.installBundle("test.fragment1"); //$NON-NLS-1$
		Bundle frag2 = installer.installBundle("test.fragment2"); //$NON-NLS-1$
		Bundle frag3 = installer.installBundle("test.fragment3"); //$NON-NLS-1$
		Bundle frag4 = installer.installBundle("test.fragment4"); //$NON-NLS-1$
		Bundle frag5 = installer.installBundle("test.fragment5"); //$NON-NLS-1$
		installer.resolveBundles(new Bundle[] {frag1, frag2, frag3, frag4, frag5});

		assertTrue("host is not resolved", (test.getState() & Bundle.ACTIVE) != 0); //$NON-NLS-1$
		assertTrue("frag1 is not resolved", (frag1.getState() & Bundle.RESOLVED) != 0); //$NON-NLS-1$
		assertTrue("frag2 is not resolved", (frag2.getState() & Bundle.RESOLVED) != 0); //$NON-NLS-1$
		assertTrue("frag3 is not resolved", (frag3.getState() & Bundle.RESOLVED) != 0); //$NON-NLS-1$
		assertTrue("frag4 is not resolved", (frag4.getState() & Bundle.RESOLVED) != 0); //$NON-NLS-1$
		assertTrue("frag5 is not resolved", (frag5.getState() & Bundle.RESOLVED) != 0); //$NON-NLS-1$
	}



// TODO temporarily disable til we can debug the build test machine on Win XP
//	public void testBuddyClassLoadingRegistered1() throws Exception{
//		Bundle registeredA = installer.installBundle("buddy.registered.a");
//		installer.resolveBundles(new Bundle[] {registeredA});
//		Enumeration testFiles = registeredA.getResources("resources/test.txt");
//		assertNotNull("testFiles", testFiles);
//		ArrayList testURLs = new ArrayList();
//		while(testFiles.hasMoreElements())
//			testURLs.add(testFiles.nextElement());
//		assertEquals("test.txt number", 1, testURLs.size());
//		assertEquals("buddy.registered.a", "buddy.registered.a", readURL((URL) testURLs.get(0)));
//
//		Bundle registeredATest1 = installer.installBundle("buddy.registered.a.test1");
//		Bundle registeredATest2 = installer.installBundle("buddy.registered.a.test2");
//		installer.resolveBundles(new Bundle[] {registeredATest1, registeredATest2});
//		testFiles = registeredA.getResources("resources/test.txt");
//		assertNotNull("testFiles", testFiles);
//		testURLs = new ArrayList();
//		while(testFiles.hasMoreElements())
//			testURLs.add(testFiles.nextElement());
//
//		// TODO some debug code to figure out why this is failing on the test machine
//		if (registeredATest1.getState() != Bundle.RESOLVED) {
//			System.out.println("Bundle is not resolved!! " + registeredATest1.getSymbolicName());
//			State state = Platform.getPlatformAdmin().getState(false);
//			BundleDescription aDesc = state.getBundle(registeredATest1.getBundleId());
//			ResolverError[] errors = state.getResolverErrors(aDesc);
//			for (int i = 0; i < errors.length; i++)
//				System.out.println(errors[i]);
//		}
//		if (registeredATest2.getState() != Bundle.RESOLVED) {
//			System.out.println("Bundle is not resolved!! " + registeredATest2.getSymbolicName());
//			State state = Platform.getPlatformAdmin().getState(false);
//			BundleDescription bDesc = state.getBundle(registeredATest2.getBundleId());
//			ResolverError[] errors = state.getResolverErrors(bDesc);
//			for (int i = 0; i < errors.length; i++)
//				System.out.println(errors[i]);
//		}
//
//		// The real test
//		assertEquals("test.txt number", 3, testURLs.size());
//		assertEquals("buddy.registered.a", "buddy.registered.a", readURL((URL) testURLs.get(0)));
//		assertEquals("buddy.registered.a.test1", "buddy.registered.a.test1", readURL((URL) testURLs.get(1)));
//		assertEquals("buddy.registered.a.test2", "buddy.registered.a.test2", readURL((URL) testURLs.get(2)));
//	}
//
//	public void testBuddyClassLoadingDependent1() throws Exception{
//		Bundle dependentA = installer.installBundle("buddy.dependent.a");
//		installer.resolveBundles(new Bundle[] {dependentA});
//		Enumeration testFiles = dependentA.getResources("resources/test.txt");
//		assertNotNull("testFiles", testFiles);
//		ArrayList testURLs = new ArrayList();
//		while(testFiles.hasMoreElements())
//			testURLs.add(testFiles.nextElement());
//		assertEquals("test.txt number", 1, testURLs.size());
//		assertEquals("buddy.dependent.a", "buddy.dependent.a", readURL((URL) testURLs.get(0)));
//
//		Bundle dependentATest1 = installer.installBundle("buddy.dependent.a.test1");
//		Bundle dependentATest2 = installer.installBundle("buddy.dependent.a.test2");
//		installer.resolveBundles(new Bundle[] {dependentATest1, dependentATest2});
//		testFiles = dependentA.getResources("resources/test.txt");
//		assertNotNull("testFiles", testFiles);
//		testURLs = new ArrayList();
//		while(testFiles.hasMoreElements())
//			testURLs.add(testFiles.nextElement());
//		assertEquals("test.txt number", 3, testURLs.size());
//		assertEquals("buddy.dependent.a", "buddy.dependent.a", readURL((URL) testURLs.get(0)));
//		assertEquals("buddy.dependent.a.test1", "buddy.dependent.a.test1", readURL((URL) testURLs.get(1)));
//		assertEquals("buddy.dependent.a.test2", "buddy.dependent.a.test2", readURL((URL) testURLs.get(2)));
//	}

	private String readURL(URL url) throws IOException {
		StringBuffer sb = new StringBuffer();
		BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
		try {
			for (String line = reader.readLine(); line != null;) {
				sb.append(line);
				line = reader.readLine();
				if (line != null)
					sb.append('\n');
			}
		} finally {
			reader.close();
		}
		return sb.toString();
	}
}
