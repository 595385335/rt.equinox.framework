/*******************************************************************************
 * Copyright (c) 2003,2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.runtime.adaptor;

import java.io.*;
import java.lang.reflect.Constructor;
import java.net.*;
import java.util.*;
import org.eclipse.osgi.framework.adaptor.FrameworkAdaptor;
import org.eclipse.osgi.framework.internal.core.OSGi;
import org.eclipse.osgi.framework.log.FrameworkLog;
import org.eclipse.osgi.framework.log.FrameworkLogEntry;
import org.eclipse.osgi.framework.tracker.ServiceTracker;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.osgi.service.resolver.*;
import org.eclipse.osgi.service.runnable.ParameterizedRunnable;
import org.osgi.framework.*;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.service.startlevel.StartLevel;

/**
 * Special startup class for the Eclipse Platform. This class cannot be 
 * instantiated; all functionality is provided by static methods. 
 * <p>
 * The Eclipse Platform makes heavy use of Java class loaders for loading 
 * plug-ins. Even the Eclispe Runtime itself and the OSGi framework need
 * to be loaded by special class loaders. The upshot is that a 
 * client program (such as a Java main program, a servlet) cannot directly 
 * reference any part of Eclipse directly. Instead, a client must use this 
 * loader class to start the platform, invoking functionality defined 
 * in plug-ins, and shutting down the platform when done. 
 * </p>
 * @since 3.0
 */
public class EclipseStarter {
	private static FrameworkAdaptor adaptor;
	private static BundleContext context;
	private static ServiceTracker applicationTracker;
	private static boolean initialize = false;
	public static boolean debug = false;
	private static boolean running = false;

	// command line arguments
	private static final String CONSOLE = "-console"; //$NON-NLS-1$
	private static final String CONSOLE_LOG = "-consoleLog"; //$NON-NLS-1$
	private static final String DEBUG = "-debug"; //$NON-NLS-1$
	private static final String INITIALIZE = "-initialize"; //$NON-NLS-1$
	private static final String DEV = "-dev"; //$NON-NLS-1$
	private static final String WS = "-ws"; //$NON-NLS-1$
	private static final String OS = "-os"; //$NON-NLS-1$
	private static final String ARCH = "-arch"; //$NON-NLS-1$
	private static final String NL = "-nl"; //$NON-NLS-1$	

	private static final String CONFIGURATION = "-configuration"; //$NON-NLS-1$	
	private static final String USER = "-user"; //$NON-NLS-1$	
	// this is more of an Eclipse argument but this OSGi implementation stores its 
	// metadata alongside Eclipse's.
	private static final String DATA = "-data"; //$NON-NLS-1$
	
	// System properties
	public static final String PROP_DEBUG = "osgi.debug"; //$NON-NLS-1$
	public static final String PROP_DEV = "osgi.dev"; //$NON-NLS-1$
	public static final String PROP_CONSOLE = "osgi.console"; //$NON-NLS-1$
	public static final String PROP_CONSOLE_CLASS= "osgi.consoleClass"; //$NON-NLS-1$
	public static final String PROP_OS = "osgi.os"; //$NON-NLS-1$
	public static final String PROP_WS = "osgi.ws"; //$NON-NLS-1$
	public static final String PROP_NL = "osgi.nl"; //$NON-NLS-1$
	public static final String PROP_ARCH = "osgi.arch"; //$NON-NLS-1$
	public static final String PROP_ADAPTOR = "osgi.adaptor"; //$NON-NLS-1$
	public static final String PROP_SYSPATH= "osgi.syspath"; //$NON-NLS-1$
	
	public static final String PROP_EXITCODE = "eclipse.exitcode"; //$NON-NLS-1$
	public static final String PROP_CONSOLE_LOG = "eclipse.consoleLog"; //$NON-NLS-1$
	private static final String PROP_VM = "eclipse.vm"; //$NON-NLS-1$
	private static final String PROP_VMARGS = "eclipse.vmargs"; //$NON-NLS-1$
	private static final String PROP_COMMANDS = "eclipse.commands"; //$NON-NLS-1$

