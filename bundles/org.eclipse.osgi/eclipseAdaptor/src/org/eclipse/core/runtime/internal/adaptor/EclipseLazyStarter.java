/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.core.runtime.internal.adaptor;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.security.AccessController;
import java.util.*;
import org.eclipse.osgi.baseadaptor.*;
import org.eclipse.osgi.baseadaptor.bundlefile.BundleEntry;
import org.eclipse.osgi.baseadaptor.hooks.AdaptorHook;
import org.eclipse.osgi.baseadaptor.hooks.ClassLoadingStatsHook;
import org.eclipse.osgi.baseadaptor.loader.ClasspathEntry;
import org.eclipse.osgi.baseadaptor.loader.ClasspathManager;
import org.eclipse.osgi.framework.adaptor.FrameworkAdaptor;
import org.eclipse.osgi.framework.adaptor.StatusException;
import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.framework.internal.core.*;
import org.eclipse.osgi.framework.log.FrameworkLog;
import org.eclipse.osgi.framework.log.FrameworkLogEntry;
import org.eclipse.osgi.framework.util.SecureAction;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.StateHelper;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.*;
import org.osgi.service.startlevel.StartLevel;

public class EclipseLazyStarter implements ClassLoadingStatsHook, AdaptorHook, HookConfigurator {
	private static final boolean throwErrorOnFailedStart = "true".equals(FrameworkProperties.getProperty("osgi.compatibility.errorOnFailedStart", "true"));  //$NON-NLS-1$//$NON-NLS-2$ //$NON-NLS-3$
	private static final SecureAction secureAction = (SecureAction) AccessController.doPrivileged(SecureAction.createSecureAction());
	private StartLevelImpl startLevelService;
	private ServiceReference slRef;
	private BaseAdaptor adaptor;
	// holds the current activation trigger class and the ClasspathManagers that need to be activated
	private ThreadLocal activationStack = new ThreadLocal();
	// used to store exceptions that occurred while activating a bundle
	// keyed by ClasspathManager->Exception
	// WeakHashMap is used to prevent pinning the ClasspathManager objects.
	private final Map errors = Collections.synchronizedMap(new WeakHashMap());

	public void preFindLocalClass(String name, ClasspathManager manager) throws ClassNotFoundException {
		AbstractBundle bundle = (AbstractBundle) manager.getBaseData().getBundle();
		// If the bundle is active, uninstalled or stopping then the bundle has already
		// been initialized (though it may have been destroyed) so just return the class.
		if ((bundle.getState() & (Bundle.ACTIVE | Bundle.UNINSTALLED | Bundle.STOPPING)) != 0)
			return;
		EclipseStorageHook storageHook = (EclipseStorageHook) manager.getBaseData().getStorageHook(EclipseStorageHook.KEY);
		// The bundle is not active and does not require activation, just return the class
		if (!shouldActivateFor(name, manager.getBaseData(), storageHook, manager))
			return;
		ArrayList stack = (ArrayList) activationStack.get();
		if (stack == null) {
			stack = new ArrayList(6);
			activationStack.set(stack);
		}
		// the first element in the stack is the name of the trigger class, 
		// each element after the trigger class is a classpath manager 
		// that must be activated after the trigger class has been defined (see postFindLocalClass)
		int size = stack.size();
		if (size > 1) {
			for (int i = size -1; i >= 1; i--)
				if (manager == stack.get(i))
					// the manager is already on the stack in which case we are already in the process of loading the trigger class
					return;
		}
		Thread threadChangingState = bundle.getStateChanging();
		if (bundle.getState() == Bundle.STARTING && threadChangingState == Thread.currentThread())
			return; // this thread is starting the bundle already
		if (size == 0)
			stack.add(name);
		stack.add(manager);
	}

	public void postFindLocalClass(String name, Class clazz, ClasspathManager manager) throws ClassNotFoundException {
		ArrayList stack = (ArrayList) activationStack.get();
		if (stack == null)
			return;
		int size = stack.size();
		if (size <= 1 || stack.get(0) != name)
			return;
		// if we have a stack we must clear it even if (clazz == null)
		ClasspathManager[] managers = null;
		managers = new ClasspathManager[size - 1];
		for (int i = 1; i < size; i++)
			managers[i - 1] = (ClasspathManager) stack.get(i);
		stack.clear();
		if (clazz == null)
			return;
		for (int i = managers.length - 1; i >= 0; i--) {
			if (errors.get(managers[i]) != null) {
				if (throwErrorOnFailedStart)
					throw (TerminatingClassNotFoundException) errors.get(managers[i]);
				continue;
			}
			AbstractBundle bundle = (AbstractBundle) managers[i].getBaseData().getBundle();
			// The bundle must be started.
			// Note that another thread may already be starting this bundle;
			// In this case we will timeout after 5 seconds and record the BundleException
			try {
				// do not persist the start of this bundle
				secureAction.start(bundle, Bundle.START_TRANSIENT);
			} catch (BundleException e) {
				Throwable cause = e.getCause();
				if (cause != null && cause instanceof StatusException) {
					StatusException status = (StatusException) cause;
					if ((status.getStatusCode() & StatusException.CODE_ERROR) == 0) {
						if (status.getStatus() instanceof Thread) {
							String message = NLS.bind(EclipseAdaptorMsg.ECLIPSE_CLASSLOADER_CONCURRENT_STARTUP, new Object[] {Thread.currentThread(), name, status.getStatus(), bundle, new Integer(5000)});
							adaptor.getFrameworkLog().log(new FrameworkLogEntry(FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME, FrameworkLogEntry.WARNING, 0, message, 0, e, null));
						}			
						continue;
					}
				}
				String message = NLS.bind(EclipseAdaptorMsg.ECLIPSE_CLASSLOADER_ACTIVATION, bundle.getSymbolicName(), Long.toString(bundle.getBundleId()));
				TerminatingClassNotFoundException error = new TerminatingClassNotFoundException(message, e);
				errors.put(managers[i], error);
				if (throwErrorOnFailedStart) {
					adaptor.getFrameworkLog().log(new FrameworkLogEntry(FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME, FrameworkLogEntry.ERROR, 0, message, 0, e, null));
					throw error;
				}
				adaptor.getEventPublisher().publishFrameworkEvent(FrameworkEvent.ERROR, bundle, new BundleException(message, e));
			}
		}
	}

