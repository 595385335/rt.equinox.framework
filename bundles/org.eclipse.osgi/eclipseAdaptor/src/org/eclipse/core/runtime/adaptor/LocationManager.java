/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
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
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import org.eclipse.osgi.framework.adaptor.FrameworkAdaptor;
import org.eclipse.osgi.service.datalocation.Location;

public class LocationManager {
	private static Location installLocation = null;
	private static Location configurationLocation = null;
	private static Location userLocation = null;
	private static Location instanceLocation = null;

	public static final String PROP_INSTALL_AREA = "osgi.install.area"; //$NON-NLS-1$
	public static final String PROP_CONFIG_AREA = "osgi.configuration.area"; //$NON-NLS-1$
	public static final String PROP_SHARED_CONFIG_AREA = "osgi.sharedConfiguration.area"; //$NON-NLS-1$
	public static final String PROP_INSTANCE_AREA = "osgi.instance.area"; //$NON-NLS-1$
	public static final String PROP_USER_AREA = "osgi.user.area"; //$NON-NLS-1$
	public static final String PROP_MANIFEST_CACHE = "osgi.manifest.cache"; //$NON-NLS-1$
	public static final String PROP_USER_HOME = "user.home"; //$NON-NLS-1$
	public static final String PROP_USER_DIR = "user.dir"; //$NON-NLS-1$

	// configuration area file/dir names
	public static final String BUNDLES_DIR = "bundles"; //$NON-NLS-1$
	public static final String FRAMEWORK_FILE = ".framework"; //$NON-NLS-1$
	public static final String STATE_FILE = ".state"; //$NON-NLS-1$
	public static final String BUNDLE_DATA_FILE = ".bundledata"; //$NON-NLS-1$
	public static final String MANIFESTS_DIR = "manifests"; //$NON-NLS-1$
	public static final String CONFIG_FILE = "config.ini"; //$NON-NLS-1$
	public static final String ECLIPSE_PROPERTIES = "eclipse.properties"; //$NON-NLS-1$

	// Constants for configuration location discovery
	private static final String ECLIPSE = "eclipse"; //$NON-NLS-1$
	private static final String PRODUCT_SITE_MARKER = ".eclipseproduct"; //$NON-NLS-1$
	private static final String PRODUCT_SITE_ID = "id"; //$NON-NLS-1$
	private static final String PRODUCT_SITE_VERSION = "version"; //$NON-NLS-1$

	private static final String CONFIG_DIR = "configuration"; //$NON-NLS-1$

	// Data mode constants for user, configuration and data locations.
	private static final String NONE = "@none"; //$NON-NLS-1$
	private static final String NO_DEFAULT = "@noDefault"; //$NON-NLS-1$
	private static final String USER_HOME = "@user.home"; //$NON-NLS-1$
	private static final String USER_DIR = "@user.dir"; //$NON-NLS-1$

	public static URL buildURL(String spec, boolean trailingSlash) {
		if (spec == null)
			return null;
		boolean isFile = spec.startsWith("file:"); //$NON-NLS-1$
		try {
			if (isFile)
				return adjustTrailingSlash(new File(spec.substring(5)).toURL(), trailingSlash);
			else
				return new URL(spec);
		} catch (MalformedURLException e) {
			// if we failed and it is a file spec, there is nothing more we can do
			// otherwise, try to make the spec into a file URL.
			if (isFile)
				return null;
			try {
				return adjustTrailingSlash(new File(spec).toURL(), trailingSlash);
			} catch (MalformedURLException e1) {
				return null;
			}
		}
	}

	private static URL adjustTrailingSlash(URL url, boolean trailingSlash) throws MalformedURLException {
		String file = url.getFile();
		if (trailingSlash == (file.endsWith("/"))) //$NON-NLS-1$
			return url;
		file = trailingSlash ? file + "/" : file.substring(0, file.length() - 1); //$NON-NLS-1$
		return new URL(url.getProtocol(), url.getHost(), file);
	}

	private static void mungeConfigurationLocation() {
		// if the config property was set, munge it for backwards compatibility.
		String location = System.getProperty(PROP_CONFIG_AREA);
		if (location != null) {
			location = buildURL(location, false).toExternalForm();
			if (location.endsWith(".cfg")) { //$NON-NLS-1$
				int index = location.lastIndexOf('/');
				location = location.substring(0, index + 1);
			}
			if (!location.endsWith("/")) //$NON-NLS-1$
				location += "/"; //$NON-NLS-1$
			System.getProperties().put(PROP_CONFIG_AREA, location);
		}
	}

	public static void initializeLocations() {
		URL defaultLocation = buildURL(System.getProperty(PROP_USER_HOME), true);
		userLocation = buildLocation(PROP_USER_AREA, defaultLocation, "user", false); //$NON-NLS-1$

		defaultLocation = buildURL(new File(System.getProperty(PROP_USER_DIR), "workspace").getAbsolutePath(), true); //$NON-NLS-1$
		instanceLocation = buildLocation(PROP_INSTANCE_AREA, defaultLocation, "workspace", false); //$NON-NLS-1$

		mungeConfigurationLocation();
		// compute a default but it is very unlikely to be used since main will have computed everything
		defaultLocation = buildURL(computeDefaultConfigurationLocation(), true);
		configurationLocation = buildLocation(PROP_CONFIG_AREA, defaultLocation, CONFIG_DIR, false);
		// get the parent location based on the system property. This will have been set on the 
		// way in either by the caller/user or by main.  There will be no parent location if we are not 
		// cascaded.
		URL parentLocation = computeSharedConfigurationLocation();
		if (parentLocation != null && !parentLocation.equals(configurationLocation.getURL())) {
			Location parent = new BasicLocation(null, parentLocation, true);
			((BasicLocation) configurationLocation).setParent(parent);
		}
		initializeDerivedConfigurationLocations();

		// assumes that the property is already set
		installLocation = buildLocation(PROP_INSTALL_AREA, null, null, true);
	}