	/** string containing the classname of the adaptor to be used in this framework instance */
	protected static final String DEFAULT_ADAPTOR_CLASS = "org.eclipse.core.runtime.adaptor.EclipseAdaptor";
	
	// Console information
	protected static final String DEFAULT_CONSOLE_CLASS = "org.eclipse.osgi.framework.internal.core.FrameworkConsole";
	private static final String CONSOLE_NAME = "OSGi Console";

	/**
	 * Launches the platform and runs a single application. The application is either identified
	 * in the given arguments (e.g., -application &ltapp id&gt) or in the <code>eclipse.application</code>
	 * System property.  This convenience method starts 
	 * up the platform, runs the indicated application, and then shuts down the 
	 * platform. The platform must not be running already. 
	 * 
	 * @param args the command line-style arguments used to configure the platform
	 * @param endSplashHandler the block of code to run to tear down the splash 
	 * 	screen or <code>null</code> if no tear down is required
	 * @return the result of running the application
	 * @throws Exception if anything goes wrong
	 */
	public static Object run(String[] args, Runnable endSplashHandler) throws Exception {
		if (running)
			throw new IllegalStateException("Platform already running");  
		try {
			startup(args, endSplashHandler);
			return run(null);
		} finally {
			shutdown();
		}
	}
	
	/**
	 * Returns true if the platform is already running, false otherwise.
	 * @return whether or not the platform is already running
	 */
	public static boolean isRunning() {
		return running;
	}
	/**
	 * Starts the platform and sets it up to run a single application. The application is either identified
	 * in the given arguments (e.g., -application &ltapp id&gt) or in the <code>eclipse.application</code>
	 * System property.  The platform must not be running already. 
	 * <p>
	 * The given runnable (if not <code>null</code>) is used to tear down the splash screen if required.
	 * </p>
	 * @param argument the argument passed to the application
	 * @return the result of running the application
	 * @throws Exception if anything goes wrong
	 */
	public static void startup(String[] args, Runnable endSplashHandler) throws Exception {
		if (running)
			throw new IllegalStateException("Platform is already running");
		processCommandLine(args);
		LocationManager.initializeLocations();
		loadConfigurationInfo();
		loadDefaultProperties();
		adaptor = createAdaptor();
		OSGi osgi = new OSGi(adaptor);
		osgi.launch();
		String console = System.getProperty(PROP_CONSOLE);
		if (console != null)
			startConsole(osgi, new String[0], console);
		context = osgi.getBundleContext();
		publishSplashScreen(endSplashHandler);
		Bundle[] basicBundles = loadBasicBundles();
		setStartLevel(6);
		// they should all be active by this time
		ensureBundlesActive(basicBundles);
		running = true;
	}

	/**
	 * Runs the applicaiton for which the platform was started. The platform 
	 * must be running. 
	 * <p>
	 * The given argument is passed to the application being run.  If it is <code>null</code>
	 * then the command line arguments used in starting the platform, and not consumed
	 * by the platform code, are passed to the application as a <code>String[]</code>.
	 * </p>
	 * @param argument the argument passed to the application. May be <code>null</code>
	 * @return the result of running the application
	 * @throws Exception if anything goes wrong
	 */
	public static Object run(Object argument) {
		if (!running)
			throw new IllegalStateException("Platform not running");
		logUnresolvedBundles(context.getBundles());
		// if we are just initializing, do not run the application just return.
		if (initialize)
			return new Integer(0);
		initializeApplicationTracker();
		ParameterizedRunnable application = (ParameterizedRunnable)applicationTracker.getService();
		applicationTracker.close();
		if (application == null)
			throw new IllegalStateException(EclipseAdaptorMsg.formatter.getString("ECLIPSE_STARTUP_ERROR_NO_APPLICATION"));
		return application.run(argument);
	}

