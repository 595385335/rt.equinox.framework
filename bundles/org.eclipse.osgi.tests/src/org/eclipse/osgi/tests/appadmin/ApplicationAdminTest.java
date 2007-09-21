/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.tests.appadmin;

import java.util.*;
import java.util.Map.Entry;
import junit.framework.Test;
import junit.framework.TestSuite;
import org.eclipse.core.tests.session.ConfigurationSessionTestSuite;
import org.eclipse.core.tests.session.SetupManager.SetupException;
import org.eclipse.equinox.internal.app.EclipseAppHandle;
import org.eclipse.osgi.tests.OSGiTest;
import org.eclipse.osgi.tests.OSGiTestsActivator;
import org.eclipse.osgi.tests.bundles.BundleInstaller;
import org.osgi.framework.*;
import org.osgi.service.application.*;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

public class ApplicationAdminTest extends OSGiTest {
	public static final String testRunnerApp = "org.eclipse.pde.junit.runtime.coretestapplicationnonmain"; //$NON-NLS-1$
	public static final String testResults = "test.results"; //$NON-NLS-1$
	public static final String SUCCESS = "success"; //$NON-NLS-1$
	public static final String ADDED = "added"; //$NON-NLS-1$
	public static final String MODIFIED = "modified"; //$NON-NLS-1$
	public static final String REMOVED = "removed"; //$NON-NLS-1$
	public static final String simpleResults = "test.simpleResults"; //$NON-NLS-1$
	public static final String[] tests = new String[] {"testSimpleApp", "testGlobalSingleton", "testCardinality01", "testCardinality02", "testMainThreaded01", "testMainThreaded02", "testHandleEvents01", "testDescriptorEvents01", "testPersistentLock01", "testPersistentLock02", "testPersistentLock03", "testPersistentSchedule01", "testPersistentSchedule02", "testPersistentSchedule03", "testPersistentSchedule04", "testPersistentSchedule05", "testPersistentSchedule06", "testPersistentSchedule07", "testPersistentSchedule08", "testFailedApplication01", "testDestroyBeforeStart01", "testDestroyBeforeStart02"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$ //$NON-NLS-12$ //$NON-NLS-13$ //$NON-NLS-14$ //$NON-NLS-15$ //$NON-NLS-16$ //$NON-NLS-17$ //$NON-NLS-18$ //$NON-NLS-19$ //$NON-NLS-20$ //$NON-NLS-21$ //$NON-NLS-22$
	private static final String PI_OSGI_SERVICES = "org.eclipse.osgi.services"; //$NON-NLS-1$

	public static Test suite() {
		TestSuite suite = new TestSuite(ApplicationAdminTest.class.getName());

		ConfigurationSessionTestSuite appAdminSessionTest = new ConfigurationSessionTestSuite(PI_OSGI_TESTS, ApplicationAdminTest.class.getName());
		String[] ids = ConfigurationSessionTestSuite.MINIMAL_BUNDLE_SET;
		for (int i = 0; i < ids.length; i++)
			appAdminSessionTest.addBundle(ids[i]);
		appAdminSessionTest.addBundle(PI_OSGI_SERVICES);
		appAdminSessionTest.addBundle(PI_OSGI_TESTS);
		appAdminSessionTest.setApplicationId(testRunnerApp);
		try {
			appAdminSessionTest.getSetup().setSystemProperty("eclipse.application.registerDescriptors", "true"); //$NON-NLS-1$//$NON-NLS-2$
		} catch (SetupException e) {
			throw new RuntimeException(e);
		}
		// we add tests the hard way so we can control the order of the tests.
		for (int i = 0; i < tests.length; i++)
			appAdminSessionTest.addTest(new ApplicationAdminTest(tests[i]));
		suite.addTest(appAdminSessionTest);
		return suite;
	}

	public ApplicationAdminTest(String name) {
		super(name);
	}

	private ApplicationDescriptor getApplication(String appName) {
		try {
			ServiceReference[] refs = getContext().getServiceReferences(ApplicationDescriptor.class.getName(), "(" + ApplicationDescriptor.APPLICATION_PID + "=" + appName + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (refs == null || refs.length == 0) {
				refs = getContext().getServiceReferences(ApplicationDescriptor.class.getName(), null);
				String availableApps = ""; //$NON-NLS-1$
				if (refs != null) {
					for (int i = 0; i < refs.length; i++) {
						availableApps += refs[i].getProperty(ApplicationDescriptor.APPLICATION_PID);
						if (i < refs.length - 1)
							availableApps += ","; //$NON-NLS-1$
					}
				}
				fail("Could not find app pid: " + appName + " available apps are: " + availableApps); //$NON-NLS-1$ //$NON-NLS-2$
			}
			ApplicationDescriptor result = (ApplicationDescriptor) getContext().getService(refs[0]);
			if (result != null)
				getContext().ungetService(refs[0]);
			else
				fail("Could not get application descriptor service: " + appName); //$NON-NLS-1$
			return result;
		} catch (InvalidSyntaxException e) {
			fail("Could not create app filter", e); //$NON-NLS-1$
		}
		return null;
	}

	private HashMap getArguments() {
		HashMap args = new HashMap();
		args.put(testResults, new HashMap());
		return args;
	}

	public void testSimpleApp() {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".simpleApp"); //$NON-NLS-1$
		HashMap args = getArguments();
		HashMap results = (HashMap) args.get(testResults);
		try {
			ApplicationHandle handle = app.launch(args);
			handle.destroy();
		} catch (Throwable e) {
			fail("failed to launch simpleApp", e); //$NON-NLS-1$
		}
		String result = (String) results.get(simpleResults);
		assertEquals("Check application result", SUCCESS, result); //$NON-NLS-1$
	}

	public void testGlobalSingleton() {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".globalSingletonApp"); //$NON-NLS-1$
		HashMap args = getArguments();
		try {
			ApplicationHandle handle = app.launch(args);
			handle.destroy();
			fail("should not be able to launch a global singleton app: " + app.getApplicationId()); //$NON-NLS-1$
		} catch (ApplicationException e) {
			assertEquals("check error code", ApplicationException.APPLICATION_NOT_LAUNCHABLE, e.getErrorCode()); //$NON-NLS-1$
		}
	}