	private static Location buildLocation(String property, URL defaultLocation, String userDefaultAppendage, boolean readOnly) {
		BasicLocation result = null;
		String location = System.getProperty(property);
		System.getProperties().remove(property);
		// if the instance location is not set, predict where the workspace will be and 
		// put the instance area inside the workspace meta area.
		if (location == null)
			result = new BasicLocation(property, defaultLocation, readOnly);
		else if (location.equalsIgnoreCase(NONE))
			return null;
		else if (location.equalsIgnoreCase(NO_DEFAULT))
			result = new BasicLocation(property, null, readOnly);
		else {
			if (location.equalsIgnoreCase(USER_HOME))
				location = computeDefaultUserAreaLocation(userDefaultAppendage);
			if (location.equalsIgnoreCase(USER_DIR))
				location = new File(System.getProperty(PROP_USER_DIR), userDefaultAppendage).getAbsolutePath();
			URL url = buildURL(location, true);
			if (url != null) {
				result = new BasicLocation(property, null, readOnly);
				result.setURL(url, false);
			}
		}
		return result;
	}

	private static void initializeDerivedConfigurationLocations() {
		if (System.getProperty(PROP_MANIFEST_CACHE) == null)
			System.getProperties().put(PROP_MANIFEST_CACHE, getConfigurationFile(MANIFESTS_DIR).getAbsolutePath());
	}

	private static URL computeInstallConfigurationLocation() {
		String property = System.getProperty(PROP_INSTALL_AREA);
		try {
			return new URL(property);
		} catch (MalformedURLException e) {
			// do nothing here since it is basically impossible to get a bogus url 
		}
		return null;
	}

	private static URL computeSharedConfigurationLocation() {
		String property = System.getProperty(PROP_SHARED_CONFIG_AREA);
		if (property == null)
			return null;
		try {
			return new URL(property);
		} catch (MalformedURLException e) {
			// do nothing here since it is basically impossible to get a bogus url 
		}
		return null;
	}

	private static String computeDefaultConfigurationLocation() {
		// 1) We store the config state relative to the 'eclipse' directory if possible
		// 2) If this directory is read-only 
		//    we store the state in <user.home>/.eclipse/<application-id>_<version> where <user.home> 
		//    is unique for each local user, and <application-id> is the one 
		//    defined in .eclipseproduct marker file. If .eclipseproduct does not
		//    exist, use "eclipse" as the application-id.

		URL installURL = computeInstallConfigurationLocation();
		File installDir = new File(installURL.getFile());
		if ("file".equals(installURL.getProtocol()) && installDir.canWrite()) //$NON-NLS-1$
			return new File(installDir, CONFIG_DIR).getAbsolutePath();

		// We can't write in the eclipse install dir so try for some place in the user's home dir
		return computeDefaultUserAreaLocation(CONFIG_DIR);
	}

	private static String computeDefaultUserAreaLocation(String pathAppendage) {
		//    we store the state in <user.home>/.eclipse/<application-id>_<version> where <user.home> 
		//    is unique for each local user, and <application-id> is the one 
		//    defined in .eclipseproduct marker file. If .eclipseproduct does not
		//    exist, use "eclipse" as the application-id.
		String installProperty = System.getProperty(PROP_INSTALL_AREA);
		URL installURL = buildURL(installProperty, true);
		if (installURL == null)
			return null;
		File installDir = new File(installURL.getFile());
		String appName = "." + ECLIPSE; //$NON-NLS-1$
		File eclipseProduct = new File(installDir, PRODUCT_SITE_MARKER);
		if (eclipseProduct.exists()) {
			Properties props = new Properties();
			try {
				props.load(new FileInputStream(eclipseProduct));
				String appId = props.getProperty(PRODUCT_SITE_ID);
				if (appId == null || appId.trim().length() == 0)
					appId = ECLIPSE;
				String appVersion = props.getProperty(PRODUCT_SITE_VERSION);
				if (appVersion == null || appVersion.trim().length() == 0)
					appVersion = ""; //$NON-NLS-1$
				appName += File.separator + appId + "_" + appVersion; //$NON-NLS-1$
			} catch (IOException e) {
				// Do nothing if we get an exception.  We will default to a standard location 
				// in the user's home dir.
			}
		}
		String userHome = System.getProperty(PROP_USER_HOME);
		return new File(userHome, appName + "/" + pathAppendage).getAbsolutePath(); //$NON-NLS-1$
	}

	public static Location getUserLocation() {
		return userLocation;
	}

	public static Location getConfigurationLocation() {
		return configurationLocation;
	}

	public static Location getInstallLocation() {
		return installLocation;
	}

	public static Location getInstanceLocation() {
		return instanceLocation;
	}

	public static File getOSGiConfigurationDir() {
		// TODO assumes the URL is a file: url
		return new File(configurationLocation.getURL().getFile(), FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME);
	}

	public static File getConfigurationFile(String filename) {
		File dir = getOSGiConfigurationDir();
		if (!dir.exists())
			dir.mkdirs();
		return new File(dir, filename);
	}
}