	/**
	 * Shuts down the Platform. The state of the Platform is not automatically 
	 * saved before shutting down. 
	 * <p>
	 * On return, the Platform will no longer be running (but could be re-launched 
	 * with another call to startup). If relaunching, care must be taken to reinitialize
	 * any System properties which the platform uses (e.g., osgi.instance.area) as
	 * some policies in the platform do not allow resetting of such properties on 
	 * subsequent runs.
	 * </p><p>
	 * Any objects handed out by running Platform, 
	 * including Platform runnables obtained via getRunnable, will be 
	 * permanently invalid. The effects of attempting to invoke methods 
	 * on invalid objects is undefined. 
	 * </p>
	 * @throws Exception if anything goes wrong
	 */
	public static void shutdown() throws Exception {
		if (!running)
			return;
		stopSystemBundle();
	}

	private static void ensureBundlesActive(Bundle[] bundles) {
		for (int i = 0; i < bundles.length; i++) {
			if (bundles[i].getState() != Bundle.ACTIVE) {
				String message = EclipseAdaptorMsg.formatter.getString("ECLIPSE_STARTUP_ERROR_BUNDLE_NOT_ACTIVE", bundles[i]);
				throw new IllegalStateException(message);
			}			
		}
	}
	private static void logUnresolvedBundles(Bundle[] bundles) {
		State state = adaptor.getState();
		FrameworkLog logService = adaptor.getFrameworkLog();
		StateHelper stateHelper = adaptor.getPlatformAdmin().getStateHelper();
		for (int i = 0; i < bundles.length; i++)
			if (bundles[i].getState() == Bundle.INSTALLED) {
				String generalMessage = EclipseAdaptorMsg.formatter.getString("ECLIPSE_STARTUP_ERROR_BUNDLE_NOT_RESOLVED", bundles[i]);
				BundleDescription description = state.getBundle(bundles[i].getBundleId());
				// for some reason, the state does not know about that bundle
				if (description == null)
					continue;
				VersionConstraint[] unsatisfied = stateHelper.getUnsatisfiedConstraints(description);
				// the bundle wasn't resolved but none of its constraints were
				// unsatisfiable
				FrameworkLogEntry[] logChildren = unsatisfied.length == 0 ? null : new FrameworkLogEntry[unsatisfied.length];
				for (int j = 0; j < unsatisfied.length; j++)
					logChildren[j] = new FrameworkLogEntry("org.eclipse.osgi", EclipseAdaptorMsg.getResolutionFailureMessage(unsatisfied[j]), 0, null, null);
				
				logService.log(new FrameworkLogEntry("org.eclipse.osgi", generalMessage, 0, null, logChildren));
			}
	}		
	private static void publishSplashScreen(Runnable endSplashHandler) {
		// InternalPlatform now how to retrieve this later
		Dictionary properties = new Hashtable();
		properties.put("name","splashscreen");
		context.registerService(Runnable.class.getName(),endSplashHandler,properties);		
	}