	private void doTestCardinality01(String appID, int cardinality, boolean hasMax) {
		ApplicationDescriptor app = getApplication(appID);
		ArrayList instances = new ArrayList();
		int i = 0;
		try {
			for (; i <= cardinality; i++)
				instances.add(app.launch(null));
		} catch (ApplicationException e) {
			if (!hasMax || i != cardinality)
				fail("Unexpected ApplicationException", e); //$NON-NLS-1$
			assertEquals("check error code", ApplicationException.APPLICATION_NOT_LAUNCHABLE, e.getErrorCode()); //$NON-NLS-1$
		} finally {
			for (Iterator handles = instances.iterator(); handles.hasNext();) {
				((ApplicationHandle) handles.next()).destroy();
			}
		}
		assertEquals("Did not launch the correct # of concurrent instances", instances.size(), cardinality + (hasMax ? 0 : 1)); //$NON-NLS-1$
	}

	public void testCardinality01() {
		doTestCardinality01(PI_OSGI_TESTS + ".testCardinality01", 10, true); //$NON-NLS-1$
	}

	public void testCardinality02() {
		doTestCardinality01(PI_OSGI_TESTS + ".testCardinality02", 20, false); //$NON-NLS-1$
	}

	private void doTestMainThreaded01(String appID) {
		ApplicationDescriptor app = getApplication(appID);
		ArrayList instances = new ArrayList();
		try {
			instances.add(app.launch(null));
			instances.add(app.launch(null));
		} catch (ApplicationException e) {
			if (instances.size() == 0)
				fail("Unable to launch a main threaded application"); //$NON-NLS-1$
			assertEquals("check error code", ApplicationException.APPLICATION_NOT_LAUNCHABLE, e.getErrorCode()); //$NON-NLS-1$
		} finally {
			for (Iterator handles = instances.iterator(); handles.hasNext();) {
				((ApplicationHandle) handles.next()).destroy();
			}
		}
		assertEquals("Did not launch the correct # of main app instances", instances.size(), 1); //$NON-NLS-1$
	}