	public void preFindLocalResource(String name, ClasspathManager manager) {
		// do nothing
	}

	public void postFindLocalResource(String name, URL resource, ClasspathManager manager) {
		// do nothing
	}

	public void recordClassDefine(String name, Class clazz, byte[] classbytes, ClasspathEntry classpathEntry, BundleEntry entry, ClasspathManager manager) {
		// do nothing
	}

	private boolean shouldActivateFor(String className, BaseData bundledata, EclipseStorageHook storageHook, ClasspathManager manager) throws ClassNotFoundException {
		if (!isLazyStartable(className, bundledata, storageHook))
			return false;
		// Don't activate non-starting bundles
		if (bundledata.getBundle().getState() == Bundle.RESOLVED) {
			if (throwErrorOnFailedStart) {
				TerminatingClassNotFoundException error = (TerminatingClassNotFoundException) errors.get(manager);
				if (error != null)
					throw error;
			}
			StartLevelImpl sl = startLevelService;
			return sl != null && ((sl.getStartLevel() == bundledata.getStartLevel() && sl.isSettingStartLevel()));
		}
		return true;
	}

	private boolean isLazyStartable(String className, BaseData bundledata, EclipseStorageHook storageHook) {
		if (storageHook == null)
			return false;
		boolean lazyStart = storageHook.isLazyStart();
		String[] excludes = storageHook.getLazyStartExcludes();
		String[] includes = storageHook.getLazyStartIncludes();
		// no exceptions, it is easy to figure it out
		if (excludes == null && includes == null)
			return lazyStart;
		// otherwise, we need to check if the package is in the exceptions list
		int dotPosition = className.lastIndexOf('.');
		// the class has no package name... no exceptions apply
		if (dotPosition == -1)
			return lazyStart;
		String packageName = className.substring(0, dotPosition);
		if (lazyStart)
			return ((includes == null || contains(includes, packageName)) && (excludes == null || !contains(excludes, packageName)));
		return (excludes != null && contains(excludes, packageName));
	}

	private boolean contains(String[] array, String element) {
		for (int i = 0; i < array.length; i++)
			if (array[i].equals(element))
				return true;
		return false;
	}

	public void addHooks(HookRegistry hookRegistry) {
		hookRegistry.addClassLoadingStatsHook(this);
		hookRegistry.addAdaptorHook(this);
	}

	public void addProperties(Properties properties) {
		// do nothing
	}

	public FrameworkLog createFrameworkLog() {
		// do nothing
		return null;
	}

	public void frameworkStart(BundleContext context) throws BundleException {
		slRef = context.getServiceReference(StartLevel.class.getName());
		if (slRef != null)
			startLevelService = (StartLevelImpl) context.getService(slRef);
	}

	public void frameworkStop(BundleContext context) throws BundleException {
		if (slRef != null) {
			context.ungetService(slRef);
			startLevelService = null;
		}
	}

	public void frameworkStopping(BundleContext context) {
		if (!Debug.DEBUG || !Debug.DEBUG_ENABLED)
			return;

		BundleDescription[] allBundles = adaptor.getState().getResolvedBundles();
		StateHelper stateHelper = adaptor.getPlatformAdmin().getStateHelper();
		Object[][] cycles = stateHelper.sortBundles(allBundles);
		logCycles(cycles);
	}

	public void handleRuntimeError(Throwable error) {
		// do nothing

	}

	public void initialize(BaseAdaptor baseAdaptor) {
		this.adaptor = baseAdaptor;
	}

	public URLConnection mapLocationToURLConnection(String location) throws IOException {
		// do nothing
		return null;
	}

	public boolean matchDNChain(String pattern, String[] dnChain) {
		// do nothing
		return false;
	}

	private void logCycles(Object[][] cycles) {
		// log cycles
		if (cycles.length > 0) {
			StringBuffer cycleText = new StringBuffer("["); //$NON-NLS-1$			
			for (int i = 0; i < cycles.length; i++) {
				cycleText.append('[');
				for (int j = 0; j < cycles[i].length; j++) {
					cycleText.append(((BundleDescription) cycles[i][j]).getSymbolicName());
					cycleText.append(',');
				}
				cycleText.insert(cycleText.length() - 1, ']');
			}
			cycleText.setCharAt(cycleText.length() - 1, ']');
			String message = NLS.bind(EclipseAdaptorMsg.ECLIPSE_BUNDLESTOPPER_CYCLES_FOUND, cycleText);
			FrameworkLogEntry entry = new FrameworkLogEntry(FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME, FrameworkLogEntry.WARNING, 0, message, 0, null, null);
			adaptor.getFrameworkLog().log(entry);
		}
	}

	private static class TerminatingClassNotFoundException extends ClassNotFoundException implements StatusException {
		private static final long serialVersionUID = -6730732895632169173L;
		private Object cause;
		public TerminatingClassNotFoundException(String message, Throwable cause) {
			super(message, cause);
			this.cause = cause;
		}

		public Object getStatus() {
			return cause;
		}

		public int getStatusCode() {
			return StatusException.CODE_ERROR;
		}
		
	}
}