	private static String searchForBundle(String name, String parent) throws MalformedURLException {
		URL url = null;
		File fileLocation = null;
		boolean reference = false;
		try {
			url = new URL(name);
		} catch (MalformedURLException e) {
			// TODO this is legacy support for non-URL names.  It should be removed eventually.
			// if name was not a URL then construct one.  
			// Assume it should be a reference and htat it is relative.  This support need not 
			// be robust as it is temporary..
			fileLocation = new File(parent, name);
			url = new URL("reference:file:"+ parent + "/" + name);
			reference = true;
		}
		// if the name was a URL then see if it is relative.  If so, insert syspath.
		if (!reference) {
			URL baseURL = url;
			// if it is a reference URL then strip off the reference: and set base to the file:...
			if (url.getProtocol().equals("reference")) {
				reference = true;
				baseURL = new URL(url.getFile());
			}
			
			fileLocation = new File(baseURL.getFile());
			// if the location is relative, prefix it with the syspath
			if (!fileLocation.isAbsolute())
				fileLocation = new File(parent, fileLocation.toString());
		}
		// If the result is a reference then search for the real result and 
		// reconstruct the answer.
		if (reference) {
			String result = searchFor(fileLocation.getName(), fileLocation.getParentFile().getAbsolutePath());
			if (result != null)
				url = new URL("reference", null, "file:" + result);
			else
				return null;
		}

		// finally we have something worth trying	
		try {
			URLConnection result = url.openConnection();
			result.connect();
			return url.toExternalForm();
		} catch (IOException e) {
//			int i = location.lastIndexOf('_');
//			return i == -1? location : location.substring(0, i);
			return null;
		}
	}
	/*
	 * Ensure all basic bundles are installed, resolved and scheduled to start. Returns an array containing
	 * all basic bundles. 
	 */
	private static Bundle[] loadBasicBundles() throws BundleException, MalformedURLException, IllegalArgumentException, IllegalStateException {
			long startTime = System.currentTimeMillis();
			ServiceReference reference = context.getServiceReference(StartLevel.class.getName());
			StartLevel start = null;
			if (reference != null)
				start = (StartLevel) context.getService(reference);
			String[] installEntries = getArrayFromList(System.getProperty("osgi.bundles"));
			String syspath = getSysPath();
			Bundle[] bundles = new Bundle[installEntries.length];
			boolean installedSomething = false;
			for (int i = 0; i < installEntries.length; i++) {
				String name = installEntries[i];
				int level = -1;
				int index = name.indexOf('@');
				if (index >= 0) {
					String levelString = name.substring(index + 1, name.length());
					level = Integer.parseInt(levelString);
					name = name.substring(0, index);
				}
				String location = searchForBundle(name, syspath);
				if (location == null)
					throw new IllegalArgumentException(EclipseAdaptorMsg.formatter.getString("ECLIPSE_STARTUP_BUNDLE_NOT_FOUND", name));
				// don't need to install if it is already installed
				bundles[i] = getBundleByLocation(location);
				if (bundles[i] == null) {
					bundles[i] = context.installBundle(location);
					installedSomething = true;
					if (level >= 0 && start != null)
						start.setBundleStartLevel(bundles[i], level);
				}
			}
			// If we installed something, force all basic bundles we installed to be resolved
			if (installedSomething)
				refreshPackages(bundles);
			// schedule all basic bundles to be started
			for (int i = 0; i < bundles.length; i++) {
				if (bundles[i].getState() == Bundle.INSTALLED)
					throw new IllegalStateException(EclipseAdaptorMsg.formatter.getString("ECLIPSE_STARTUP_ERROR_BUNDLE_NOT_RESOLVED", bundles[i].getLocation()));
				bundles[i].start();
			}
			context.ungetService(reference);
			if (debug)
				System.out.println("Time loadBundles in the framework: " + (System.currentTimeMillis() - startTime));
			return bundles;
		}

	private static void refreshPackages(Bundle[] bundles) {
		if (bundles.length == 0)
			return;
		ServiceReference packageAdminRef = context.getServiceReference(PackageAdmin.class.getName());
		PackageAdmin packageAdmin = null;
		if (packageAdminRef != null) {
			packageAdmin = (PackageAdmin)context.getService(packageAdminRef);
			if (packageAdmin == null)
				return;
		}
		// TODO this is such a hack it is silly.  There are still cases for race conditions etc
		// but this should allow for some progress...
		final Semaphore semaphore = new Semaphore(0);
		FrameworkListener listener = new FrameworkListener() {
			public void frameworkEvent(FrameworkEvent event) {
				if (event.getType() == FrameworkEvent.PACKAGES_REFRESHED)
					semaphore.release();
			}
		};
		context.addFrameworkListener(listener);
		packageAdmin.refreshPackages(bundles);
		semaphore.acquire();
		context.removeFrameworkListener(listener);
		context.ungetService(packageAdminRef);
	}
	
	/**
	 *  Invokes the OSGi Console on another thread
	 *
	 * @param osgi The current OSGi instance for the console to attach to
	 * @param consoleArgs An String array containing commands from the command line
	 * for the console to execute
	 * @param consolePort the port on which to run the console.  Empty string implies the default port.
	 */
	private static void startConsole(OSGi osgi, String[] consoleArgs, String consolePort) {
		try {
			String consoleClassName = System.getProperty(PROP_CONSOLE_CLASS, DEFAULT_CONSOLE_CLASS);
			Class consoleClass = Class.forName(consoleClassName);
			Class[] parameterTypes;
			Object[] parameters;
			if (consolePort.length() == 0) {
				parameterTypes = new Class[] { OSGi.class, String[].class };
				parameters = new Object[] { osgi, consoleArgs };
			} else {
				parameterTypes = new Class[] { OSGi.class, int.class, String[].class };
				parameters = new Object[] { osgi, new Integer(consolePort), consoleArgs };
			}
			Constructor constructor = consoleClass.getConstructor(parameterTypes);
			Object console = constructor.newInstance(parameters);
			Thread t = new Thread(((Runnable) console), CONSOLE_NAME);
			t.start();
		} catch (NumberFormatException nfe) {
			System.err.println("Invalid console port: " + consolePort);
		} catch (Exception ex) {
			System.out.println("Failed to find/start: " + CONSOLE_NAME);
		}

	}