	public void testMainThreaded01() {
		doTestMainThreaded01(PI_OSGI_TESTS + ".testMainThreaded01"); //$NON-NLS-1$
	}

	public void testMainThreaded02() {
		doTestMainThreaded01(PI_OSGI_TESTS + ".testMainThreaded02"); //$NON-NLS-1$
	}

	public void testHandleEvents01() throws InvalidSyntaxException {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".simpleApp"); //$NON-NLS-1$
		ApplicationHandleTracker handleTracker = new ApplicationHandleTracker(getContext());
		ServiceTracker tracker = new ServiceTracker(getContext(), FrameworkUtil.createFilter("(&(objectClass=" + ApplicationHandle.class.getName() + ")(" + ApplicationHandle.APPLICATION_DESCRIPTOR + "=" + app.getApplicationId() + "))"), handleTracker); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		tracker.open();
		try {
			ApplicationHandle handle = app.launch(null);
			handleTracker.waitForEvent(handle.getInstanceId(), ApplicationHandle.RUNNING);
			handle.destroy();
			handleTracker.waitForEvent(handle.getInstanceId(), REMOVED);
			String[][] events = handleTracker.getEvents();
			String[][] expected = new String[][] {new String[] {handle.getInstanceId(), "org.eclipse.equinox.app.starting"}, new String[] {handle.getInstanceId(), ApplicationHandle.RUNNING}, new String[] {handle.getInstanceId(), ApplicationHandle.STOPPING}, new String[] {handle.getInstanceId(), "org.eclipse.equinox.app.stopped"}, new String[] {handle.getInstanceId(), "removed"}}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			assertEquals("Check expected # events", expected.length, events.length); //$NON-NLS-1$
			for (int i = 0; i < events.length; i++) {
				assertEquals("Check expected event id for #" + i, expected[i][0], events[i][0]); //$NON-NLS-1$
				assertEquals("Check expected event state for #" + i, expected[i][1], events[i][1]); //$NON-NLS-1$
			}
		} catch (ApplicationException e) {
			fail("failed to launch application", e); //$NON-NLS-1$
		} finally {
			tracker.close();
		}
	}

	public void testDescriptorEvents01() throws InvalidSyntaxException, BundleException {
		BundleInstaller bundleInstaller = null;
		try {
			bundleInstaller = new BundleInstaller(OSGiTestsActivator.TEST_FILES_ROOT + "internal/plugins/appAdminTests", getContext()); //$NON-NLS-1$
		} catch (InvalidSyntaxException e) {
			fail("Failed to create bundle installer", e); //$NON-NLS-1$
		}
		String testAppPID = "appadmin.test01.simpleApp"; //$NON-NLS-1$
		ApplicationDescriptorTracker descriptionTracker = new ApplicationDescriptorTracker(getContext());
		ServiceTracker tracker = new ServiceTracker(getContext(), FrameworkUtil.createFilter("(&(objectClass=" + ApplicationDescriptor.class.getName() + ")(" + ApplicationDescriptor.APPLICATION_PID + "=" + testAppPID + "))"), descriptionTracker); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		tracker.open();
		try {
			Bundle test01 = bundleInstaller.installBundle("appadmin.test01"); //$NON-NLS-1$
			assertTrue("Check if test bundle is resolved: " + test01.getSymbolicName(), bundleInstaller.resolveBundles(new Bundle[] {test01})); //$NON-NLS-1$
			descriptionTracker.waitForEvent(testAppPID, ADDED, Boolean.FALSE, Boolean.TRUE);
			ApplicationDescriptor app = getApplication(testAppPID);
			app.lock();
			descriptionTracker.waitForEvent(testAppPID, MODIFIED, Boolean.TRUE, Boolean.TRUE);
			app.unlock();
			descriptionTracker.waitForEvent(testAppPID, MODIFIED, Boolean.FALSE, Boolean.TRUE);
			ApplicationHandle handle = null;
			try {
				handle = app.launch(null);
			} catch (ApplicationException e) {
				fail("failed to launch app: " + app.getApplicationId(), e); //$NON-NLS-1$
			}
			descriptionTracker.waitForEvent(testAppPID, MODIFIED, Boolean.FALSE, Boolean.FALSE);
			handle.destroy();
			descriptionTracker.waitForEvent(testAppPID, MODIFIED, Boolean.FALSE, Boolean.TRUE);
			test01.uninstall();
			descriptionTracker.waitForEvent(testAppPID, REMOVED, Boolean.FALSE, Boolean.TRUE);
		} finally {
			bundleInstaller.shutdown();
			tracker.close();
		}
	}

	public void testPersistentLock01() {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".simpleApp"); //$NON-NLS-1$
		app.lock();
	}

	public void testPersistentLock02() {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".simpleApp"); //$NON-NLS-1$
		try {
			ApplicationHandle handle = app.launch(null);
			handle.destroy();
			fail("The application should be locked: " + app.getApplicationId()); //$NON-NLS-1$
		} catch (ApplicationException e) {
			assertEquals("check error code", ApplicationException.APPLICATION_LOCKED, e.getErrorCode()); //$NON-NLS-1$
		}
		app.unlock();
		try {
			ApplicationHandle handle = app.launch(null);
			handle.destroy();
		} catch (Throwable e) {
			fail("failed to launch simpleApp", e); //$NON-NLS-1$
		}
	}

	public void testPersistentLock03() {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".simpleApp"); //$NON-NLS-1$
		try {
			ApplicationHandle handle = app.launch(null);
			handle.destroy();
		} catch (Throwable e) {
			fail("failed to launch simpleApp", e); //$NON-NLS-1$
		}
	}

	public void testPersistentSchedule01() {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".simpleApp"); //$NON-NLS-1$
		try {
			HashMap args = new HashMap();
			args.put("test.arg1", Boolean.TRUE); //$NON-NLS-1$
			args.put("test.arg2", new Integer(34)); //$NON-NLS-1$
			args.put("test.arg3", new Long(34)); //$NON-NLS-1$
			app.schedule("schedule.1", args, "org/osgi/application/timer", "(minute=*)", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		} catch (InvalidSyntaxException e) {
			fail("Failed to schedule an application", e); //$NON-NLS-1$
		} catch (ApplicationException e) {
			fail("Failed to schedule an application", e); //$NON-NLS-1$
		}
	}

	public void testPersistentSchedule02() throws InvalidSyntaxException {
		ScheduledApplication scheduledApp = getScheduleApplication("schedule.1", true); //$NON-NLS-1$
		ApplicationHandleTracker handleTracker = new ApplicationHandleTracker(getContext());
		ServiceTracker tracker = new ServiceTracker(getContext(), FrameworkUtil.createFilter("(&(objectClass=" + ApplicationHandle.class.getName() + ")(" + ApplicationHandle.APPLICATION_DESCRIPTOR + "=" + scheduledApp.getApplicationDescriptor().getApplicationId() + "))"), handleTracker); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		tracker.open();
		try {
			ApplicationHandle handle = (ApplicationHandle) tracker.waitForService(61000);
			assertNotNull("Should find an application handle", handle); //$NON-NLS-1$
			handle.destroy();
			HashMap results = (HashMap) ((EclipseAppHandle) handle).waitForResult(1000);
			assertNotNull("Null results", results); //$NON-NLS-1$
			HashMap args = new HashMap();
			args.put("test.arg1", Boolean.TRUE); //$NON-NLS-1$
			args.put("test.arg2", new Integer(34)); //$NON-NLS-1$
			args.put("test.arg3", new Long(34)); //$NON-NLS-1$
			for (Iterator iEntries = args.entrySet().iterator(); iEntries.hasNext();) {
				Entry entry = (Entry) iEntries.next();
				assertEquals("key: " + entry.getKey(), entry.getValue(), results.get(entry.getKey())); //$NON-NLS-1$
			}
		} catch (InterruptedException e) {
			fail("got interupted", e); //$NON-NLS-1$
		} finally {
			tracker.close();
			scheduledApp.remove();
		}
	}

	public void testPersistentSchedule03() {
		ScheduledApplication scheduledApp = getScheduleApplication("schedule.1", false); //$NON-NLS-1$
		if (scheduledApp != null) {
			scheduledApp.remove();
			fail("Scheduled application should not be found: " + scheduledApp.getScheduleId()); //$NON-NLS-1$
		}
	}

	public void testPersistentSchedule04() {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".simpleApp"); //$NON-NLS-1$
		try {
			// lock the app so that it cannot be launched with a scheduled
			app.lock();
			HashMap args = new HashMap();
			args.put("test.arg1", Boolean.TRUE); //$NON-NLS-1$
			args.put("test.arg2", new Integer(34)); //$NON-NLS-1$
			args.put("test.arg3", new Long(34)); //$NON-NLS-1$
			// make it non-recurring
			app.schedule("schedule.2", args, "org/osgi/application/timer", "(minute=*)", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		} catch (InvalidSyntaxException e) {
			fail("Failed to schedule an application", e); //$NON-NLS-1$
		} catch (ApplicationException e) {
			fail("Failed to schedule an application", e); //$NON-NLS-1$
		}
	}

	public void testPersistentSchedule05() throws InvalidSyntaxException {
		ScheduledApplication scheduledApp = getScheduleApplication("schedule.2", true); //$NON-NLS-1$
		ApplicationHandleTracker handleTracker = new ApplicationHandleTracker(getContext());
		ServiceTracker tracker = new ServiceTracker(getContext(), FrameworkUtil.createFilter("(&(objectClass=" + ApplicationHandle.class.getName() + ")(" + ApplicationHandle.APPLICATION_DESCRIPTOR + "=" + scheduledApp.getApplicationDescriptor().getApplicationId() + "))"), handleTracker); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		tracker.open();
		ApplicationHandle badHandle = null;
		try {
			badHandle = (ApplicationHandle) tracker.waitForService(61000);
			assertNull("Should not find an application handle", badHandle); //$NON-NLS-1$
			// unlock app then try again
			scheduledApp.getApplicationDescriptor().unlock();
			ApplicationHandle handle = (ApplicationHandle) tracker.waitForService(61000);
			assertNotNull("Should find an application handle", handle); //$NON-NLS-1$
			handle.destroy();
			HashMap results = (HashMap) ((EclipseAppHandle) handle).waitForResult(1000);
			assertNotNull("Null results", results); //$NON-NLS-1$
			HashMap args = new HashMap();
			args.put("test.arg1", Boolean.TRUE); //$NON-NLS-1$
			args.put("test.arg2", new Integer(34)); //$NON-NLS-1$
			args.put("test.arg3", new Long(34)); //$NON-NLS-1$
			for (Iterator iEntries = args.entrySet().iterator(); iEntries.hasNext();) {
				Entry entry = (Entry) iEntries.next();
				assertEquals("key: " + entry.getKey(), entry.getValue(), results.get(entry.getKey())); //$NON-NLS-1$
			}
			// should not find the scheduled app anymore
			// sleeping here to allow for the scheduled app to disappear; could use service events instead
			Thread.sleep(1000);
			scheduledApp = getScheduleApplication("schedule.2", false); //$NON-NLS-1$
			if (scheduledApp != null) {
				fail("Scheduled application should not be found: " + scheduledApp.getScheduleId()); //$NON-NLS-1$
			}
		} catch (InterruptedException e) {
			fail("got interupted", e); //$NON-NLS-1$
		} finally {
			if (badHandle != null)
				badHandle.destroy();
			tracker.close();
			if (scheduledApp != null)
				scheduledApp.remove();
		}
	}

	public void testPersistentSchedule06() {
		ScheduledApplication scheduledApp = getScheduleApplication("schedule.2", false); //$NON-NLS-1$
		if (scheduledApp != null) {
			scheduledApp.remove();
			fail("Scheduled application should not be found: " + scheduledApp.getScheduleId()); //$NON-NLS-1$
		}
	}

	public void testPersistentSchedule07() {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".simpleApp"); //$NON-NLS-1$
		try {
			app.schedule("schedule.duplicate1", null, "org/osgi/application/timer", "(minute=*)", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		} catch (InvalidSyntaxException e) {
			fail("Failed to schedule an application", e); //$NON-NLS-1$
		} catch (ApplicationException e) {
			fail("Failed to schedule an application", e); //$NON-NLS-1$
		}
		try {
			app.schedule("schedule.duplicate1", null, "org/osgi/application/timer", "(minute=*)", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			fail("should not be able to create a duplicate scheduled application id"); //$NON-NLS-1$
		} catch (InvalidSyntaxException e) {
			fail("Failed to schedule an application", e); //$NON-NLS-1$
		} catch (ApplicationException e) {
			assertEquals("check error code", ApplicationException.APPLICATION_DUPLICATE_SCHEDULE_ID, e.getErrorCode()); //$NON-NLS-1$
		}
	}

	public void testPersistentSchedule08() {
		ScheduledApplication scheduledApp = getScheduleApplication("schedule.duplicate1", true); //$NON-NLS-1$
		try {
			scheduledApp.getApplicationDescriptor().schedule("schedule.duplicate1", null, "org/osgi/application/timer", "(minute=*)", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			fail("should not be able to create a duplicate scheduled application id"); //$NON-NLS-1$
		} catch (InvalidSyntaxException e) {
			fail("Failed to schedule an application", e); //$NON-NLS-1$
		} catch (ApplicationException e) {
			assertEquals("check error code", ApplicationException.APPLICATION_DUPLICATE_SCHEDULE_ID, e.getErrorCode()); //$NON-NLS-1$
		} finally {
			scheduledApp.remove();
		}
	}

	public void testFailedApplication01() throws InvalidSyntaxException {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".failedApp"); //$NON-NLS-1$
		ApplicationHandleTracker handleTracker = new ApplicationHandleTracker(getContext());
		ServiceTracker tracker = new ServiceTracker(getContext(), FrameworkUtil.createFilter("(&(objectClass=" + ApplicationHandle.class.getName() + ")(" + ApplicationHandle.APPLICATION_DESCRIPTOR + "=" + app.getApplicationId() + "))"), handleTracker); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		tracker.open();
		try {
			ApplicationHandle handle = app.launch(null);
			handleTracker.waitForEvent(handle.getInstanceId(), REMOVED);
			String[][] events = handleTracker.getEvents();
			String[][] expected = new String[][] {new String[] {handle.getInstanceId(), "org.eclipse.equinox.app.starting"}, new String[] {handle.getInstanceId(), ApplicationHandle.STOPPING}, new String[] {handle.getInstanceId(), "org.eclipse.equinox.app.stopped"}, new String[] {handle.getInstanceId(), "removed"}}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			assertEquals("Check expected # events", expected.length, events.length); //$NON-NLS-1$
			for (int i = 0; i < events.length; i++) {
				assertEquals("Check expected event id for #" + i, expected[i][0], events[i][0]); //$NON-NLS-1$
				assertEquals("Check expected event state for #" + i, expected[i][1], events[i][1]); //$NON-NLS-1$
			}
		} catch (ApplicationException e) {
			fail("failed to launch application", e); //$NON-NLS-1$
		} finally {
			tracker.close();
		}
	}

	public void testDestroyBeforeStart01() throws InvalidSyntaxException {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".simpleApp"); //$NON-NLS-1$
		HashMap args = getArguments();
		HashMap results = (HashMap) args.get(testResults);
		ServiceTrackerCustomizer trackerCustomizer = new ServiceTrackerCustomizer() {
			public Object addingService(ServiceReference reference) {
				ApplicationHandle handle = (ApplicationHandle) getContext().getService(reference);
				handle.destroy();
				return handle;
			}

			public void modifiedService(ServiceReference reference, Object service) {
				// nothing
			}

			public void removedService(ServiceReference reference, Object service) {
				// nothing
			}
		};
		ServiceTracker tracker = new ServiceTracker(getContext(), FrameworkUtil.createFilter("(&(objectClass=" + ApplicationHandle.class.getName() + ")(" + ApplicationHandle.APPLICATION_DESCRIPTOR + "=" + app.getApplicationId() + "))"), trackerCustomizer); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		tracker.open();
		try {
			ApplicationHandle handle = app.launch(args);
			handle.destroy();
		} catch (Throwable e) {
			fail("failed to launch simpleApp", e); //$NON-NLS-1$
		}
		String result = (String) results.get(simpleResults);
		assertNull("Check application result", result); //$NON-NLS-1$
	}

	public void testDestroyBeforeStart02() throws InvalidSyntaxException {
		ApplicationDescriptor app = getApplication(PI_OSGI_TESTS + ".testMainThreaded01"); //$NON-NLS-1$
		HashMap args = getArguments();
		HashMap results = (HashMap) args.get(testResults);
		ServiceTrackerCustomizer trackerCustomizer = new ServiceTrackerCustomizer() {
			public Object addingService(ServiceReference reference) {
				ApplicationHandle handle = (ApplicationHandle) getContext().getService(reference);
				handle.destroy();
				return handle;
			}

			public void modifiedService(ServiceReference reference, Object service) {
				// nothing
			}

			public void removedService(ServiceReference reference, Object service) {
				// nothing
			}
		};
		ServiceTracker tracker = new ServiceTracker(getContext(), FrameworkUtil.createFilter("(&(objectClass=" + ApplicationHandle.class.getName() + ")(" + ApplicationHandle.APPLICATION_DESCRIPTOR + "=" + app.getApplicationId() + "))"), trackerCustomizer); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		tracker.open();
		try {
			ApplicationHandle handle = app.launch(args);
			handle.destroy();
		} catch (Throwable e) {
			fail("failed to launch simpleApp", e); //$NON-NLS-1$
		}
		String result = (String) results.get(simpleResults);
		assertNull("Check application result", result); //$NON-NLS-1$
	}

	private ScheduledApplication getScheduleApplication(String scheduleID, boolean failOnMissing) {
		try {
			ServiceReference[] refs = getContext().getServiceReferences(ScheduledApplication.class.getName(), "(" + ScheduledApplication.SCHEDULE_ID + "=" + scheduleID + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (refs == null || refs.length == 0) {
				if (!failOnMissing)
					return null;
				refs = getContext().getServiceReferences(ScheduledApplication.class.getName(), null);
				String availableScheds = ""; //$NON-NLS-1$
				if (refs != null) {
					for (int i = 0; i < refs.length; i++) {
						availableScheds += refs[i].getProperty(ScheduledApplication.SCHEDULE_ID);
						if (i < refs.length - 1)
							availableScheds += ","; //$NON-NLS-1$
					}
				}
				fail("Could not find schedule: " + scheduleID + " available apps are: " + availableScheds); //$NON-NLS-1$ //$NON-NLS-2$
			}
			ScheduledApplication result = (ScheduledApplication) getContext().getService(refs[0]);
			if (result != null)
				getContext().ungetService(refs[0]);
			else
				fail("Could not get scheduled application: " + scheduleID); //$NON-NLS-1$
			return result;
		} catch (InvalidSyntaxException e) {
			fail("Could not create app filter", e); //$NON-NLS-1$
		}
		return null;
	}

	public static class ApplicationHandleTracker implements ServiceTrackerCustomizer {
		private ArrayList events = new ArrayList();
		private BundleContext bc;

		public ApplicationHandleTracker(BundleContext bc) {
			this.bc = bc;
		}

		public synchronized Object addingService(ServiceReference reference) {
			String instance = (String) reference.getProperty(ApplicationHandle.APPLICATION_PID);
			String state = (String) reference.getProperty(ApplicationHandle.APPLICATION_STATE);
			events.add(new String[] {instance, state});
			Object result = bc.getService(reference);
			notifyAll();
			return result;
		}

		public synchronized void modifiedService(ServiceReference reference, Object service) {
			String instance = (String) reference.getProperty(ApplicationHandle.APPLICATION_PID);
			String state = (String) reference.getProperty(ApplicationHandle.APPLICATION_STATE);
			events.add(new String[] {instance, state});
			notifyAll();
		}

		public synchronized void removedService(ServiceReference reference, Object service) {
			String instance = (String) reference.getProperty(ApplicationHandle.APPLICATION_PID);
			events.add(new String[] {instance, REMOVED});
			notifyAll();
		}

		// Note that this method assumes you call it before the event actually gets posted.
		// It only looks at the last event that was saved.
		public synchronized void waitForEvent(String instance, String state) {
			long delay = 5000;
			long startTime = System.currentTimeMillis();
			boolean found = eventFound(instance, state);
			while (delay > 0 && !found) {
				try {
					wait(5000);
					delay -= System.currentTimeMillis() - startTime;
				} catch (InterruptedException e) {
					// do nothing
				}
				found = eventFound(instance, state);
			}
			if (!found)
				fail("failed waiting for handle event: " + instance + " " + state); //$NON-NLS-1$ //$NON-NLS-2$
		}

		public synchronized String[][] getEvents() {
			return (String[][]) events.toArray(new String[events.size()][]);
		}

		private boolean eventFound(String instance, String state) {
			if (events.size() == 0)
				return false;
			String[] event = (String[]) events.get(events.size() - 1);
			if (instance.equals(event[0]) && state.equals(event[1]))
				return true;
			return false;
		}
	}

	public static class ApplicationDescriptorTracker implements ServiceTrackerCustomizer {
		private BundleContext bc;
		private ArrayList events = new ArrayList();

		public ApplicationDescriptorTracker(BundleContext bc) {
			this.bc = bc;
		}

		public synchronized Object addingService(ServiceReference reference) {
			String pid = (String) reference.getProperty(ApplicationDescriptor.APPLICATION_PID);
			Boolean locked = (Boolean) reference.getProperty(ApplicationDescriptor.APPLICATION_LOCKED);
			Boolean launchable = (Boolean) reference.getProperty(ApplicationDescriptor.APPLICATION_LAUNCHABLE);
			events.add(new Object[] {pid, ADDED, locked, launchable});
			Object result = bc.getService(reference);
			notifyAll();
			return result;
		}

		public synchronized void modifiedService(ServiceReference reference, Object service) {
			String pid = (String) reference.getProperty(ApplicationDescriptor.APPLICATION_PID);
			Boolean locked = (Boolean) reference.getProperty(ApplicationDescriptor.APPLICATION_LOCKED);
			Boolean launchable = (Boolean) reference.getProperty(ApplicationDescriptor.APPLICATION_LAUNCHABLE);
			events.add(new Object[] {pid, MODIFIED, locked, launchable});
			notifyAll();
		}

		public synchronized void removedService(ServiceReference reference, Object service) {
			String pid = (String) reference.getProperty(ApplicationDescriptor.APPLICATION_PID);
			Boolean locked = (Boolean) reference.getProperty(ApplicationDescriptor.APPLICATION_LOCKED);
			Boolean launchable = (Boolean) reference.getProperty(ApplicationDescriptor.APPLICATION_LAUNCHABLE);
			events.add(new Object[] {pid, REMOVED, locked, launchable});
			notifyAll();
		}

		// Note that this method assumes you call it before the event actually gets posted.
		// It only looks at the last event that was saved.
		public synchronized void waitForEvent(String pid, String type, Boolean locked, Boolean launchable) {
			long delay = 5000;
			long startTime = System.currentTimeMillis();
			boolean found = eventFound(pid, type, locked, launchable);
			while (delay > 0 && !found) {
				try {
					wait(5000);
					delay -= System.currentTimeMillis() - startTime;
				} catch (InterruptedException e) {
					// do nothing
				}
				found = eventFound(pid, type, locked, launchable);
			}
			if (!found)
				fail("failed waiting for descriptor event: " + pid + " " + type + " " + locked + " " + launchable); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}

		public synchronized Object[][] getEvents() {
			return (Object[][]) events.toArray(new Object[events.size()][]);
		}

		private boolean eventFound(String pid, String type, Boolean locked, Boolean launchable) {
			if (events.size() == 0)
				return false;
			Object[] event = (Object[]) events.get(events.size() - 1);
			if (pid.equals(event[0]) && type.equals(event[1]) && locked.equals(event[2]) && launchable.equals(event[3]))
				return true;
			return false;
		}
	}
}