	/**
	 *  Creates and returns the adaptor
	 *
	 *  @return a FrameworkAdaptor object
	 */
	private static FrameworkAdaptor createAdaptor() throws Exception {
		String adaptorClassName = System.getProperty(PROP_ADAPTOR, DEFAULT_ADAPTOR_CLASS);
		Class adaptorClass = Class.forName(adaptorClassName);
		Class[] constructorArgs = new Class[] { String[].class };
		Constructor constructor = adaptorClass.getConstructor(constructorArgs);
		return (FrameworkAdaptor) constructor.newInstance(new Object[] { new String[0] });
	}

	private static String[] processCommandLine(String[] args) throws Exception {
		EnvironmentInfo.allArgs = args;
		int[] configArgs = new int[100];
		configArgs[0] = -1; // need to initialize the first element to something that could not be an index.
		int configArgIndex = 0;
		for (int i = 0; i < args.length; i++) {
			boolean found = false;
			// check for args without parameters (i.e., a flag arg)
	
			// check if debug should be enabled for the entire platform
			// If this is the last arg or there is a following arg (i.e., arg+1 has a leading -), 
			// simply enable debug.  Otherwise, assume that that the following arg is
			// actually the filename of an options file.  This will be processed below.
			if (args[i].equalsIgnoreCase(DEBUG) && ((i + 1 == args.length) || ((i + 1 < args.length) && (args[i + 1].startsWith("-"))))) { //$NON-NLS-1$
				System.getProperties().put(PROP_DEBUG, "");	//$NON-NLS-1$
				debug = true;
				found = true;
			}
			
			// check if development mode should be enabled for the entire platform
			// If this is the last arg or there is a following arg (i.e., arg+1 has a leading -), 
			// simply enable development mode.  Otherwise, assume that that the following arg is
			// actually some additional development time class path entries.  This will be processed below.
			if (args[i].equalsIgnoreCase(DEV) && ((i + 1 == args.length) || ((i + 1 < args.length) && (args[i + 1].startsWith("-"))))) { //$NON-NLS-1$
				System.getProperties().put(PROP_DEV, "");	//$NON-NLS-1$
				found = true;
				continue;
			}

			// look for the initialization arg
			if (args[i].equalsIgnoreCase(INITIALIZE)) {
				initialize = true;
				found = true;
			}

			// look for the consoleLog flag
			if (args[i].equalsIgnoreCase(CONSOLE_LOG)) {
				System.getProperties().put(PROP_CONSOLE_LOG, "true"); //$NON-NLS-1$
				found = true;
			}

			// look for the console with no port.  
			if (args[i].equalsIgnoreCase(CONSOLE) && ((i + 1 == args.length) || ((i + 1 < args.length) && (args[i + 1].startsWith("-"))))) { //$NON-NLS-1$
				System.getProperties().put(PROP_CONSOLE, "");	//$NON-NLS-1$
				found = true;
				continue;
			}

			if (found) {
				configArgs[configArgIndex++] = i;
				continue;
			}
			// check for args with parameters. If we are at the last argument or if the next one
			// has a '-' as the first character, then we can't have an arg with a parm so continue.
			if (i == args.length - 1 || args[i + 1].startsWith("-")) { //$NON-NLS-1$
				continue;
			}
			String arg = args[++i];
	
			// look for the console and port.  
			if (args[i - 1].equalsIgnoreCase(CONSOLE)) {
				System.getProperties().put(PROP_CONSOLE, arg);
				found = true;
				continue;
			}
	
			// look for the configuration location .  
			if (args[i - 1].equalsIgnoreCase(CONFIGURATION)) {
				System.getProperties().put(LocationManager.PROP_CONFIG_AREA, arg);
				found = true;
				continue;
			}
	
			// look for the data location for this instance.  
			if (args[i - 1].equalsIgnoreCase(DATA)) {
				System.getProperties().put(LocationManager.PROP_INSTANCE_AREA, arg);
				found = true;
				continue;
			}
	
			// look for the user location for this instance.  
			if (args[i - 1].equalsIgnoreCase(USER)) {
				System.getProperties().put(LocationManager.PROP_USER_AREA, arg);
				found = true;
				continue;
			}
	
			// look for the development mode and class path entries.  
			if (args[i - 1].equalsIgnoreCase(DEV)) {
				System.getProperties().put(PROP_DEV, arg);
				found = true;
				continue;
			}
	
			// look for the debug mode and option file location.  
			if (args[i - 1].equalsIgnoreCase(DEBUG)) {
				System.getProperties().put(PROP_DEBUG, arg);
				debug = true;
				found = true;
				continue;
			}

			// look for the window system.  
			if (args[i - 1].equalsIgnoreCase(WS)) {
				System.getProperties().put(PROP_WS, arg);
				found = true;
			}
	
			// look for the operating system
			if (args[i - 1].equalsIgnoreCase(OS)) {
				System.getProperties().put(PROP_OS, arg);
				found = true;
			}
	
			// look for the system architecture
			if (args[i - 1].equalsIgnoreCase(ARCH)) {
				System.getProperties().put(PROP_ARCH, arg);
				found = true;
			}
	
			// look for the nationality/language
			if (args[i - 1].equalsIgnoreCase(NL)) {
				System.getProperties().put(PROP_NL, arg);
				found = true;
			}
			// done checking for args.  Remember where an arg was found 
			if (found) {
				configArgs[configArgIndex++] = i - 1;
				configArgs[configArgIndex++] = i;
			}
		}
	
		// remove all the arguments consumed by this argument parsing
		if (configArgIndex == 0) {
			EnvironmentInfo.frameworkArgs = new String[0];
			EnvironmentInfo.appArgs = args;
			return args;
		}
		EnvironmentInfo.appArgs = new String[args.length - configArgIndex];
		EnvironmentInfo.frameworkArgs = new String[configArgIndex];
		configArgIndex = 0;
		int j = 0;
		int k = 0;
		for (int i = 0; i < args.length; i++) {
			if (i == configArgs[configArgIndex]) {
				EnvironmentInfo.frameworkArgs[k++] = args[i];
				configArgIndex++;
			} else
				EnvironmentInfo.appArgs[j++] = args[i];
		}
		return EnvironmentInfo.appArgs;
	}
	
	/**
	 * Returns the result of converting a list of comma-separated tokens into an array
	 * 
	 * @return the array of string tokens
	 * @param prop the initial comma-separated string
	 */
	private static String[] getArrayFromList(String prop) {
		if (prop == null || prop.trim().equals("")) //$NON-NLS-1$
			return new String[0];
		Vector list = new Vector();
		StringTokenizer tokens = new StringTokenizer(prop, ","); //$NON-NLS-1$
		while (tokens.hasMoreTokens()) {
			String token = tokens.nextToken().trim();
			if (!token.equals("")) //$NON-NLS-1$
				list.addElement(token);
		}
		return list.isEmpty() ? new String[0] : (String[]) list.toArray(new String[list.size()]);
	}

	protected static String getSysPath() {
		String result = System.getProperty(PROP_SYSPATH);
		if (result != null) 
			return result;

		URL url = EclipseStarter.class.getProtectionDomain().getCodeSource().getLocation();
		result = url.getFile();
		if (result.endsWith("/"))
			result = result.substring(0, result.length() - 1);
		result = result.substring(0, result.lastIndexOf('/'));
		result = result.substring(0, result.lastIndexOf('/'));
		if (Character.isUpperCase(result.charAt(0))) {
			char[] chars = result.toCharArray();
			chars[0] = Character.toLowerCase(chars[0]);
			result = new String(chars);
		}
		return result;
	}

	private static Bundle getBundleByLocation(String location) {
		Bundle[] installed = context.getBundles();
		for (int i = 0; i < installed.length; i++) {
			Bundle bundle = installed[i];
			if (location.equalsIgnoreCase(bundle.getLocation()))
				return bundle;
		}
		return null;
	}

	private static void initializeApplicationTracker() {
		Filter filter = null;
		try {
			String appClass = ParameterizedRunnable.class.getName();
			filter = context.createFilter("(&(objectClass=" + appClass + ")(eclipse.application=*))");
		} catch (InvalidSyntaxException e) {
			// ignore this.  It should never happen as we have tested the above format.
		}
		applicationTracker = new ServiceTracker(context, filter, null);
		applicationTracker.open();
	}
	
	private static void loadConfigurationInfo() {
		Location configArea = LocationManager.getConfigurationLocation();
		if (configArea == null)
			return;
		
		URL location = null;
		try {
			location = new URL(configArea.getURL().toExternalForm() + "config.ini");
		} catch (MalformedURLException e) {
			// its ok.  Thie should never happen
		}
		mergeProperties(System.getProperties(), loadProperties(location));
	}
	
	private static void loadDefaultProperties() {
		URL codeLocation = EclipseStarter.class.getProtectionDomain().getCodeSource().getLocation();
		if (codeLocation == null)
			return;
		String location = codeLocation.getFile();
		if (location.endsWith("/"))
			location = location.substring(0, location.length() - 1);
		int i = location.lastIndexOf('/');
		location = location.substring(0, i + 1) + "eclipse.properties";
		URL result = null;
		try {
			result = new File(location).toURL();
		} catch (MalformedURLException e) {
			// its ok.  Thie should never happen
		}
		mergeProperties(System.getProperties(), loadProperties(result));
	}
	
	private static Properties loadProperties(URL location) {
		Properties result = new Properties();
		if (location ==  null)
			return result;
		try {
			InputStream in = location.openStream();
			try {
				result.load(in);
			} finally {
				in.close();
			}
		} catch (IOException e) {
			// its ok if there is no file.  We'll just use the defaults for everything
			// TODO but it might be nice to log something with gentle wording (i.e., it is not an error)
		} 
		return result;
	}
	
	private static void mergeProperties(Properties destination, Properties source) {
		for (Enumeration e = source.keys(); e.hasMoreElements(); ) {
			String key = (String)e.nextElement();
			String value = source.getProperty(key);
			if (destination.getProperty(key) == null)
				destination.put(key, value);
		}
	}
	
	private static void stopSystemBundle() throws BundleException {
		if (context == null || !running)
			return;
		Bundle systemBundle = context.getBundle(0);
		if (systemBundle.getState() == Bundle.ACTIVE) {
			final Semaphore semaphore = new Semaphore(0);
			FrameworkListener listener = new FrameworkListener() {
				public void frameworkEvent(FrameworkEvent event) {
					if (event.getType() == FrameworkEvent.STARTLEVEL_CHANGED)
						semaphore.release();
				}
				
			};
			context.addFrameworkListener(listener);
			systemBundle.stop();
			semaphore.acquire();
			context.removeFrameworkListener(listener);
		}
		context = null;
		applicationTracker = null;
		running = false;
	}
	private static void setStartLevel(final int value) {
		ServiceTracker tracker = new ServiceTracker(context, StartLevel.class.getName(), null);
		tracker.open();
		final StartLevel startLevel = (StartLevel)tracker.getService();
		final Semaphore semaphore = new Semaphore(0);
		FrameworkListener listener = new FrameworkListener() {
			public void frameworkEvent(FrameworkEvent event) {
				if (event.getType() == FrameworkEvent.STARTLEVEL_CHANGED && startLevel.getStartLevel() == value)
					semaphore.release();
			}
		};
		context.addFrameworkListener(listener);
		startLevel.setStartLevel(value);
		semaphore.acquire();
		context.removeFrameworkListener(listener);
		tracker.close();
	}
	/**
	 * Searches for the given target directory starting in the "plugins" subdirectory
	 * of the given location.  If one is found then this location is returned; 
	 * otherwise an exception is thrown.
	 * 
	 * @return the location where target directory was found
	 * @param start the location to begin searching
	 */
	private static String searchFor(final String target, String start) {
		FileFilter filter = new FileFilter() {
			public boolean accept(File candidate) {
				return candidate.isDirectory() && (candidate.getName().equals(target) || candidate.getName().startsWith(target + "_")); //$NON-NLS-1$
			}
		};
		File[] candidates = new File(start).listFiles(filter); //$NON-NLS-1$
		if (candidates == null)
			return null;
		String result = null;
		Object maxVersion = null;
		for (int i = 0; i < candidates.length; i++) {
			String name = candidates[i].getName();
			String version = ""; //$NON-NLS-1$ // Note: directory with version suffix is always > than directory without version suffix
			int index = name.indexOf('_');
			if (index != -1)
				version = name.substring(index + 1);
			Object currentVersion = getVersionElements(version);
			if (maxVersion == null) {
				result = candidates[i].getAbsolutePath();
				maxVersion = currentVersion;
			} else {
				if (compareVersion((Object[]) maxVersion, (Object[]) currentVersion) < 0) {
					result = candidates[i].getAbsolutePath();
					maxVersion = currentVersion;
				}
			}
		}
		if (result == null)
			return null;
		return result.replace(File.separatorChar, '/') + "/"; //$NON-NLS-1$
	}
	/**
	 * Do a quick parse of version identifier so its elements can be correctly compared.
	 * If we are unable to parse the full version, remaining elements are initialized
	 * with suitable defaults.
	 * @return an array of size 4; first three elements are of type Integer (representing
	 * major, minor and service) and the fourth element is of type String (representing
	 * qualifier). Note, that returning anything else will cause exceptions in the caller.
	 */
	private static Object[] getVersionElements(String version) {
		Object[] result = { new Integer(0), new Integer(0), new Integer(0), "" }; //$NON-NLS-1$
		StringTokenizer t = new StringTokenizer(version, "."); //$NON-NLS-1$
		String token;
		int i = 0;
		while (t.hasMoreTokens() && i < 4) {
			token = t.nextToken();
			if (i < 3) {
				// major, minor or service ... numeric values
				try {
					result[i++] = new Integer(token);
				} catch (Exception e) {
					// invalid number format - use default numbers (0) for the rest
					break;
				}
			} else {
				// qualifier ... string value
				result[i++] = token;
			}
		}
		return result;
	}
	/**
	 * Compares version strings. 
	 * @return result of comparison, as integer;
	 * <code><0</code> if left < right;
	 * <code>0</code> if left == right;
	 * <code>>0</code> if left > right;
	 */
	private static int compareVersion(Object[] left, Object[] right) {
		int result = ((Integer) left[0]).compareTo((Integer) right[0]); // compare major
		if (result != 0)
			return result;

		result = ((Integer) left[1]).compareTo((Integer) right[1]); // compare minor
		if (result != 0)
			return result;

		result = ((Integer) left[2]).compareTo((Integer) right[2]); // compare service
		if (result != 0)
			return result;

		return ((String) left[3]).compareTo((String) right[3]); // compare qualifier
	}

	private static String buildCommandLine(String arg, String value) {
		StringBuffer result = new StringBuffer(300);
		String entry = System.getProperty(PROP_VM);
		if (entry == null)
			return null;
		result.append(entry );
		result.append('\n');
		// append the vmargs and commands.  Assume that these already end in \n
		entry = System.getProperty(PROP_VMARGS);
		if (entry != null) 
			result.append(entry);
		entry = System.getProperty(PROP_COMMANDS);
		if (entry != null)
			result.append(entry);
		String commandLine = result.toString();
		int i = commandLine.indexOf(arg + "\n");
		if (i == 0)
			commandLine += arg + "\n" + value + "\n";
		else {
			i += arg.length() + 1;
			String left = commandLine.substring(0, i);
			int j = commandLine.indexOf('\n', i);
			String right = commandLine.substring(j);
			commandLine = left + value + right;
		}
		return commandLine;
	}
}